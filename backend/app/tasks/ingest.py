import asyncio

import structlog

from app.tasks import celery_app

logger = structlog.get_logger()


@celery_app.task(name="tasks.ingest_document", bind=True, max_retries=3)
def ingest_document(self, doc_id: str) -> None:
    """Celery task that wraps the async ingestion service."""
    from app.database import AsyncSessionLocal
    from app.models.document import Document
    from app.services.ingestion import ingest
    from sqlalchemy import select

    async def _run():
        async with AsyncSessionLocal() as session:
            result = await session.execute(select(Document).where(Document.id == doc_id))
            doc = result.scalar_one()
            await ingest(
                doc_id=str(doc.id),
                user_id=str(doc.user_id),
                s3_url=doc.s3_url,
                filename=doc.filename,
                session=session,
            )

    try:
        asyncio.run(_run())
    except Exception as exc:
        logger.error("celery_task_failed", doc_id=doc_id, error=str(exc))
        raise self.retry(exc=exc, countdown=60)
