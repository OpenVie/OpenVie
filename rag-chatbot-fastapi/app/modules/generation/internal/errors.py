# Copyright 2026 LinkedNodeDigital
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

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
