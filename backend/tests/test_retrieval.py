from unittest.mock import AsyncMock, MagicMock, patch

import pytest


class TestRerankerFallback:
    @pytest.mark.asyncio
    async def test_falls_back_to_top5_when_cohere_unavailable(self):
        """When Cohere raises, retrieval must return top-5 by raw score — not error out."""
        mock_matches = [
            {"id": f"doc_{i}", "score": float(i) / 10, "metadata": {"page_number": 1, "chunk_index": i, "text": f"chunk {i}", "doc_id": "doc123"}}
            for i in range(20)
        ]
        mock_matches.sort(key=lambda m: m["score"], reverse=True)

        with (
            patch("app.services.retrieval.openai_client") as mock_openai,
            patch("app.services.retrieval.anthropic_client") as mock_anthropic,
            patch("app.services.retrieval.get_pinecone_index") as mock_pc,
            patch("app.services.retrieval.cohere", create=True) as mock_cohere_module,
        ):
            mock_openai.embeddings.create = AsyncMock(
                return_value=MagicMock(data=[MagicMock(embedding=[0.1] * 1536)])
            )
            mock_anthropic.messages.create = AsyncMock(
                return_value=MagicMock(content=[MagicMock(text="rewritten question")])
            )
            mock_pc.return_value.query.return_value = {"matches": mock_matches}

            # Make Cohere raise
            mock_co = AsyncMock()
            mock_co.rerank.side_effect = Exception("Cohere unavailable")
            mock_cohere_module.AsyncClient.return_value = mock_co

            from app.services.retrieval import retrieve_chunks

            results = await retrieve_chunks("What is X?", "doc123", "user456")

        assert len(results) == 5
        scores = [r["score"] for r in results]
        assert scores == sorted(scores, reverse=True), "Results should be sorted by score desc"


class TestPromptTokenGuard:
    def test_context_trimmed_when_over_budget(self):
        """Chunks exceeding 6000-token budget must be dropped (lowest-ranked first)."""
        from app.services.generation import _build_context_block

        # Each chunk is ~5000 chars (~1250 tokens); 3 chunks = ~15000 tokens > 6000 limit
        large_chunks = [
            {"text": "x" * 5000, "page": i + 1, "score": 1.0 - i * 0.1}
            for i in range(3)
        ]
        context = _build_context_block(large_chunks)

        # Should only contain 1 chunk (first one fits, second exceeds budget)
        assert context.count("[CHUNK") == 1


class TestQueryRewrite:
    @pytest.mark.asyncio
    async def test_rewrite_skipped_for_long_questions(self):
        """Questions with > 20 words should not trigger a Claude rewrite call."""
        long_question = " ".join(["word"] * 21)  # 21 words

        with (
            patch("app.services.retrieval.openai_client") as mock_openai,
            patch("app.services.retrieval.anthropic_client") as mock_anthropic,
            patch("app.services.retrieval.get_pinecone_index") as mock_pc,
        ):
            mock_openai.embeddings.create = AsyncMock(
                return_value=MagicMock(data=[MagicMock(embedding=[0.1] * 1536)])
            )
            mock_pc.return_value.query.return_value = {"matches": []}
            mock_anthropic.messages.create = AsyncMock()

            from app.services.retrieval import retrieve_chunks

            await retrieve_chunks(long_question, "doc123", "user456")

        mock_anthropic.messages.create.assert_not_called()
