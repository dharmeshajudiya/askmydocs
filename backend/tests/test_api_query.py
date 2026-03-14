import uuid
from unittest.mock import AsyncMock, patch

import pytest


@pytest.mark.asyncio
class TestQueryEndpoint:
    async def test_query_returns_404_for_unowned_doc(self, client):
        response = await client.post(
            "/query",
            json={"document_id": str(uuid.uuid4()), "question": "What is this about?"},
        )
        assert response.status_code == 404

    async def test_query_returns_404_for_non_ready_doc(self, client, session, mock_user):
        from app.models.document import Document

        doc = Document(
            id=uuid.uuid4(),
            user_id=mock_user.id,
            filename="proc.pdf",
            s3_url="s3://bucket/proc.pdf",
            status="processing",
        )
        session.add(doc)
        await session.commit()

        response = await client.post(
            "/query",
            json={"document_id": str(doc.id), "question": "What is this?"},
        )
        assert response.status_code == 404

    async def test_query_streams_sse_for_ready_doc(self, client, session, mock_user):
        from app.models.document import Document

        doc = Document(
            id=uuid.uuid4(),
            user_id=mock_user.id,
            filename="ready.pdf",
            s3_url="s3://bucket/ready.pdf",
            status="ready",
            total_chunks=10,
        )
        session.add(doc)
        await session.commit()

        mock_chunks = [
            {"text": "The liability cap is $1M.", "page": 6, "score": 0.95, "chunk_index": 0}
        ]

        async def mock_stream(question, chunks):
            yield "The "
            yield "liability "
            yield "cap "
            yield "is $1M."

        with (
            patch("app.api.query.retrieve_chunks", new_callable=AsyncMock, return_value=mock_chunks),
            patch("app.api.query.stream_answer", side_effect=mock_stream),
        ):
            response = await client.post(
                "/query",
                json={"document_id": str(doc.id), "question": "What is the liability cap?"},
            )

        assert response.status_code == 200
        assert "text/event-stream" in response.headers["content-type"]
        body = response.text
        assert "The " in body
        assert "[DONE]" in body


@pytest.mark.asyncio
class TestQueryHistory:
    async def test_history_returns_empty_list(self, client):
        response = await client.get("/query/history")
        assert response.status_code == 200
        assert isinstance(response.json(), list)

    async def test_history_returns_past_queries(self, client, session, mock_user):
        from app.models.document import Document
        from app.models.query_history import QueryHistory

        doc = Document(
            id=uuid.uuid4(),
            user_id=mock_user.id,
            filename="hist.pdf",
            s3_url="s3://bucket/hist.pdf",
            status="ready",
        )
        session.add(doc)
        await session.flush()

        history = QueryHistory(
            user_id=mock_user.id,
            document_id=doc.id,
            question="What are the termination conditions?",
            answer="Termination is covered in clause 12.3 on page 4.",
            source_chunks=[{"chunk_text": "...", "page": 4, "score": 0.97}],
            latency_ms=1200,
        )
        session.add(history)
        await session.commit()

        response = await client.get("/query/history")
        assert response.status_code == 200
        data = response.json()
        assert len(data) >= 1
        assert data[0]["question"] == "What are the termination conditions?"
