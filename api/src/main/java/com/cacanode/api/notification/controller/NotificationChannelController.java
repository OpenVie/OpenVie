package com.cacanode.api.notification.controller;

import com.cacanode.api.common.controller.BaseController;
import com.cacanode.api.notification.service.NotificationChannelService;
import com.cacanode.api.notification.service.NotificationChannelService.ChannelRequest;
import com.cacanode.api.notification.service.NotificationChannelService.ChannelView;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Organization-owner notification channel administration. Credential values
 * are write-only: reads return presence, never the stored secret.
 */
@Tag(name = "Notification channels", description = "Organization mail plugins (owner only)")
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ORG_OWNER')")
public class NotificationChannelController extends BaseController {

    private final NotificationChannelService channelService;

    public record TestRequest(@NotBlank @Email String email) {
    }

    @GetMapping
    public List<ChannelView> list(HttpServletRequest request) {
        return channelService.list(getOrgId(request));
    }

    @PutMapping("/{type}")
    public ChannelView upsert(
            @PathVariable String type,
            @Valid @RequestBody ChannelRequest body,
            HttpServletRequest request) {
        return channelService.upsert(getOrgId(request), new ChannelRequest(
                type, body.enabled(), body.fromEmail(), body.fromName(), body.host(),
                body.port(), body.username(), body.password(), body.auth(),
                body.starttls(), body.apiKey()));
    }

    @PostMapping("/{channelId}/enabled")
    public ChannelView setEnabled(
            @PathVariable UUID channelId,
            @RequestBody Map<String, Boolean> body,
            HttpServletRequest request) {
        return channelService.setEnabled(getOrgId(request), channelId,
                Boolean.TRUE.equals(body.get("enabled")));
    }

    @DeleteMapping("/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID channelId, HttpServletRequest request) {
        channelService.delete(getOrgId(request), channelId);
    }

    @PostMapping("/test")
    public ResponseEntity<Void> test(
            @Valid @RequestBody TestRequest body,
            HttpServletRequest request) {
        channelService.testDelivery(getOrgId(request), body.email());
        return ResponseEntity.noContent().build();
    }
}
