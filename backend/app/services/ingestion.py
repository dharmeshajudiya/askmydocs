import asyncio
import traceback
from io import BytesIO

import structlog
from langchain.text_splitter import RecursiveCharacterTextSplitter
from openai import AsyncOpenAI
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.models.document import Document
from app.utils.pinecone import get_pinecone_index
from app.utils.s3 import download_file_from_s3

logger = structlog.get_logger()

openai_client = AsyncOpenAI(api_key=settings.openai_api_key)
splitter = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)


def _extract_text_pdf(file_bytes: bytes) -> list[dict]:
    import fitz  # PyMuPDF

    pages = []
    with fitz.open(stream=file_bytes, filetype="pdf") as doc:
        for page_num, page in enumerate(doc, start=1):
            text = page.get_text()
            if text.strip():
                pages.append({"text": text, "page_number": page_num})
    return pages


def _extract_text_docx(file_bytes: bytes) -> list[dict]:
    from docx import Document as DocxDocument

    doc = DocxDocument(BytesIO(file_bytes))
    full_text = "\n".join(p.text for p in doc.paragraphs if p.text.strip())
    return [{"text": full_text, "page_number": 1}]


def _extract_text_txt(file_bytes: bytes) -> list[dict]:
    text = file_bytes.decode("utf-8", errors="replace")
    return [{"text": text, "page_number": 1}]


def _extract_text(file_bytes: bytes, filename: str) -> list[dict]:
    ext = filename.lower().rsplit(".", 1)[-1]
    if ext == "pdf":
        return _extract_text_pdf(file_bytes)
    elif ext == "docx":
        return _extract_text_docx(file_bytes)
    else:
        return _extract_text_txt(file_bytes)


async def _embed_batch(texts: list[str]) -> list[list[float]]:
    response = await openai_client.embeddings.create(
        model="text-embedding-3-small",
        input=texts,
    )
    return [item.embedding for item in response.data]


async def ingest(
    doc_id: str,
    user_id: str,
    s3_url: str,
    filename: str,
    session: AsyncSession,
) -> None:
    log = logger.bind(doc_id=doc_id, user_id=user_id)

    # Mark as processing before doing any work (prevents duplicate jobs on crash/restart)
    result = await session.execute(select(Document).where(Document.id == doc_id))
    document = result.scalar_one()
    document.status = "processing"
    await session.commit()
    log.info("ingestion_started")

    try:
        # 1. Download from S3
        file_bytes = await download_file_from_s3(s3_url)
        log.info("s3_download_complete", bytes=len(file_bytes))

        # 2. Extract text with page numbers (runs in executor since PyMuPDF is sync)
        loop = asyncio.get_event_loop()
        pages = await loop.run_in_executor(None, _extract_text, file_bytes, filename)
        log.info("text_extracted", pages=len(pages))

        # 3. Chunk with LangChain splitter
        chunks: list[dict] = []
        chunk_index = 0
        for page in pages:
            splits = splitter.split_text(page["text"])
            char_cursor = 0
            for split_text in splits:
                char_start = page["text"].find(split_text, char_cursor)
                char_end = char_start + len(split_text)
                char_cursor = char_end
                chunks.append({
                    "text": split_text,
                    "metadata": {
                        "doc_id": doc_id,
                        "chunk_index": chunk_index,
                        "page_number": page["page_number"],
                        "char_start": char_start,
                        "char_end": char_end,
                        "user_id": user_id,
                    },
                })
                chunk_index += 1

        log.info("chunking_complete", total_chunks=len(chunks))

        # 4. Embed in batches of 100
        namespace = f"user_{user_id}"
        index = get_pinecone_index()
        batch_size = 100

        for batch_start in range(0, len(chunks), batch_size):
            batch = chunks[batch_start : batch_start + batch_size]
            texts = [c["text"] for c in batch]
            embeddings = await _embed_batch(texts)

            # 5. Upsert to Pinecone
            vectors = [
                (
                    f"{doc_id}_{c['metadata']['chunk_index']}",
                    emb,
                    c["metadata"],
                )
                for c, emb in zip(batch, embeddings)
            ]

            await loop.run_in_executor(
                None,
                lambda v=vectors: index.upsert(vectors=v, namespace=namespace),
            )
            log.info("batch_upserted", batch_start=batch_start, batch_size=len(batch))

        # 6. Mark ready
        document.status = "ready"
        document.total_chunks = len(chunks)
        await session.commit()
        log.info("ingestion_complete", total_chunks=len(chunks))

    except Exception:
        tb = traceback.format_exc()
        log.error("ingestion_failed", traceback=tb)
        document.status = "error"
        document.error_log = tb
        await session.commit()
        raise
