import asyncio

import structlog
from anthropic import AsyncAnthropic
from openai import AsyncOpenAI

from app.config import settings
from app.utils.pinecone import get_pinecone_index

logger = structlog.get_logger()

openai_client = AsyncOpenAI(api_key=settings.openai_api_key)
anthropic_client = AsyncAnthropic(api_key=settings.anthropic_api_key)


async def prewarm_reranker() -> None:
    """Send a dummy rerank call on startup to avoid cold-start latency."""
    try:
        import cohere

        co = cohere.AsyncClient(settings.cohere_api_key)
        await co.rerank(
            model="rerank-3",
            query="warmup",
            documents=["warmup document"],
            top_n=1,
        )
        logger.info("cohere_reranker_prewarmed")
    except Exception as exc:
        logger.warning("cohere_prewarm_failed", error=str(exc))


async def _rewrite_query(question: str) -> str:
    """Rewrite short questions to be more specific for vector search."""
    message = await anthropic_client.messages.create(
        model="claude-haiku-4-5-20251001",
        max_tokens=150,
        messages=[
            {
                "role": "user",
                "content": f"Rewrite this question to be specific and self-contained for document search: {question}",
            }
        ],
    )
    return message.content[0].text.strip()


async def retrieve_chunks(question: str, doc_id: str, user_id: str) -> list[dict]:
    log = logger.bind(doc_id=doc_id, user_id=user_id)

    # 1. Query rewrite — skip if already long enough
    word_count = len(question.split())
    if word_count <= 20:
        rewritten = await _rewrite_query(question)
        log.info("query_rewritten", original=question, rewritten=rewritten)
    else:
        rewritten = question

    # 2. Embed query
    response = await openai_client.embeddings.create(
        model="text-embedding-3-small",
        input=[rewritten],
    )
    query_vector = response.data[0].embedding

    # 3. Vector search — filter to specific document
    namespace = f"user_{user_id}"
    loop = asyncio.get_event_loop()
    index = get_pinecone_index()

    pinecone_results = await loop.run_in_executor(
        None,
        lambda: index.query(
            vector=query_vector,
            top_k=20,
            namespace=namespace,
            filter={"doc_id": {"$eq": doc_id}},
            include_metadata=True,
        ),
    )

    matches = pinecone_results.get("matches", [])
    log.info("vector_search_complete", num_results=len(matches))

    if not matches:
        return []

    # 4. Rerank with Cohere — fallback to top-5 by raw score if unavailable
    try:
        import cohere

        co = cohere.AsyncClient(settings.cohere_api_key)
        docs = [m["metadata"].get("text", m.get("id", "")) for m in matches]
        # Metadata stores the chunk text; fall back gracefully
        docs_for_rerank = []
        for m in matches:
            text = m.get("metadata", {}).get("chunk_text") or m.get("metadata", {}).get("text", "")
            docs_for_rerank.append(text)

        rerank_response = await co.rerank(
            model="rerank-3",
            query=rewritten,
            documents=docs_for_rerank,
            top_n=5,
        )

        top_chunks = []
        for result in rerank_response.results:
            match = matches[result.index]
            meta = match.get("metadata", {})
            top_chunks.append({
                "text": docs_for_rerank[result.index],
                "page": meta.get("page_number", 1),
                "score": result.relevance_score,
                "chunk_index": meta.get("chunk_index", 0),
            })

    except Exception as exc:
        log.warning("cohere_rerank_failed_falling_back", error=str(exc))
        # Fallback: top-5 by cosine score
        sorted_matches = sorted(matches, key=lambda m: m.get("score", 0), reverse=True)[:5]
        top_chunks = []
        for match in sorted_matches:
            meta = match.get("metadata", {})
            text = meta.get("chunk_text") or meta.get("text", "")
            top_chunks.append({
                "text": text,
                "page": meta.get("page_number", 1),
                "score": match.get("score", 0.0),
                "chunk_index": meta.get("chunk_index", 0),
            })

    log.info("retrieval_complete", top_chunks=len(top_chunks))
    return top_chunks
