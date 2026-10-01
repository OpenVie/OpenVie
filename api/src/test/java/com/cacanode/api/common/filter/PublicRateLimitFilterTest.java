package com.cacanode.api.common.filter;

import com.cacanode.api.common.cache.CacheMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicRateLimitFilterTest {

    private StringRedisTemplate redisTemplate;
    private CacheMetrics cacheMetrics;
    private PublicRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        cacheMetrics = mock(CacheMetrics.class);
        filter = new PublicRateLimitFilter(redisTemplate, cacheMetrics);
        ReflectionTestUtils.setField(filter, "enabled", true);
        ReflectionTestUtils.setField(filter, "requestsPerMinute", 120L);
    }

    @Test
    void blocksPublicRequestAfterConfiguredLimit() throws Exception {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), any(Object[].class)
        )).thenReturn(121L);
        MockHttpServletRequest request = request("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        assertNotNull(response.getHeader("Retry-After"));
        assertEquals("application/json", response.getContentType());
        assertEquals(null, chain.getRequest());
        verify(cacheMetrics).redisOperation("public-rate-limit", "increment", "success");
    }

    @Test
    void allowsPublicRequestWithinLimit() throws Exception {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), any(Object[].class)
        )).thenReturn(1L);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(
                request("POST", "/api/v1/auth/invitations/accept"),
                new MockHttpServletResponse(),
                chain
        );

        assertNotNull(chain.getRequest());
        verify(cacheMetrics).redisOperation("public-rate-limit", "increment", "success");
    }

    @Test
    void failsOpenWhenRedisIsUnavailable() throws Exception {
        when(redisTemplate.execute(
                any(RedisScript.class), anyList(), any(Object[].class)
        )).thenThrow(new IllegalStateException("Redis unavailable"));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(
                request("POST", "/api/v1/auth/refresh"),
                new MockHttpServletResponse(),
                chain
        );

        assertNotNull(chain.getRequest());
        verify(cacheMetrics).redisOperation("public-rate-limit", "increment", "error");
    }

    @Test
    void doesNotRateLimitAuthenticatedApplicationRoutes() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(
                request("GET", "/api/v1/documents"),
                new MockHttpServletResponse(),
                chain
        );

        assertNotNull(chain.getRequest());
        verify(redisTemplate, never()).execute(
                any(RedisScript.class), anyList(), any(Object[].class)
        );
    }

    @Test
    void retainedAuthRoutesGetIndependentBuckets() {
        String login = ReflectionTestUtils.invokeMethod(filter, "routeGroup", "/api/v1/auth/login");
        String refresh = ReflectionTestUtils.invokeMethod(filter, "routeGroup", "/api/v1/auth/refresh");

        assertEquals("auth:login", login);
        assertEquals("auth:refresh", refresh);
        assertNotEquals(login, refresh);
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("203.0.113.10");
        return request;
    }
}
