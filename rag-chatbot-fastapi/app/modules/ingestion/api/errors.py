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

class IngestionError(Exception):
    """Base ingestion pipeline error."""


class PermanentIngestionFailure(IngestionError):
    """Failure caused by invalid input that should not be retried."""


class TransientIngestionFailure(IngestionError):
    """Failure caused by infrastructure or temporary dependencies."""
