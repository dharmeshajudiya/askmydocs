import asyncio

import structlog
from pinecone import Pinecone

from app.config import settings

logger = structlog.get_logger()

_pinecone_client: Pinecone | None = None
_index = None


def get_pinecone_index():
    global _pinecone_client, _index
    if _index is None:
        _pinecone_client = Pinecone(api_key=settings.pinecone_api_key)
        _index = _pinecone_client.Index(host=settings.pinecone_environment)
    return _index


async def delete_document_vectors(doc_id: str, user_id: str) -> None:
    namespace = f"user_{user_id}"
    loop = asyncio.get_event_loop()

    def _delete():
        index = get_pinecone_index()
        # List and delete all vectors with the doc_id prefix
        for ids in index.list(prefix=f"{doc_id}_", namespace=namespace):
            index.delete(ids=ids, namespace=namespace)

    await loop.run_in_executor(None, _delete)
    logger.info("pinecone_vectors_deleted", doc_id=doc_id, namespace=namespace)
