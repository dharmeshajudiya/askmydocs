import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict


class QueryRequest(BaseModel):
    document_id: uuid.UUID
    question: str


class SourceChunk(BaseModel):
    chunk_text: str
    page: int
    score: float


class QueryHistoryResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    document_id: uuid.UUID
    question: str
    answer: str
    source_chunks: list[SourceChunk]
    latency_ms: int | None
    created_at: datetime
