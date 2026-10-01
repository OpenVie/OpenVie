package com.cacanode.api.chat.controller;

import com.cacanode.api.chat.dto.ChatDtos;
import com.cacanode.api.chat.query.ChatControlPlaneService;
import com.cacanode.api.common.controller.BaseController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController extends BaseController {
    private final ChatControlPlaneService chatService;

    @PostMapping("/sessions")
    public ChatDtos.SessionResponse create(
            @Valid @RequestBody ChatDtos.CreateSessionRequest body,
            HttpServletRequest request) {
        return chatService.createEmployeeSession(getTenantId(request), getUserId(request), body);
    }

    @PostMapping("/sessions/{sessionId}/messages")
    public ChatDtos.AssistantMessageResponse submit(
            @PathVariable UUID sessionId,
            @Valid @RequestBody ChatDtos.SubmitMessageRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Request-ID", required = false) String requestId,
            HttpServletRequest request) {
        return chatService.submitEmployeeMessage(
                getTenantId(request), getUserId(request), sessionId, body.content(),
                body.metadata(), idempotencyKey, requestId);
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
