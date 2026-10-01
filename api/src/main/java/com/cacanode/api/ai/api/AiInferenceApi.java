package com.cacanode.api.ai.api;

import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public interface AiInferenceApi {
    GeneratedAnswer generate(GenerationRequest request);

    List<DocumentUnit> listDocumentUnits(
            UUID tenantId, UUID knowledgeBaseId, UUID documentId, String requestId);

    void deleteDocumentIndex(UUID tenantId, UUID knowledgeBaseId, UUID documentId, String requestId);

    record GenerationRequest(
            UUID generationId,
            UUID turnId,
            UUID tenantId,
            UUID chatbotId,
            UUID knowledgeBaseId,
            long authoritativeRevision,
            String channel,
            String locale,
            String question,
            List<PriorMessage> priorMessages,
            String tenantName,
            String promptSchemaVersion,
            String requestId,
            String traceId
    ) {
    }

    record PriorMessage(String role, String content) {
    }

    record GeneratedAnswer(
            UUID generationId,
            long authoritativeRevision,
            String answer,
            List<Citation> citations,
            Long inputTokens,
            Long outputTokens,
            String cacheTier,
            Long avoidedInputTokens,
            Long avoidedOutputTokens
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Citation(
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
            @JsonProperty("table_id") String tableId
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record DocumentUnit(
            @JsonProperty("unit_id") String unitId,
            @JsonProperty("chunk_index") int chunkIndex,
            String text,
            String sourceName,
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
