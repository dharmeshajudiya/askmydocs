import uuid
from unittest.mock import AsyncMock, patch

import pytest


@pytest.mark.asyncio
class TestDocumentUpload:
    async def test_upload_pdf_returns_202(self, client):
        with (
            patch("app.api.documents.upload_file_to_s3", new_callable=AsyncMock) as mock_s3,
            patch("app.api.documents.ingest_document") as mock_task,
        ):
            mock_s3.return_value = "s3://bucket/documents/test.pdf"
            mock_task.delay = AsyncMock()

            response = await client.post(
                "/documents",
                files={"file": ("test.pdf", b"%PDF-1.4 sample content", "application/pdf")},
            )

        assert response.status_code == 202
        data = response.json()
        assert "id" in data
        assert data["status"] == "queued"
        assert data["filename"] == "test.pdf"

    async def test_upload_rejects_unsupported_type(self, client):
        response = await client.post(
            "/documents",
            files={"file": ("image.png", b"\x89PNG\r\n", "image/png")},
        )
        assert response.status_code == 415

    async def test_upload_txt_accepted(self, client):
        with (
            patch("app.api.documents.upload_file_to_s3", new_callable=AsyncMock) as mock_s3,
            patch("app.api.documents.ingest_document") as mock_task,
        ):
            mock_s3.return_value = "s3://bucket/documents/notes.txt"
            mock_task.delay = AsyncMock()

            response = await client.post(
                "/documents",
                files={"file": ("notes.txt", b"plain text content", "text/plain")},
            )

        assert response.status_code == 202


@pytest.mark.asyncio
class TestDocumentList:
    async def test_list_returns_empty_for_new_user(self, client):
        response = await client.get("/documents")
        assert response.status_code == 200
        assert isinstance(response.json(), list)


@pytest.mark.asyncio
class TestDocumentStatus:
    async def test_status_returns_404_for_unknown_doc(self, client):
        fake_id = uuid.uuid4()
        response = await client.get(f"/documents/{fake_id}/status")
        assert response.status_code == 404

    async def test_status_returns_queued(self, client, session, mock_user):
        from app.models.document import Document

        doc = Document(
            id=uuid.uuid4(),
            user_id=mock_user.id,
            filename="test.pdf",
            s3_url="s3://bucket/test.pdf",
            status="queued",
        )
        session.add(doc)
        await session.commit()

        response = await client.get(f"/documents/{doc.id}/status")
        assert response.status_code == 200
        assert response.json()["status"] == "queued"


@pytest.mark.asyncio
class TestDocumentDelete:
    async def test_delete_returns_404_for_unknown(self, client):
        fake_id = uuid.uuid4()
        response = await client.delete(f"/documents/{fake_id}")
        assert response.status_code == 404

    async def test_delete_removes_document(self, client, session, mock_user):
        from app.models.document import Document

        doc = Document(
            id=uuid.uuid4(),
            user_id=mock_user.id,
            filename="to_delete.pdf",
            s3_url="s3://bucket/to_delete.pdf",
            status="ready",
        )
        session.add(doc)
        await session.commit()

        with (
            patch("app.api.documents.delete_document_vectors", new_callable=AsyncMock),
            patch("app.api.documents.delete_file_from_s3", new_callable=AsyncMock),
        ):
            response = await client.delete(f"/documents/{doc.id}")

        assert response.status_code == 204

        # Verify gone
        status_response = await client.get(f"/documents/{doc.id}/status")
        assert status_response.status_code == 404
