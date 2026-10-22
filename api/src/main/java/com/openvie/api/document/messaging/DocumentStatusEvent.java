// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.openvie.api.document.messaging;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DocumentStatusEvent(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("event_id") UUID eventId,
        @JsonProperty("job_id") UUID jobId,
        @JsonProperty("tenant_id") UUID tenantId,
        @JsonProperty("document_id") UUID documentId,
        @JsonProperty("status") String status,
        @JsonProperty("chunk_count") Integer chunkCount,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("text_source") String textSource,
        @JsonProperty("page_count") Integer pageCount
) {
}
