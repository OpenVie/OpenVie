package com.cacanode.api.ai.infrastructure;

import com.cacanode.ai.v1.GenerateAnswerEvent;
import com.cacanode.ai.v1.GenerateAnswerRequest;
import com.cacanode.ai.v1.GenerateAnswerResponse;
import com.cacanode.ai.v1.InferenceServiceGrpc;
import com.cacanode.api.ai.api.AiInferenceApi;
import com.cacanode.api.ai.api.AiInferenceException;
import io.grpc.Context;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

class GrpcAiInferenceClientTest {
    private Server server;
    private GrpcAiInferenceClient client;
    private BiConsumer<GenerateAnswerRequest, StreamObserver<GenerateAnswerEvent>> provider;
    private final AiInferenceApi.GenerationRequest request = new AiInferenceApi.GenerationRequest(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            7L, "EMPLOYEE_PLAYGROUND", "en-US", "question", List.of(), "Tenant", "schema", "request", "trace");

    @BeforeEach
    void setUp() throws Exception {
        server = ServerBuilder.forPort(0).addService(new InferenceServiceGrpc.InferenceServiceImplBase() {
            @Override
            public void generateAnswer(GenerateAnswerRequest input, StreamObserver<GenerateAnswerEvent> output) {
                provider.accept(input, output);
            }
        }).build().start();
        client = new GrpcAiInferenceClient("localhost:" + server.getPort(), true, "", "", "", "", 5, 5, 5);
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void previewsReplaceRatherThanConcatenateAndFinalAnswerIsAuthoritative() {
        provider = (input, output) -> {
            output.onNext(content("First draft"));
            output.onNext(content("Rewritten draft"));
            output.onNext(completed("Authoritative final"));
            output.onCompleted();
        };
        var previews = new ArrayList<String>();

        var answer = client.generate(request, previews::add);

        assertEquals(List.of("First draft", "Rewritten draft"), previews);
        assertEquals("Authoritative final", answer.answer());
        assertEquals(request.generationId(), answer.generationId());
        assertEquals(request.authoritativeRevision(), answer.authoritativeRevision());
    }

    @Test
    void previewOnlyStreamIsNotSuccessfulPartialAnswer() {
        provider = (input, output) -> {
            output.onNext(content("Partial"));
            output.onCompleted();
        };
        assertInvalidStream();
    }

    @Test
    void duplicateCompletionIsRejected() {
        provider = (input, output) -> {
            output.onNext(completed("Final"));
            output.onNext(completed("Final again"));
            output.onCompleted();
        };
        assertInvalidStream();
    }

    @Test
    void unknownEmptyEventIsRejected() {
        provider = (input, output) -> {
            output.onNext(GenerateAnswerEvent.getDefaultInstance());
            output.onCompleted();
        };
        assertInvalidStream();
    }

    @Test
    void blankContentIsRejected() {
        provider = (input, output) -> {
            output.onNext(content("  "));
            output.onCompleted();
        };
        assertInvalidStream();
    }

    @Test
    void finalContextMismatchIsRejected() {
        provider = (input, output) -> {
            output.onNext(GenerateAnswerEvent.newBuilder().setCompleted(
                    GenerateAnswerResponse.newBuilder().setGenerationId(UUID.randomUUID().toString())
                            .setAuthoritativeRevision(request.authoritativeRevision()).setAnswer("Final")).build());
            output.onCompleted();
        };
        assertInvalidStream();
    }

    @Test
    void providerFailureAfterPreviewIsNotRetried() {
        var calls = new AtomicInteger();
        provider = (input, output) -> {
            calls.incrementAndGet();
            output.onNext(content("Partial"));
            output.onError(Status.UNAVAILABLE.asRuntimeException());
        };

        var failure = assertThrows(AiInferenceException.class, () -> client.generate(request, ignored -> { }));

        assertEquals("MODEL_PROVIDER_ERROR", failure.getCode());
        assertEquals(1, calls.get());
    }

    @Test
    void cancellingParentContextUnblocksGrpcReadAndCancelsProvider() throws Exception {
        var previewSeen = new CountDownLatch(1);
        var providerCancelled = new CountDownLatch(1);
        provider = (input, output) -> {
            ((ServerCallStreamObserver<GenerateAnswerEvent>) output)
                    .setOnCancelHandler(providerCancelled::countDown);
            output.onNext(content("Blocked preview"));
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var context = Context.current().withCancellation()) {
            var pending = executor.submit(() -> {
                Context previous = context.attach();
                try {
                    return client.generate(request, ignored -> previewSeen.countDown());
                } finally {
                    context.detach(previous);
                }
            });
            assertTrue(previewSeen.await(3, TimeUnit.SECONDS));

            context.cancel(new IllegalStateException("Client disconnected"));

            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> pending.get(3, TimeUnit.SECONDS));
            assertTrue(providerCancelled.await(3, TimeUnit.SECONDS));
        }
    }

    private void assertInvalidStream() {
        var failure = assertThrows(AiInferenceException.class,
                () -> client.generate(request, ignored -> { }));
        assertEquals("INVALID_AI_RESPONSE", failure.getCode());
    }

    private GenerateAnswerEvent content(String value) {
        return GenerateAnswerEvent.newBuilder().setContent(value).build();
    }

    private GenerateAnswerEvent completed(String value) {
        return GenerateAnswerEvent.newBuilder().setCompleted(GenerateAnswerResponse.newBuilder()
                .setGenerationId(request.generationId().toString())
                .setAuthoritativeRevision(request.authoritativeRevision()).setAnswer(value)).build();
    }
}
