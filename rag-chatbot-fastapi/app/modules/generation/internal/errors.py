class ChatError(Exception):
    """Base chat/RAG error."""


class ChatSessionNotFoundError(ChatError):
    """Session does not exist or does not belong to the tenant."""


class ChatWorkspaceNotFoundError(ChatError):
    """Requested chatbot or knowledge base does not belong to the tenant."""


class ChatSessionStoreUnavailableError(ChatError):
    """Chat session persistence is unavailable."""


class ChatModelTimeoutError(ChatError):
    """Model generation exceeded the configured timeout."""


class ChatModelProviderError(ChatError):
    """Model provider rejected or failed the generation request."""


class ChatQuotaExceededError(ChatError):
    """Tenant message quota has been exhausted."""
