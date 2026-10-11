package com.cacanode.api.chat.controller;

import com.cacanode.api.chat.dto.ChatDtos;
import com.cacanode.api.ai.api.AiInferenceException;
import com.cacanode.api.chat.exception.ChatApiException;
import com.cacanode.api.chat.query.ChatControlPlaneService;
import com.cacanode.api.common.controller.BaseController;
import com.cacanode.api.common.exception.custom.ResourceNotFoundException;
import com.cacanode.api.common.exception.custom.UnauthorizedException;
import io.grpc.Context;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.time.LocalDate;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController extends BaseController {
    private final ChatControlPlaneService chatService;
    private final Map<Thread, Context.CancellableContext> activeStreams = new ConcurrentHashMap<>();
    @Value("${app.ai.grpc.answer-deadline-seconds:100}")
    private long answerDeadlineSeconds = 100;

    @PostMapping("/sessions")
    public ChatDtos.SessionResponse create(
            @Valid @RequestBody ChatDtos.CreateSessionRequest body,
            HttpServletRequest request) {
        return chatService.createEmployeeSession(getTenantId(request), getUserId(request), body);
    }

    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> submit(
            @PathVariable UUID sessionId,
            @Valid @RequestBody ChatDtos.SubmitMessageRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId,
            HttpServletRequest request) {
        UUID tenantId = getTenantId(request);
        UUID userId = getUserId(request);
        SseEmitter emitter = new SseEmitter((2 * answerDeadlineSeconds + 10) * 1000);
        Context.CancellableContext context = Context.current().withCancellation();
        AtomicBoolean cancelled = new AtomicBoolean();
        // Servlet disconnects are observed on writes, even while the provider is silent.
        Thread heartbeat = Thread.ofVirtual().name("chat-heartbeat-" + sessionId).unstarted(() -> {
            try {
                while (!context.isCancelled()) {
                    emit(emitter, context, SseEmitter.event().comment("keepalive"));
                    Thread.sleep(5000);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (CancellationException exception) {
                if (!cancelled.get()) emitter.complete();
            }
        });
        Thread worker = Thread.ofVirtual().name("chat-answer-" + sessionId).unstarted(() ->
                context.run(() -> {
                    try {
                        var result = chatService.submitEmployeeMessage(
                                tenantId, userId, sessionId, body.content(), body.metadata(),
                                idempotencyKey, requestId, new ChatControlPlaneService.AnswerStreamListener() {
                                    @Override
                                    public void onContent(String content) {
                                        send(emitter, context, "content", Map.of("content", content));
                                    }

                                    @Override
                                    public void onReset() {
                                        send(emitter, context, "reset", Map.of());
                                    }
                                });
                        send(emitter, context, "complete", result);
                        emitter.complete();
                    } catch (CancellationException exception) {
                        if (!cancelled.get()) emitter.complete();
                    } catch (RuntimeException exception) {
                        if (!context.isCancelled()) {
                            String code = "MODEL_PROVIDER_ERROR";
                            String message = "The model provider could not complete the request.";
                            if (exception instanceof ChatApiException failure) {
                                code = failure.getCode();
                                message = failure.getMessage();
                            } else if (exception instanceof AiInferenceException failure) {
                                code = failure.getCode();
                                message = failure.getMessage();
                            } else if (exception instanceof UnauthorizedException) {
                                code = "UNAUTHORIZED";
                                message = exception.getMessage();
                            } else if (exception instanceof AccessDeniedException) {
                                code = "FORBIDDEN";
                                message = exception.getMessage();
                            } else if (exception instanceof ResourceNotFoundException) {
                                code = "NOT_FOUND";
                                message = exception.getMessage();
                            }
                            try {
                                send(emitter, context, "error", Map.of("code", code, "message", message));
                                emitter.complete();
                            } catch (CancellationException ignored) {
                                if (!cancelled.get()) emitter.complete();
                            }
                        } else if (!cancelled.get()) {
                            emitter.complete();
                        }
                    } finally {
                        heartbeat.interrupt();
                        activeStreams.remove(Thread.currentThread());
                        context.cancel(null);
                    }
                }));
        Runnable cancel = () -> {
            if (cancelled.compareAndSet(false, true)) {
                context.cancel(new CancellationException("The answer stream was closed."));
                heartbeat.interrupt();
                if (worker != Thread.currentThread()) {
                    worker.interrupt();
                }
            }
        };
        emitter.onTimeout(() -> {
            cancel.run();
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of(
                        "code", "MODEL_TIMEOUT", "message", "The model took too long to answer."),
                        MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException ignored) {
                // A disconnected client cannot receive the timeout event.
            } finally {
                emitter.complete();
            }
        });
        emitter.onError(exception -> cancel.run());
        emitter.onCompletion(cancel);
        activeStreams.put(worker, context);
        heartbeat.start();
        worker.start();
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    private static void send(SseEmitter emitter, Context.CancellableContext context,
                             String event, Object data) {
        emit(emitter, context, SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
    }

    private static void emit(SseEmitter emitter, Context.CancellableContext context,
                             SseEmitter.SseEventBuilder event) {
        if (context.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("The answer stream was cancelled.");
        }
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException exception) {
            context.cancel(exception);
            throw new CancellationException("The answer stream was disconnected.");
        }
    }

    @PreDestroy
    void closeStreams() {
        activeStreams.forEach((worker, context) -> {
            context.cancel(new CancellationException("The application is shutting down."));
            worker.interrupt();
        });
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatDtos.MessageResponse> history(
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int after,
            HttpServletRequest request) {
        return chatService.history(getTenantId(request), getUserId(request), sessionId, limit, after);
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable UUID sessionId, HttpServletRequest request) {
        chatService.close(getTenantId(request), getUserId(request), sessionId);
    }

    public List<ChatDtos.PlaygroundSessionResponse> playground(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return chatService.playground(getTenantId(request), getUserId(request), limit, offset);
    }

    @GetMapping("/playground/sessions")
    public ResponseEntity<List<ChatDtos.PlaygroundSessionResponse>> playgroundResponse(
            @RequestParam(defaultValue = "30") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String cursor,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(value = "activity_from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate activityFrom,
            @RequestParam(value = "activity_to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate activityTo,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            HttpServletRequest request) {
        var result = chatService.playgroundPage(getTenantId(request), getUserId(request), limit,
                offset, cursor, query, status, activityFrom, activityTo, sort, direction);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.nextCursor() != null) response.header("X-Next-Cursor", result.nextCursor());
        return response.body(result.sessions());
    }

    @DeleteMapping("/playground/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void hide(@PathVariable UUID sessionId, HttpServletRequest request) {
        chatService.hidePlayground(getTenantId(request), getUserId(request), sessionId);
    }
}
