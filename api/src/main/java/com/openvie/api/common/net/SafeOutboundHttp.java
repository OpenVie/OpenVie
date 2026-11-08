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

import com.openvie.api.common.exception.custom.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hardened outbound HTTP client for external_call, remote catalog fetch,
 * and external integrations.
 *
 * <p>Enforces:
 * <ul>
 *   <li>HTTPS only by default (HTTP permitted only when {@code app.bizapps.outbound.allow-http=true})</li>
 *   <li>Host must be explicitly present in the provided {@code allowedHosts} allow-list</li>
 *   <li>DNS resolution with SSRF validation: rejects loopback, private RFC 1918, link-local,
 *       cloud metadata (169.254.169.254), IPv6 unique local/link-local, and multicast addresses</li>
 *   <li>Redirects disabled (never follows 3xx)</li>
 *   <li>Timeouts: connect 10s, read 15s</li>
 *   <li>Response body capped at 256 KiB</li>
 *   <li>Sanitized URL logging (no query parameters, no headers)</li>
 * </ul>
 */
@Component
@Slf4j(topic = "OUTBOUND-HTTP")
public class SafeOutboundHttp {

    public static final int MAX_RESPONSE_BYTES = 256 * 1024; // 256 KiB
    public static final int CONNECT_TIMEOUT_MS = 10_000;
    public static final int READ_TIMEOUT_MS = 15_000;

    private final boolean allowHttp;

    public SafeOutboundHttp(@Value("${app.bizapps.outbound.allow-http:false}") boolean allowHttp) {
        this.allowHttp = allowHttp;
    }

    public record Request(
            String url,
            String method,
            Map<String, String> headers,
            String body,
            List<String> allowedHosts,
            Integer connectTimeoutMs,
            Integer readTimeoutMs
    ) {
        public Request(String url, String method, Map<String, String> headers, String body,
                List<String> allowedHosts) {
            this(url, method, headers, body, allowedHosts, null, null);
        }
    }

    public record Response(
            int statusCode,
            String body,
            Map<String, List<String>> headers
    ) {}

