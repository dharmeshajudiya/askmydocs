import uuid

import structlog
from fastapi import APIRouter, Depends, File, HTTPException, UploadFile, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.database import get_session
from app.models.document import Document
from app.models.user import User
from app.schemas.document import DocumentResponse, DocumentStatusResponse
from app.tasks.ingest import ingest_document
from app.utils.s3 import upload_file_to_s3, delete_file_from_s3
from app.utils.pinecone import delete_document_vectors

logger = structlog.get_logger()

router = APIRouter()

ALLOWED_CONTENT_TYPES = {
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "text/plain",
}


@router.post("", response_model=DocumentResponse, status_code=status.HTTP_202_ACCEPTED)
async def upload_document(
    file: UploadFile = File(...),
    session: AsyncSession = Depends(get_session),
    current_user: User = Depends(get_current_user),
):
    if file.content_type not in ALLOWED_CONTENT_TYPES:
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            detail="Only PDF, DOCX, and TXT files are supported",
        )

    doc_id = uuid.uuid4()
    log = logger.bind(doc_id=str(doc_id), user_id=str(current_user.id))

    file_bytes = await file.read()
    s3_url = await upload_file_to_s3(
        file_bytes=file_bytes,
        filename=file.filename,
        doc_id=str(doc_id),
        content_type=file.content_type,
    )
    log.info("document_uploaded_to_s3", s3_url=s3_url)

    document = Document(
        id=doc_id,
        user_id=current_user.id,
        filename=file.filename,
        s3_url=s3_url,
        status="queued",
    )
    session.add(document)
    await session.commit()
    await session.refresh(document)

    ingest_document.delay(str(doc_id))
    log.info("ingestion_task_queued")

    return DocumentResponse.model_validate(document)


@router.get("", response_model=list[DocumentResponse])
async def list_documents(
    session: AsyncSession = Depends(get_session),
    current_user: User = Depends(get_current_user),
):
    result = await session.execute(
        select(Document)
        .where(Document.user_id == current_user.id)
        .order_by(Document.created_at.desc())
    )
    return [DocumentResponse.model_validate(doc) for doc in result.scalars().all()]


@router.get("/{doc_id}/status", response_model=DocumentStatusResponse)
async def get_document_status(
    doc_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
    current_user: User = Depends(get_current_user),
):
    document = await _get_owned_document(doc_id, current_user.id, session)
    return DocumentStatusResponse.model_validate(document)


@router.delete("/{doc_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_document(
    doc_id: uuid.UUID,
    session: AsyncSession = Depends(get_session),
    current_user: User = Depends(get_current_user),
):
    document = await _get_owned_document(doc_id, current_user.id, session)
    log = logger.bind(doc_id=str(doc_id), user_id=str(current_user.id))

    await delete_document_vectors(str(doc_id), str(current_user.id))
    log.info("pinecone_vectors_deleted")

    await delete_file_from_s3(document.s3_url)
    log.info("s3_file_deleted")

    await session.delete(document)
    await session.commit()
    log.info("document_deleted")


async def _get_owned_document(
    doc_id: uuid.UUID, user_id: uuid.UUID, session: AsyncSession
) -> Document:
    result = await session.execute(
        select(Document).where(Document.id == doc_id, Document.user_id == user_id)
    )
    document = result.scalar_one_or_none()
    if not document:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Document not found")
    return document
