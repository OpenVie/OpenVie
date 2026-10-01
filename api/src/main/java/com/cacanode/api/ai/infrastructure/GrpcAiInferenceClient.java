package com.cacanode.api.ai.infrastructure;

import com.cacanode.api.ai.api.AiInferenceApi;
import com.cacanode.api.ai.api.AiInferenceException;

import com.cacanode.ai.v1.DeleteDocumentIndexRequest;
import com.cacanode.ai.v1.GenerateAnswerRequest;
import com.cacanode.ai.v1.GenerateAnswerResponse;
import com.cacanode.ai.v1.InferenceServiceGrpc;
import com.cacanode.ai.v1.ListDocumentUnitsRequest;
import com.cacanode.ai.v1.TraceMetadata;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
public class GrpcAiInferenceClient implements AiInferenceApi {
    private final ManagedChannel channel;
    private final InferenceServiceGrpc.InferenceServiceBlockingStub stub;
    private final long answerDeadlineSeconds;
    private final long unitDeadlineSeconds;
    private final long deletionDeadlineSeconds;

    public GrpcAiInferenceClient(
            @Value("${app.ai.grpc.target:localhost:50051}") String target,
            @Value("${app.ai.grpc.plaintext:true}") boolean plaintext,
            @Value("${app.ai.grpc.ca-certificate:}") String caCertificate,
            @Value("${app.ai.grpc.client-certificate:}") String clientCertificate,
            @Value("${app.ai.grpc.client-key:}") String clientKey,
            @Value("${app.ai.grpc.authority-override:}") String authorityOverride,
            @Value("${app.ai.grpc.answer-deadline-seconds:100}") long answerDeadlineSeconds,
            @Value("${app.ai.grpc.document-units-deadline-seconds:10}") long unitDeadlineSeconds,
            @Value("${app.ai.grpc.deletion-deadline-seconds:15}") long deletionDeadlineSeconds
    ) {
        NettyChannelBuilder builder = NettyChannelBuilder.forTarget(target);
        if (plaintext) {
            builder.usePlaintext();
        } else {
            if (caCertificate.isBlank() || clientCertificate.isBlank() || clientKey.isBlank()) {
                throw new IllegalStateException("Production AI gRPC mTLS material is incomplete");
            }
            try {
                builder.sslContext(GrpcSslContexts.forClient()
                        .trustManager(new File(caCertificate))
                        .keyManager(new File(clientCertificate), new File(clientKey))
                        .build());
            } catch (SSLException exception) {
                throw new IllegalStateException("Unable to configure AI gRPC mTLS", exception);
            }
        }
        if (!authorityOverride.isBlank()) {
            builder.overrideAuthority(authorityOverride);
        }
        this.channel = builder.maxInboundMessageSize(16 * 1024 * 1024).build();
        this.stub = InferenceServiceGrpc.newBlockingStub(channel);
        this.answerDeadlineSeconds = answerDeadlineSeconds;
        this.unitDeadlineSeconds = unitDeadlineSeconds;
        this.deletionDeadlineSeconds = deletionDeadlineSeconds;
    }

    @Override
    public GeneratedAnswer generate(GenerationRequest request) {
        GenerateAnswerRequest.Builder builder = GenerateAnswerRequest.newBuilder()
                .setGenerationId(request.generationId().toString())
                .setTurnId(request.turnId().toString())
                .setTenantId(request.tenantId().toString())
                .setChatbotId(request.chatbotId().toString())
                .setKnowledgeBaseId(request.knowledgeBaseId().toString())
                .setAuthoritativeRevision(request.authoritativeRevision())
                .setChannel(request.channel())
                .setLocale(request.locale())
                .setQuestion(request.question())
                .setTenantName(request.tenantName())
                .setPromptSchemaVersion(request.promptSchemaVersion())
                .setTrace(trace(request.requestId(), request.traceId()));
        request.priorMessages().stream().limit(20).forEach(message -> builder.addPriorMessages(
                com.cacanode.ai.v1.PriorMessage.newBuilder()
                        .setRole(message.role()).setContent(message.content())));

        GenerateAnswerResponse response = unavailableRetry(
                answerDeadlineSeconds,
                service -> service.generateAnswer(builder.build()),
                "answer generation");
        if (!response.getGenerationId().equals(request.generationId().toString())
                || response.getAuthoritativeRevision() != request.authoritativeRevision()) {
            throw new AiInferenceException(HttpStatus.BAD_GATEWAY, "INVALID_AI_RESPONSE",
                    "The inference service returned mismatched generation context.");
        }
        return new GeneratedAnswer(
                UUID.fromString(response.getGenerationId()),
                response.getAuthoritativeRevision(),
                response.getAnswer(),
                response.getCitationsList().stream().map(this::citation).toList(),
                response.hasInputTokens() ? response.getInputTokens() : null,
                response.hasOutputTokens() ? response.getOutputTokens() : null,
                response.getCacheTier(),
                response.hasAvoidedInputTokens() ? response.getAvoidedInputTokens() : null,
                response.hasAvoidedOutputTokens() ? response.getAvoidedOutputTokens() : null);
    }

