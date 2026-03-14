import asyncio
from functools import partial
from urllib.parse import urlparse

import boto3
import structlog

from app.config import settings

logger = structlog.get_logger()


def _get_s3_client():
    kwargs = dict(
        region_name=settings.aws_region,
        aws_access_key_id=settings.aws_access_key_id,
        aws_secret_access_key=settings.aws_secret_access_key,
    )
    if settings.s3_endpoint_url:
        kwargs["endpoint_url"] = settings.s3_endpoint_url
    return boto3.client("s3", **kwargs)


async def upload_file_to_s3(
    file_bytes: bytes, filename: str, doc_id: str, content_type: str
) -> str:
    key = f"documents/{doc_id}/{filename}"
    loop = asyncio.get_event_loop()

    def _upload():
        client = _get_s3_client()
        client.put_object(
            Bucket=settings.s3_bucket,
            Key=key,
            Body=file_bytes,
            ContentType=content_type,
        )

    await loop.run_in_executor(None, _upload)
    logger.info("s3_upload_complete", key=key)

    if settings.s3_endpoint_url:
        return f"{settings.s3_endpoint_url}/{settings.s3_bucket}/{key}"
    return f"https://{settings.s3_bucket}.s3.{settings.aws_region}.amazonaws.com/{key}"


async def download_file_from_s3(s3_url: str) -> bytes:
    parsed = urlparse(s3_url)

    if settings.s3_endpoint_url:
        # MinIO: http://host/bucket/key
        parts = parsed.path.lstrip("/").split("/", 1)
        key = parts[1] if len(parts) > 1 else parts[0]
    else:
        # AWS: https://bucket.s3.region.amazonaws.com/key
        key = parsed.path.lstrip("/")

    loop = asyncio.get_event_loop()

    def _download():
        client = _get_s3_client()
        response = client.get_object(Bucket=settings.s3_bucket, Key=key)
        return response["Body"].read()

    return await loop.run_in_executor(None, _download)


async def delete_file_from_s3(s3_url: str) -> None:
    parsed = urlparse(s3_url)

    if settings.s3_endpoint_url:
        parts = parsed.path.lstrip("/").split("/", 1)
        key = parts[1] if len(parts) > 1 else parts[0]
    else:
        key = parsed.path.lstrip("/")

    loop = asyncio.get_event_loop()

    def _delete():
        client = _get_s3_client()
        client.delete_object(Bucket=settings.s3_bucket, Key=key)

    await loop.run_in_executor(None, _delete)
    logger.info("s3_delete_complete", key=key)
