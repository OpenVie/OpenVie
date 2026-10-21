package com.openvie.api.chat.service;

import com.openvie.api.chat.query.ChatControlPlaneService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ChatControlPlaneServiceTest {

    @Test
    void localeParticipatesInIdempotencyFingerprintInput() {
        Map<String, Object> en = ChatControlPlaneService.fingerprintPayload(
                "Hello", Map.of("surface", "playground"), "en-US");
        Map<String, Object> vi = ChatControlPlaneService.fingerprintPayload(
                "Hello", Map.of("surface", "playground"), "vi-VN");
        Map<String, Object> noLocale = ChatControlPlaneService.fingerprintPayload(
                "Hello", Map.of("surface", "playground"), null);

        assertEquals("en-US", en.get("locale"));
        assertEquals("vi-VN", vi.get("locale"));
        assertNotEquals(en, vi);
        assertFalse(noLocale.containsKey("locale"));
    }

    @Test
    void documentScopeParticipatesInIdempotencyFingerprintInputOnlyWhenPresent() {
        UUID scopedDocumentId = UUID.randomUUID();
        Map<String, Object> unscoped = ChatControlPlaneService.fingerprintPayload(
                "Hello", Map.of(), null, List.of());
        Map<String, Object> scoped = ChatControlPlaneService.fingerprintPayload(
                "Hello", Map.of(), null, List.of(scopedDocumentId));
        Map<String, Object> otherScope = ChatControlPlaneService.fingerprintPayload(
                "Hello", Map.of(), null, List.of(UUID.randomUUID()));

        assertFalse(unscoped.containsKey("document_ids"));
        assertEquals(List.of(scopedDocumentId.toString()), scoped.get("document_ids"));
        assertNotEquals(scoped, otherScope);
        assertNotEquals(scoped, unscoped);
    }
}