    @Override
    public List<AiInferenceApi.DocumentUnit> listDocumentUnits(
            UUID tenantId, UUID knowledgeBaseId, UUID documentId, String requestId) {
        var request = ListDocumentUnitsRequest.newBuilder()
                .setTenantId(tenantId.toString())
                .setKnowledgeBaseId(knowledgeBaseId.toString())
                .setDocumentId(documentId.toString())
                .setTrace(trace(requestId, requestId))
                .build();
        return unavailableRetry(unitDeadlineSeconds, service -> service.listDocumentUnits(request),
                "document-unit read").getUnitsList().stream().map(this::documentUnit).toList();
    }

    @Override
    public void deleteDocumentIndex(
            UUID tenantId, UUID knowledgeBaseId, UUID documentId, String requestId) {
        var request = DeleteDocumentIndexRequest.newBuilder()
                .setTenantId(tenantId.toString())
                .setKnowledgeBaseId(knowledgeBaseId.toString())
                .setDocumentId(documentId.toString())
                .setTrace(trace(requestId, requestId))
                .build();
        unavailableRetry(deletionDeadlineSeconds, service -> service.deleteDocumentIndex(request),
                "document-index deletion");
    }

    private <T> T unavailableRetry(
            long deadlineSeconds,
            Function<InferenceServiceGrpc.InferenceServiceBlockingStub, T> operation,
            String operationName) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return operation.apply(stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS));
            } catch (StatusRuntimeException exception) {
                if (exception.getStatus().getCode() == Status.Code.UNAVAILABLE && attempt == 0) {
                    continue;
                }
                if (exception.getStatus().getCode() == Status.Code.DEADLINE_EXCEEDED) {
                    throw new AiInferenceException(HttpStatus.GATEWAY_TIMEOUT, "MODEL_TIMEOUT",
                            "The model took too long to answer.");
                }
                if (exception.getStatus().getCode() == Status.Code.NOT_FOUND) {
                    throw new AiInferenceException(HttpStatus.NOT_FOUND, "INDEXED_DOCUMENT_NOT_FOUND",
                            "Indexed document was not found.");
                }
                throw new AiInferenceException(HttpStatus.BAD_GATEWAY, "MODEL_PROVIDER_ERROR",
                        "The inference service could not complete " + operationName + ".");
            }
        }
        throw new IllegalStateException("Unreachable retry state");
    }

    private TraceMetadata trace(String requestId, String traceId) {
        return TraceMetadata.newBuilder()
                .setRequestId(requestId == null ? "" : requestId)
                .setTraceId(traceId == null ? "" : traceId)
                .build();
    }

    private AiInferenceApi.Citation citation(com.cacanode.ai.v1.Citation citation) {
        return new AiInferenceApi.Citation(
                citation.getId(), citation.getDocumentId(), citation.getSourceName(),
                citation.hasPageNumber() ? citation.getPageNumber() : null,
                citation.getChunkIndex(), citation.getScore(), citation.getSnippet(),
                citation.hasUnitId() ? citation.getUnitId() : null,
                citation.hasModality() ? citation.getModality() : null,
                citation.getSectionPathList(),
                citation.hasBlockType() ? citation.getBlockType() : null,
                citation.hasSheetName() ? citation.getSheetName() : null,
                citation.hasCellRange() ? citation.getCellRange() : null,
                citation.hasTableId() ? citation.getTableId() : null);
    }

    private AiInferenceApi.DocumentUnit documentUnit(com.cacanode.ai.v1.DocumentUnit unit) {
        return new AiInferenceApi.DocumentUnit(
                unit.hasUnitId() ? unit.getUnitId() : null,
                unit.getChunkIndex(), unit.getText(),
                unit.hasSourceName() ? unit.getSourceName() : null,
                unit.hasModality() ? unit.getModality() : null,
                unit.hasBlockType() ? unit.getBlockType() : null,
                unit.getSectionPathList(),
                unit.hasHeadingContext() ? unit.getHeadingContext() : null,
                unit.hasPageNumber() ? unit.getPageNumber() : null,
                unit.hasSheetName() ? unit.getSheetName() : null,
                unit.hasCellRange() ? unit.getCellRange() : null,
                unit.hasTableId() ? unit.getTableId() : null,
                unit.hasSourceStart() ? unit.getSourceStart() : null,
                unit.hasSourceEnd() ? unit.getSourceEnd() : null);
    }

    @PreDestroy
    void close() throws InterruptedException {
        channel.shutdown();
        if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
            channel.shutdownNow();
        }
    }
}
