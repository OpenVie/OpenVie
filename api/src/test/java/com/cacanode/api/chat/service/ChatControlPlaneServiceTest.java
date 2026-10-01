package com.cacanode.api.chat.service;

import com.cacanode.api.chat.query.ChatControlPlaneService;
import org.junit.jupiter.api.Test;

import java.util.Map;

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
}
