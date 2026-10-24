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

package com.openvie.api.document.service;

import com.openvie.api.common.exception.custom.MalwareDetectedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class AntivirusScannerTest {

    @Test
    @DisplayName("Should detect and quarantine standard EICAR test signature")
    void shouldDetectEicarTestSignature() {
        AntivirusScanner scanner = new AntivirusScanner(false, "localhost", 3310, 5000, true);
        String eicarPayload = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
        byte[] maliciousBytes = eicarPayload.getBytes(StandardCharsets.US_ASCII);

        MalwareDetectedException ex = assertThrows(MalwareDetectedException.class, () ->
                scanner.scan(maliciousBytes, "test-virus.txt")
        );

        assertEquals("Eicar-Test-Signature", ex.getThreatName());
    }

    @Test
    @DisplayName("Should permit clean bytes when scanner is bypassed in dev mode")
    void shouldPermitCleanBytesWhenDisabled() {
        AntivirusScanner scanner = new AntivirusScanner(false, "localhost", 3310, 5000, true);
        byte[] cleanBytes = "This is a clean document with normal text.".getBytes(StandardCharsets.UTF_8);

        assertDoesNotThrow(() -> scanner.scan(cleanBytes, "clean-doc.txt"));
    }

    @Test
    @DisplayName("Should fail open gracefully when ClamAV daemon is unreachable in dev")
    void shouldFailOpenWhenDaemonUnreachable() {
        AntivirusScanner scanner = new AntivirusScanner(true, "127.0.0.1", 65432, 200, true);
        byte[] cleanBytes = "Valid non-malicious content.".getBytes(StandardCharsets.UTF_8);

        assertDoesNotThrow(() -> scanner.scan(cleanBytes, "clean-doc.txt"));
    }
}