    public Response execute(Request req) {
        if (req.url() == null || req.url().isBlank()) {
            throw new BadRequestException("Outbound HTTP request URL is required");
        }

        URI uri;
        try {
            uri = URI.create(req.url());
        } catch (Exception ex) {
            throw new BadRequestException("Invalid URL syntax: " + ex.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new BadRequestException("URL scheme is required");
        }
        scheme = scheme.toLowerCase();
        if (!"https".equals(scheme)) {
            if ("http".equals(scheme)) {
                if (!allowHttp) {
                    throw new BadRequestException("HTTP is not allowed for outbound calls; HTTPS is required");
                }
            } else {
                throw new BadRequestException("Unsupported scheme: " + scheme + ". Only HTTPS is permitted");
            }
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BadRequestException("URL host is required");
        }

        // Validate against allowedHosts
        List<String> allowedHosts = req.allowedHosts();
        if (allowedHosts == null || allowedHosts.isEmpty()) {
            throw new BadRequestException("Outbound call blocked: installation has no allowedHosts configured");
        }
        boolean hostAllowed = false;
        for (String allowed : allowedHosts) {
            if (host.equalsIgnoreCase(allowed.trim())) {
                hostAllowed = true;
                break;
            }
        }
        if (!hostAllowed) {
            throw new BadRequestException("Host " + host + " is not in the allowedHosts allow-list: " + allowedHosts);
        }

        // Validate DNS resolution against SSRF
        validateHostResolution(host);

        // Execute HTTP connection
        String sanitizedUrl = sanitizeUrl(uri);
        log.info("Safe outbound HTTP: method={} url={}", req.method() != null ? req.method() : "GET", sanitizedUrl);

        try {
            URL targetUrl = uri.toURL();
            HttpURLConnection conn = (HttpURLConnection) targetUrl.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(req.connectTimeoutMs() == null
                    ? CONNECT_TIMEOUT_MS : req.connectTimeoutMs());
            conn.setReadTimeout(req.readTimeoutMs() == null
                    ? READ_TIMEOUT_MS : req.readTimeoutMs());

            String method = req.method() == null ? "GET" : req.method().toUpperCase();
            conn.setRequestMethod(method);

            if (req.headers() != null) {
                for (Map.Entry<String, String> entry : req.headers().entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        conn.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }
            }

            if (req.body() != null && !req.body().isEmpty()) {
                conn.setDoOutput(true);
                byte[] bodyBytes = req.body().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyBytes);
                    os.flush();
                }
            }

            int status = conn.getResponseCode();

            // Read response
            InputStream is = (status >= 200 && status < 400) ? conn.getInputStream() : conn.getErrorStream();
            String responseBody = "";
            if (is != null) {
                try (is) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] data = new byte[4096];
                    int totalRead = 0;
                    int n;
                    while ((n = is.read(data, 0, data.length)) != -1) {
                        totalRead += n;
                        if (totalRead > MAX_RESPONSE_BYTES) {
                            throw new BadRequestException("Outbound response exceeded maximum size limit of 256 KiB");
                        }
                        buffer.write(data, 0, n);
                    }
                    responseBody = buffer.toString(StandardCharsets.UTF_8);
                }
            }

            Map<String, List<String>> headers = new HashMap<>();
            conn.getHeaderFields().forEach((k, v) -> {
                if (k != null) {
                    headers.put(k, v);
                }
            });

            return new Response(status, responseBody, Collections.unmodifiableMap(headers));

        } catch (BadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Outbound HTTP connection failed for {}: {}", sanitizedUrl, ex.getMessage());
            throw new BadRequestException("Outbound connection failed: " + ex.getMessage());
        }
    }

    public void validateHostResolution(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (isBlockedIp(address)) {
                    throw new BadRequestException("Host " + host + " resolves to a restricted/private IP: " + address.getHostAddress());
                }
            }
        } catch (BadRequestException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BadRequestException("Cannot resolve host " + host + ": " + ex.getMessage());
        }
    }

    public static boolean isBlockedIp(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            // 127.0.0.0/8 (loopback)
            if (b0 == 127) return true;
            // 10.0.0.0/8 (private)
            if (b0 == 10) return true;
            // 172.16.0.0/12 (private)
            if (b0 == 172 && (b1 >= 16 && b1 <= 31)) return true;
            // 192.168.0.0/16 (private)
            if (b0 == 192 && b1 == 168) return true;
            // 169.254.0.0/16 (link-local, cloud metadata e.g. 169.254.169.254)
            if (b0 == 169 && b1 == 254) return true;
            // 0.0.0.0/8
            if (b0 == 0) return true;
            // 100.64.0.0/10 (CGNAT)
            if (b0 == 100 && (b1 >= 64 && b1 <= 127)) return true;
            // 192.0.0.0/24
            if (b0 == 192 && b1 == 0 && (bytes[2] & 0xFF) == 0) return true;
            // 198.18.0.0/15
            if (b0 == 198 && ((b1 & 0xFE) == 18)) return true;
        } else if (bytes.length == 16) {
            int b0 = bytes[0] & 0xFF;
            // fc00::/7 (unique local address)
            if ((b0 & 0xFE) == 0xFC) return true;
            // fe80::/10 (link local)
            if (b0 == 0xFE && ((bytes[1] & 0xFF) & 0xC0) == 0x80) return true;
            // ::1 or ::
            boolean allZero = true;
            for (int i = 0; i < 15; i++) {
                if (bytes[i] != 0) { allZero = false; break; }
            }
            if (allZero && (bytes[15] == 0 || bytes[15] == 1)) return true;
            // IPv4-mapped IPv6 (::ffff:0:0/96)
            if (allZero && bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF) {
                byte[] ipv4 = new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
                try {
                    return isBlockedIp(InetAddress.getByAddress(ipv4));
                } catch (Exception e) {
                    return true;
                }
            }
        }
        return false;
    }

    public static String sanitizeUrl(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        String path = uri.getPath();
        StringBuilder sb = new StringBuilder();
        if (scheme != null) {
            sb.append(scheme).append("://");
        }
        if (host != null) {
            sb.append(host);
        }
        if (port > 0 && !(("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80))) {
            sb.append(":").append(port);
        }
        if (path != null && !path.isBlank()) {
            sb.append(path);
        }
        return sb.toString();
    }
}
