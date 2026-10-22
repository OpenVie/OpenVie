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

package com.openvie.api.common.filter;

import com.openvie.api.common.cache.CacheMetrics;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Per-consumer rate limiting for authenticated traffic.
 *
 * <p>The counter key is `consumer-rate:{tenant}:{user}:{minute}` — both
 * credential kinds (a user JWT or a workspace-scoped API key) resolve to the
 * same request attributes, so integrations and humans share one fair limit.
 *
 * <p>Fail-open, consistent with {@link PublicRateLimitFilter}: API availability
 * must not depend on Redis uptime. The limit is deliberately generous and
 * validated-protection lives at the edge (APISIX) for hostile traffic patterns.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "CONSUMER-RATE-LIMIT")
public class ConsumerRateLimitFilter extends OncePerRequestFilter {

    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]); "
                    + "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]); end; "
                    + "return count;",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final CacheMetrics cacheMetrics;

    @Value("${app.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${app.rate-limit.consumer-requests-per-minute:600}")
    private long requestsPerMinute;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled || "OPTIONS".equalsIgnoreCase(request.getMethod())
                || request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        // Only already-authenticated requests are metered; the public filter
        // covers the unauthenticated attack surface. ASYNC re-dispatches (SSE)
        // carry the same attributes and would double-count.
        return request.getAttribute("tenantId") == null || request.getAttribute("userId") == null;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long epochSeconds = Instant.now().getEpochSecond();
        long minute = epochSeconds / 60;
        String key = "consumer-rate:%s:%s:%d".formatted(
                request.getAttribute("tenantId"), request.getAttribute("userId"), minute);

        try {
            Long count = redisTemplate.execute(INCREMENT_SCRIPT, List.of(key), "120");
            cacheMetrics.redisOperation("consumer-rate-limit", "increment", "success");
            if (count != null) {
                response.setHeader("X-RateLimit-Limit", Long.toString(requestsPerMinute));
                response.setHeader("X-RateLimit-Remaining",
                        Long.toString(Math.max(0, requestsPerMinute - count)));
                if (count > requestsPerMinute) {
                    writeRateLimited(response, 60 - epochSeconds % 60);
                    return;
                }
            }
        } catch (RuntimeException exception) {
            cacheMetrics.redisOperation("consumer-rate-limit", "increment", "error");
            log.warn("Consumer rate limiter unavailable; allowing request path={} reason={}",
                    request.getRequestURI(), exception.getClass().getSimpleName());
        }

        filterChain.doFilter(request, response);
    }

    private void writeRateLimited(HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"timestamp\":\"" + Instant.now() + "\",\"status\":429,"
                        + "\"error\":\"Too Many Requests\","
                        + "\"message\":\"Authenticated request rate limit exceeded\"}");
    }
}
