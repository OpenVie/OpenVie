class IngestionError(Exception):
    """Base ingestion pipeline error."""


class PermanentIngestionFailure(IngestionError):
    """Failure caused by invalid input that should not be retried."""


class TransientIngestionFailure(IngestionError):
    """Failure caused by infrastructure or temporary dependencies."""
