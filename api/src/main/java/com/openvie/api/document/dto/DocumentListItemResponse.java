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

package com.openvie.api.document.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.openvie.api.document.enums.DocumentStatus;
import com.openvie.api.document.enums.DocumentType;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One row of a knowledge base's document list. `jobId` links the document to its "
        + "ingestion job; `chunkCount` is populated once indexing has finished.")
public record DocumentListItemResponse(
        UUID id,
        UUID jobId,
        String fileName,
        DocumentType fileType,
        long fileSizeBytes,
        UUID knowledgeBaseId,
        @Schema(description = "Ingestion state: PENDING, PROCESSING, COMPLETED, or FAILED.") DocumentStatus status,
        Integer chunkCount,
        @Schema(description = "How the text was obtained: text (native layer), ocr (recognized page "
                + "images), or mixed. Absent for legacy documents.") String textSource,
        @Schema(description = "Number of source pages when known (PDF and images).") Integer pageCount,
        @Schema(description = "Set when ingestion FAILED; explains the worker-side reason.") String errorMessage,
        LocalDateTime uploadedAt,
        UUID uploadedBy
) {
}
