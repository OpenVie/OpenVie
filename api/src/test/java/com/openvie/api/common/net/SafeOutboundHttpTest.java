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

package com.openvie.api.common.net;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openvie.api.common.exception.custom.BadRequestException;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

class SafeOutboundHttpTest {

    private final SafeOutboundHttp client = new SafeOutboundHttp(false, false);

    @Test
    void rejectsHttpSchemeWhenDisabled() {
        var req = new SafeOutboundHttp.Request(
                "http://api.example.com/v1/webhook",
                "POST",
                Map.of(),
                null,
                List.of("api.example.com")
        );
        BadRequestException ex = assertThrows(BadRequestException.class, () -> client.execute(req));
        assertTrue(ex.getMessage().contains("HTTPS is required"), ex.getMessage());
    }

    @Test
    void rejectsEmptyAllowedHosts() {
        var req = new SafeOutboundHttp.Request(
                "https://api.example.com/v1/webhook",
                "POST",
                Map.of(),
                null,
                List.of()
        );
        BadRequestException ex = assertThrows(BadRequestException.class, () -> client.execute(req));
        assertTrue(ex.getMessage().contains("no allowedHosts configured"), ex.getMessage());
    }

    @Test
    void rejectsHostNotInAllowedHosts() {
        var req = new SafeOutboundHttp.Request(
                "https://malicious.evil.test/v1/webhook",
                "POST",
                Map.of(),
                null,
                List.of("api.example.com")
        );
        BadRequestException ex = assertThrows(BadRequestException.class, () -> client.execute(req));
        assertTrue(ex.getMessage().contains("not in the allowedHosts allow-list"), ex.getMessage());
    }

    @Test
    void blocksPrivateAndLocalAddresses() throws Exception {
        assertTrue(SafeOutboundHttp.isBlockedIp(InetAddress.getByName("127.0.0.1")));
        assertTrue(SafeOutboundHttp.isBlockedIp(InetAddress.getByName("localhost")));
        assertTrue(SafeOutboundHttp.isBlockedIp(InetAddress.getByName("169.254.169.254")));
        assertTrue(SafeOutboundHttp.isBlockedIp(InetAddress.getByName("10.0.0.1")));
        assertTrue(SafeOutboundHttp.isBlockedIp(InetAddress.getByName("192.168.1.1")));
        assertTrue(SafeOutboundHttp.isBlockedIp(InetAddress.getByName("172.16.0.1")));
        assertTrue(SafeOutboundHttp.isBlockedIp(InetAddress.getByName("0.0.0.0")));
        assertTrue(SafeOutboundHttp.isBlockedIp(InetAddress.getByName("::1")));
        assertTrue(SafeOutboundHttp.isBlockedIp(InetAddress.getByName("fe80::1")));
        assertTrue(SafeOutboundHttp.isBlockedIp(InetAddress.getByName("fc00::1")));

        // Public IP should NOT be blocked
        assertFalse(SafeOutboundHttp.isBlockedIp(InetAddress.getByName("8.8.8.8")));
        assertFalse(SafeOutboundHttp.isBlockedIp(InetAddress.getByName("1.1.1.1")));
    }

    @Test
    void rejectsPrivateHostsByDefault() {
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> client.validateHostResolution("localhost"));
        assertTrue(ex.getMessage().contains("restricted/private IP"), ex.getMessage());
    }

    @Test
    void theDevelopmentSwitchAllowsLocalMockHosts() throws Exception {
        var local = new SafeOutboundHttp(false, true);

        assertDoesNotThrow(() -> local.validateHostResolution("localhost"));
        assertDoesNotThrow(() -> local.validateHostResolution("127.0.0.1"));
        // The raw predicate still reports what is blocked: only the resolution
        // fence is relaxed, so production keeps a single source of truth.
        assertTrue(SafeOutboundHttp.isBlockedIp(InetAddress.getByName("127.0.0.1")));
    }

    @Test
    void sanitizesUrlByStrippingQueryAndUserInfo() {
        URI uri = URI.create("https://user:pass@api.example.com:8443/v1/endpoint?secret=12345#anchor");
        String sanitized = SafeOutboundHttp.sanitizeUrl(uri);
        assertEquals("https://api.example.com:8443/v1/endpoint", sanitized);
        assertFalse(sanitized.contains("secret"));
        assertFalse(sanitized.contains("pass"));
    }
}
