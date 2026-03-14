from collections.abc import AsyncGenerator

import structlog
from anthropic import AsyncAnthropic

from app.config import settings

logger = structlog.get_logger()

anthropic_client = AsyncAnthropic(api_key=settings.anthropic_api_key)

SYSTEM_PROMPT = (
    "You are a document assistant. Answer ONLY from the provided context. "
    "If the answer is not in the context, say \"I couldn't find that in the document.\" "
    "Always cite the page number for each fact."
)

# Rough token budget: ~4 chars per token
_MAX_CONTEXT_CHARS = 6000 * 4


def _build_context_block(chunks: list[dict]) -> str:
    """Build the context section, trimming lowest-ranked chunks if over token budget."""
    lines: list[str] = []
    total_chars = 0

    for i, chunk in enumerate(chunks):
        block = f"[CHUNK {i + 1} — page {chunk['page']}]\n{chunk['text']}\n"
        if total_chars + len(block) > _MAX_CONTEXT_CHARS:
            logger.warning("context_truncated", dropped_chunk_index=i)
            break
        lines.append(block)
        total_chars += len(block)

    return "\n".join(lines)


async def stream_answer(
    question: str, chunks: list[dict]
) -> AsyncGenerator[str, None]:
    context = _build_context_block(chunks)

    user_message = f"Context:\n{context}\n\nQuestion: {question}"

    async with anthropic_client.messages.stream(
        model="claude-sonnet-4-6",
        max_tokens=1024,
        temperature=0,
        system=SYSTEM_PROMPT,
        messages=[{"role": "user", "content": user_message}],
    ) as stream:
        async for text in stream.text_stream:
            yield text
