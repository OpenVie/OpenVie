from __future__ import annotations

import asyncio
from typing import Protocol

import boto3
from botocore import UNSIGNED
from botocore.config import Config
from botocore.exceptions import BotoCoreError, ClientError

from app.common.config import StorageConfig
from app.common.errors import StorageNotFoundError, StorageUnavailableError


class ObjectStorageReader(Protocol):
    async def download(self, storage_key: str) -> bytes: ...


class ObjectStorageWriter(Protocol):
    async def upload(self, storage_key: str, data: bytes, *, content_type: str) -> None: ...

    async def delete_prefix(self, storage_prefix: str) -> None: ...


class ObjectStorage(ObjectStorageReader, ObjectStorageWriter, Protocol):
    pass


class SeaweedS3DocumentStore:
    def __init__(self, settings: StorageConfig):
        self._bucket = settings.SEAWEEDFS_BUCKET
        has_credentials = bool(settings.SEAWEEDFS_ACCESS_KEY and settings.SEAWEEDFS_SECRET_KEY)
        self._client = boto3.client(
            "s3",
            endpoint_url=settings.SEAWEEDFS_S3_ENDPOINT,
            aws_access_key_id=settings.SEAWEEDFS_ACCESS_KEY or None,
            aws_secret_access_key=settings.SEAWEEDFS_SECRET_KEY or None,
            config=Config(
                signature_version=None if has_credentials else UNSIGNED,
                connect_timeout=settings.SEAWEEDFS_CONNECT_TIMEOUT_SECONDS,
                read_timeout=settings.SEAWEEDFS_READ_TIMEOUT_SECONDS,
                retries={"mode": "standard", "total_max_attempts": settings.SEAWEEDFS_MAX_ATTEMPTS},
                s3={"addressing_style": "path"},
            ),
        )

    async def download(self, storage_key: str) -> bytes:
        try:
            return await asyncio.to_thread(self._download_sync, storage_key)
        except ClientError as exc:
            if _is_not_found(exc):
                raise StorageNotFoundError(
                    f"Object does not exist in SeaweedFS: {storage_key}"
                ) from exc
            raise StorageUnavailableError(
                f"Unable to download document from SeaweedFS: {exc}"
            ) from exc
        except (BotoCoreError, OSError) as exc:
            raise StorageUnavailableError(
                f"Unable to download document from SeaweedFS: {exc}"
            ) from exc

    async def download_limited(self, storage_key: str, max_bytes: int) -> bytes:
        try:
            return await asyncio.to_thread(self._download_sync, storage_key, max_bytes)
        except ClientError as exc:
            if _is_not_found(exc):
                raise StorageNotFoundError(
                    f"Object does not exist in SeaweedFS: {storage_key}"
                ) from exc
            raise StorageUnavailableError(
                f"Unable to download document from SeaweedFS: {exc}"
            ) from exc
        except (BotoCoreError, OSError) as exc:
            raise StorageUnavailableError(
                f"Unable to download document from SeaweedFS: {exc}"
            ) from exc

    async def upload(self, storage_key: str, data: bytes, *, content_type: str) -> None:
        try:
            await asyncio.to_thread(self._upload_sync, storage_key, data, content_type)
        except (BotoCoreError, ClientError, OSError) as exc:
            raise StorageUnavailableError(
                f"Unable to upload derived artifact to SeaweedFS: {exc}"
            ) from exc

    async def delete_prefix(self, storage_prefix: str) -> None:
        if not storage_prefix or storage_prefix == "/":
            raise ValueError("Storage deletion prefix must be specific")
        try:
            await asyncio.to_thread(self._delete_prefix_sync, storage_prefix)
        except (BotoCoreError, ClientError, OSError) as exc:
            raise StorageUnavailableError(
                f"Unable to delete derived artifacts from SeaweedFS: {exc}"
            ) from exc

    def _download_sync(self, storage_key: str, max_bytes: int | None = None) -> bytes:
        response = self._client.get_object(Bucket=self._bucket, Key=storage_key)
        body = response["Body"]
        try:
            data = body.read() if max_bytes is None else body.read(max_bytes + 1)
            if max_bytes is not None and len(data) > max_bytes:
                raise ValueError("Stored object exceeds the bounded download limit")
            return data
        finally:
            close = getattr(body, "close", None)
            if close:
                close()

    def _upload_sync(self, storage_key: str, data: bytes, content_type: str) -> None:
        self._client.put_object(
            Bucket=self._bucket,
            Key=storage_key,
            Body=data,
            ContentType=content_type,
        )

    def _delete_prefix_sync(self, storage_prefix: str) -> None:
        continuation_token: str | None = None
        while True:
            request: dict[str, object] = {
                "Bucket": self._bucket,
                "Prefix": storage_prefix,
                "MaxKeys": 1000,
            }
            if continuation_token:
                request["ContinuationToken"] = continuation_token
            page = self._client.list_objects_v2(**request)
            objects = [
                {"Key": item["Key"]}
                for item in page.get("Contents", [])
                if isinstance(item, dict) and item.get("Key")
            ]
            if objects:
                response = self._client.delete_objects(
                    Bucket=self._bucket,
                    Delete={"Objects": objects, "Quiet": True},
                )
                errors = response.get("Errors", [])
                if errors:
                    raise OSError("SeaweedFS reported one or more object deletion failures")
            if not page.get("IsTruncated"):
                return
            continuation_token = page.get("NextContinuationToken")
            if not isinstance(continuation_token, str) or not continuation_token:
                raise OSError("SeaweedFS returned a truncated listing without a continuation token")


def _is_not_found(exc: ClientError) -> bool:
    error = exc.response.get("Error", {})
    metadata = exc.response.get("ResponseMetadata", {})
    return str(error.get("Code", "")) in {"404", "NoSuchKey", "NotFound"} or metadata.get(
        "HTTPStatusCode"
    ) == 404
