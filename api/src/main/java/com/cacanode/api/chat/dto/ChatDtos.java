package com.cacanode.api.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    public record CreateSessionRequest(
            @JsonProperty("chatbot_id") UUID chatbotId,
            @JsonProperty("knowledge_base_id") UUID knowledgeBaseId,
            @Size(max = 20) String locale,
            Map<String, Object> metadata
    ) {
    }


    public record SubmitMessageRequest(
            @NotBlank @Size(max = 32000) String content,
            Map<String, Object> metadata
    ) {
    }

    public record SessionResponse(
            String id,
            @JsonProperty("chatbot_id") String chatbotId,
            @JsonProperty("knowledge_base_id") String knowledgeBaseId,
            @JsonProperty("tenant_id") String tenantId,
            String locale
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CitationResponse(
            String id,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("source_name") String sourceName,
            @JsonProperty("page_number") Integer pageNumber,
            @JsonProperty("chunk_index") int chunkIndex,
            double score,
            String snippet,
            @JsonProperty("unit_id") String unitId,
            String modality,
            @JsonProperty("section_path") List<String> sectionPath,
            @JsonProperty("block_type") String blockType,
            @JsonProperty("sheet_name") String sheetName,
            @JsonProperty("cell_range") String cellRange,
            @JsonProperty("table_id") String tableId,
            @JsonProperty("public_url") String publicUrl
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssistantMessageResponse(
            String role,
            String content,
            List<CitationResponse> citations,
            Map<String, Object> action
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageResponse(
            String role,
            String content,
            List<CitationResponse> citations,
            @JsonProperty("sequence_number") Integer sequenceNumber,
            Map<String, Object> action
    ) {
    }

    public record PlaygroundSessionResponse(
            UUID id,
            String title,
            @JsonProperty("message_count") long messageCount,
            String status,
            @JsonProperty("created_at") LocalDateTime createdAt,
            @JsonProperty("last_activity_at") LocalDateTime lastActivityAt
    ) {
    }



    @JsonInclude(JsonInclude.Include.NON_NULL)
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
