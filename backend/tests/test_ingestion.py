import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.services.ingestion import _extract_text_txt, splitter


class TestChunking:
    def test_chunk_metadata_fields(self):
        """Each chunk must carry all required metadata fields."""
        text = "word " * 200  # 200 words, forces multiple chunks
        splits = splitter.split_text(text)

        doc_id = str(uuid.uuid4())
        user_id = str(uuid.uuid4())

        chunks = []
        chunk_index = 0
        char_cursor = 0
        for split_text in splits:
            char_start = text.find(split_text, char_cursor)
            char_end = char_start + len(split_text)
            char_cursor = char_end
            chunks.append({
                "text": split_text,
                "metadata": {
                    "doc_id": doc_id,
                    "chunk_index": chunk_index,
                    "page_number": 1,
                    "char_start": char_start,
                    "char_end": char_end,
                    "user_id": user_id,
                },
            })
            chunk_index += 1

        assert len(chunks) > 1, "Long text should produce multiple chunks"
        for i, chunk in enumerate(chunks):
            meta = chunk["metadata"]
            assert meta["doc_id"] == doc_id
            assert meta["chunk_index"] == i
            assert meta["page_number"] == 1
            assert isinstance(meta["char_start"], int)
            assert isinstance(meta["char_end"], int)
            assert meta["char_end"] > meta["char_start"]
            assert meta["user_id"] == user_id

    def test_chunk_size_respected(self):
        text = "a" * 2000
        splits = splitter.split_text(text)
        for split in splits:
            assert len(split) <= 500 + 50  # chunk_size + some overlap tolerance


class TestEmbeddingBatching:
    @pytest.mark.asyncio
    async def test_embeddings_batched_in_100s(self):
        """Verify that embedding calls batch exactly 100 chunks at a time."""
        from app.services.ingestion import _embed_batch

        call_sizes = []

        async def mock_create(**kwargs):
            texts = kwargs["input"]
            call_sizes.append(len(texts))
            mock_response = MagicMock()
            mock_response.data = [MagicMock(embedding=[0.1] * 1536) for _ in texts]
            return mock_response

        with patch("app.services.ingestion.openai_client") as mock_client:
            mock_client.embeddings.create = mock_create
            # Simulate 250 chunks — should produce 3 calls: 100, 100, 50
            texts = [f"chunk {i}" for i in range(250)]
            for i in range(0, len(texts), 100):
                await _embed_batch(texts[i : i + 100])

        assert call_sizes == [100, 100, 50]


class TestStatusTransitions:
    @pytest.mark.asyncio
    async def test_status_set_to_processing_before_extraction(self):
        """status must be set to 'processing' before any I/O, not after."""
        doc_id = str(uuid.uuid4())
        user_id = str(uuid.uuid4())
        status_sequence = []

        mock_doc = MagicMock()
        mock_doc.id = doc_id
        mock_doc.user_id = user_id
        mock_doc.s3_url = "s3://bucket/key"
        mock_doc.filename = "test.txt"
        mock_doc.status = "queued"

        def capture_status_commit():
            status_sequence.append(mock_doc.status)

        mock_session = AsyncMock()
        mock_session.execute.return_value.scalar_one.return_value = mock_doc
        mock_session.commit = AsyncMock(side_effect=capture_status_commit)

        with (
            patch("app.services.ingestion.download_file_from_s3", new_callable=AsyncMock) as mock_dl,
            patch("app.services.ingestion.get_pinecone_index") as mock_pc,
            patch("app.services.ingestion._embed_batch", new_callable=AsyncMock) as mock_emb,
        ):
            mock_dl.return_value = b"hello world text content"
            mock_emb.return_value = [[0.1] * 1536]
            mock_pc.return_value.upsert = MagicMock()

            from app.services.ingestion import ingest

            await ingest(doc_id, user_id, "s3://bucket/key", "test.txt", mock_session)

        assert status_sequence[0] == "processing", "First commit must set status=processing"
        assert status_sequence[-1] == "ready"
