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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contract of the authenticated rate limiter: only metered traffic, a hard
 * 429 with standard headers at the ceiling, and fail-open on Redis outages so
 * API availability never depends on the counter store.
 */
class ConsumerRateLimitFilterTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final CacheMetrics cacheMetrics = mock(CacheMetrics.class);
    private final ConsumerRateLimitFilter filter =
            new ConsumerRateLimitFilter(redisTemplate, cacheMetrics);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void configure() throws Exception {
        var enabledField = ConsumerRateLimitFilter.class.getDeclaredField("enabled");
        enabledField.setAccessible(true);
        enabledField.set(filter, true);
        var limitField = ConsumerRateLimitFilter.class.getDeclaredField("requestsPerMinute");
        limitField.setAccessible(true);
        limitField.set(filter, 3L);
    }

    @Test
    void trafficWithoutPrincipalAttributesIsNeverMetered() {
        assertTrue(filter.shouldNotFilter(request), "public or unauthenticated requests are out of scope");
    }

    @Test
    void asyncRedispatchesAreNotDoubleCounted() throws Exception {
        request.setAttribute("tenantId", "00000000-0000-0000-0000-000000000001");
        request.setAttribute("userId", "00000000-0000-0000-0000-000000000002");
        MockHttpServletRequest asyncRequest = new MockHttpServletRequest();
        asyncRequest.setAttribute("tenantId", "00000000-0000-0000-0000-000000000001");
        asyncRequest.setAttribute("userId", "00000000-0000-0000-0000-000000000002");
        asyncRequest.setDispatcherType(DispatcherType.ASYNC);
        assertTrue(filter.shouldNotFilter(asyncRequest), "SSE async re-dispatches share the initial request");
    }

    @Test
    void overLimitRequestsAnswer429WithRateLimitHeaders() throws Exception {
        request.setAttribute("tenantId", "00000000-0000-0000-0000-000000000003");
        request.setAttribute("userId", "00000000-0000-0000-0000-000000000004");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq("120")))
                .thenReturn(3L, 4L);

        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(HttpStatus.OK.value(), response.getStatus(), "at the limit the request flows");

        var secondResponse = new MockHttpServletResponse();
        filter.doFilter(request, secondResponse, new MockFilterChain());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), secondResponse.getStatus());
        assertEquals("3", secondResponse.getHeader("X-RateLimit-Limit"));
        assertEquals("0", secondResponse.getHeader("X-RateLimit-Remaining"));
        assertEquals("3", secondResponse.getHeader("X-RateLimit-Limit"));
        org.junit.jupiter.api.Assertions.assertNotNull(secondResponse.getHeader("Retry-After"));
        String body = secondResponse.getContentAsString();
        assertTrue(body.contains("\"status\":429") && body.contains("rate limit"),
                "429 body follows the standard error shape: " + body);
    }

    @Test
    void aRedisOutageFailsOpen() throws Exception {
        request.setAttribute("tenantId", "00000000-0000-0000-0000-000000000005");
        request.setAttribute("userId", "00000000-0000-0000-0000-000000000006");
        doThrow(new IllegalStateException("connection refused"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), eq("120"));

        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(HttpStatus.OK.value(), response.getStatus(),
                "availability must not depend on Redis uptime");
    }
}
