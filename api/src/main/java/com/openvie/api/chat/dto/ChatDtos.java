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

package com.openvie.api.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ChatDtos {
    private ChatDtos() {
    }

    @Schema(description = "Request body for creating a playground chat session.")
    public record CreateSessionRequest(
            @Schema(description = "Chatbot profile the session runs with. Optional when the workspace has exactly one chatbot.")
            @JsonProperty("chatbot_id") UUID chatbotId,
            @Schema(description = "Knowledge base whose documents ground the answers.")
            @JsonProperty("knowledge_base_id") UUID knowledgeBaseId,
            @Schema(description = "Answer language hint (BCP-47).", example = "vi")
            @Size(max = 20) String locale,
            @Schema(description = "Free-form client context attached to the session.")
            Map<String, Object> metadata
    ) {
    }


    @Schema(description = "One chat turn. The answer is streamed back as Server-Sent Events on the messages endpoint.")
    public record SubmitMessageRequest(
            @Schema(description = "User question, up to 32 000 characters.", example = "Summarize the onboarding policy.")
            @NotBlank @Size(max = 32000) String content,
            @Schema(description = "Free-form client context for this turn.")
            Map<String, Object> metadata,
            @Schema(description = "Optional retrieval scope: at most 20 document ids, each a COMPLETED document of the "
                    + "session's knowledge base. Empty or absent means the whole knowledge base. A replayed turn "
                    + "(Idempotency-Key) keeps the scope stored with the original request.")
            @Size(max = 20) @JsonProperty("document_ids") List<UUID> documentIds
    ) {
    }

    @Schema(description = "Reference of a created chat session.")
    public record SessionResponse(
            String id,
            @JsonProperty("chatbot_id") String chatbotId,
            @JsonProperty("knowledge_base_id") String knowledgeBaseId,
            @JsonProperty("tenant_id") String tenantId,
            String locale
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "One retrieval citation backing an answer. Multi-modal extras (sheet, table, image fields) "
            + "are null unless the cited unit has that modality.")
    public record CitationResponse(
            String id,
            @JsonProperty("document_id") String documentId,
            @Schema(description = "File name of the cited document.")
            @JsonProperty("source_name") String sourceName,
            @JsonProperty("page_number") Integer pageNumber,
            @Schema(description = "0-based position of the chunk inside the document.")
            @JsonProperty("chunk_index") int chunkIndex,
            @Schema(description = "Retrieval similarity score.")
            double score,
            @Schema(description = "Short text excerpt of the cited passage.")
            String snippet,
            @JsonProperty("unit_id") String unitId,
            @Schema(description = "Unit modality: TEXT, IMAGE, TABLE, or similar.")
            String modality,
            @JsonProperty("section_path") List<String> sectionPath,
            @JsonProperty("block_type") String blockType,
            @JsonProperty("sheet_name") String sheetName,
            @JsonProperty("cell_range") String cellRange,
            @JsonProperty("table_id") String tableId,
            @Schema(description = "Ingestion parser that produced the cited unit (ocr-v1 marks OCR-derived text).")
            @JsonProperty("parser_version") String parserVersion,
            @JsonProperty("public_url") String publicUrl,
            @Schema(description = "The cited image when the unit was derived from a persisted picture; "
                    + "media_id inserts into /api/v1/documents/{id}/media/{media_id}.")
            @JsonProperty("image") CitationImageDto image
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "A citable image backing a chat citation.")
    public record CitationImageDto(
            @Schema(description = "Key suffix under the document's media/ storage namespace.")
            @JsonProperty("media_id") String mediaId,
            @JsonProperty("media_type") String mediaType,
            @JsonProperty("width") Integer width,
            @JsonProperty("height") Integer height
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "The assistant's answer for the current turn: content, citations, and an optional follow-up action.")
    public record AssistantMessageResponse(
            String role,
            String content,
            List<CitationResponse> citations,
            @Schema(description = "Optional structured follow-up action the answer suggests (for example opening a cited document).")
            Map<String, Object> action
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "A stored chat message (user or assistant) with its position in the session history.")
    public record MessageResponse(
            String role,
            String content,
            List<CitationResponse> citations,
            @Schema(description = "1-based position within the session; pass it as `after` when paging history.")
            @JsonProperty("sequence_number") Integer sequenceNumber,
            Map<String, Object> action
    ) {
    }

    @Schema(description = "One row of the playground session overview.")
    public record PlaygroundSessionResponse(
            UUID id,
            String title,
            @JsonProperty("message_count") long messageCount,
            @Schema(description = "OPEN or CLOSED.", example = "OPEN")
            String status,
            @JsonProperty("created_at") LocalDateTime createdAt,
            @JsonProperty("last_activity_at") LocalDateTime lastActivityAt
    ) {
    }



    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "One indexed unit (chunk) of a document, as stored in the AI index.")
    public record DocumentUnitResponse(
            @JsonProperty("unit_id") String unitId,
            @JsonProperty("chunk_index") int chunkIndex,
            String text,
            @JsonProperty("source_name") String sourceName,
            String modality,
            @JsonProperty("block_type") String blockType,
            @JsonProperty("section_path") List<String> sectionPath,
            @JsonProperty("heading_context") String headingContext,
            @JsonProperty("page_number") Integer pageNumber,
            @JsonProperty("sheet_name") String sheetName,
            @JsonProperty("cell_range") String cellRange,
            @JsonProperty("table_id") String tableId,
            @JsonProperty("source_start") Integer sourceStart,
            @JsonProperty("source_end") Integer sourceEnd
    ) {
    }
}
