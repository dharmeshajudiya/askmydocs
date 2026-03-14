import time
import uuid
from collections.abc import AsyncGenerator

import structlog
from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.responses import StreamingResponse
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.database import get_session
from app.models.document import Document
from app.models.query_history import QueryHistory
from app.models.user import User
from app.schemas.query import QueryHistoryResponse, QueryRequest
from app.services.generation import stream_answer
from app.services.retrieval import retrieve_chunks

logger = structlog.get_logger()

router = APIRouter()


@router.post("")
async def query_document(
    body: QueryRequest,
    session: AsyncSession = Depends(get_session),
    current_user: User = Depends(get_current_user),
):
    # Verify the user owns the document
    result = await session.execute(
        select(Document).where(
            Document.id == body.document_id,
            Document.user_id == current_user.id,
            Document.status == "ready",
        )
    )
    document = result.scalar_one_or_none()
    if not document:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Document not found or not ready",
        )

    log = logger.bind(doc_id=str(body.document_id), user_id=str(current_user.id))
    log.info("query_started", question=body.question)

    start_ms = time.monotonic_ns() // 1_000_000
    chunks = await retrieve_chunks(
        question=body.question,
        doc_id=str(body.document_id),
        user_id=str(current_user.id),
    )

    async def event_stream() -> AsyncGenerator[str, None]:
        full_answer_parts: list[str] = []

        async for token in stream_answer(body.question, chunks):
            full_answer_parts.append(token)
            yield f"data: {token}\n\n"

        yield "data: [DONE]\n\n"

        # Persist to query_history after streaming completes
        full_answer = "".join(full_answer_parts)
        latency_ms = (time.monotonic_ns() // 1_000_000) - start_ms

        history = QueryHistory(
            user_id=current_user.id,
            document_id=body.document_id,
            question=body.question,
            answer=full_answer,
            source_chunks=[
                {"chunk_text": c["text"], "page": c["page"], "score": c["score"]}
                for c in chunks
            ],
            latency_ms=latency_ms,
        )
        async with session.begin():
            session.add(history)

        log.info("query_completed", latency_ms=latency_ms)

    return StreamingResponse(event_stream(), media_type="text/event-stream")


@router.get("/history", response_model=list[QueryHistoryResponse])
async def get_query_history(
    session: AsyncSession = Depends(get_session),
    current_user: User = Depends(get_current_user),
):
    result = await session.execute(
        select(QueryHistory)
        .where(QueryHistory.user_id == current_user.id)
        .order_by(QueryHistory.created_at.desc())
    )
    return [QueryHistoryResponse.model_validate(row) for row in result.scalars().all()]
