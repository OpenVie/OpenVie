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

import com.openvie.api.common.exception.custom.InternalServerErrorException;
import com.openvie.api.common.exception.custom.MalwareDetectedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Edge quarantine scanner enforcing DX-OS zero-trust principle (iv).
 *
 * <p>Scans uploaded document byte streams against standard malware signatures
 * (including ClamAV daemon via INSTREAM protocol and built-in EICAR test signatures).
 */
@Service
@Slf4j(topic = "ANTIVIRUS-SCANNER")
public class AntivirusScanner {

    private static final byte[] EICAR_PREFIX = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
            .getBytes(StandardCharsets.US_ASCII);

    private final boolean enabled;
    private final String host;
    private final int port;
    private final int timeoutMs;
    private final boolean failOpen;

    public AntivirusScanner(
            @Value("${app.antivirus.enabled:false}") boolean enabled,
            @Value("${app.antivirus.host:localhost}") String host,
            @Value("${app.antivirus.port:3310}") int port,
            @Value("${app.antivirus.timeout-ms:5000}") int timeoutMs,
            @Value("${app.antivirus.fail-open:true}") boolean failOpen) {
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
        this.failOpen = failOpen;
    }

    public void scan(byte[] fileBytes, String fileName) {
        if (fileBytes == null || fileBytes.length == 0) {
            return;
        }

        // Fast memory assertion: Check standard EICAR test signature
        if (containsEicarSignature(fileBytes)) {
            log.warn("EICAR test malware signature detected in file: {}", fileName);
            throw new MalwareDetectedException("Eicar-Test-Signature");
        }

        if (!enabled) {
            log.debug("Antivirus scanner bypassed for {}: app.antivirus.enabled=false", fileName);
            return;
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);

            try (OutputStream rawOut = socket.getOutputStream();
                 DataOutputStream out = new DataOutputStream(rawOut);
                 InputStream in = socket.getInputStream()) {

                out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                out.flush();

                int chunkSize = 2048;
                int offset = 0;
                while (offset < fileBytes.length) {
                    int length = Math.min(chunkSize, fileBytes.length - offset);
                    out.writeInt(length);
                    out.write(fileBytes, offset, length);
                    offset += length;
                }
                out.writeInt(0);
                out.flush();

                byte[] buffer = new byte[1024];
                int read = in.read(buffer);
                String response = (read > 0) ? new String(buffer, 0, read, StandardCharsets.US_ASCII).trim() : "";

                if (response.endsWith("OK")) {
                    log.info("ClamAV quarantine check PASSED for file: {}", fileName);
                } else if (response.contains("FOUND")) {
                    String threatName = extractThreatName(response);
                    log.warn("Malware quarantine threat detected: {} in file: {}", threatName, fileName);
                    throw new MalwareDetectedException(threatName);
                } else {
                    log.warn("ClamAV unexpected response: '{}' for file: {}", response, fileName);
                }
            }
        } catch (MalwareDetectedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Antivirus scanner daemon connection failed ({}:{}): {}", host, port, e.getMessage());
            if (!failOpen) {
                throw new InternalServerErrorException(
                        "Antivirus quarantine scanner is offline and fail-open policy is disabled");
            }
        }
    }

    private boolean containsEicarSignature(byte[] bytes) {
        if (bytes.length < EICAR_PREFIX.length) {
            return false;
        }
        for (int i = 0; i <= bytes.length - EICAR_PREFIX.length; i++) {
            boolean match = true;
            for (int j = 0; j < EICAR_PREFIX.length; j++) {
                if (bytes[i + j] != EICAR_PREFIX[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private String extractThreatName(String response) {
        try {
            int streamIdx = response.indexOf("stream:");
            int foundIdx = response.indexOf("FOUND");
            if (streamIdx != -1 && foundIdx != -1 && foundIdx > streamIdx + 7) {
                return response.substring(streamIdx + 7, foundIdx).trim();
            }
        } catch (Exception ignored) {
        }
        return "Generic.Malware";
    }
}
