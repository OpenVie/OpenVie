package com.cacanode.api.common.filter;

import com.cacanode.api.common.cache.CacheMetrics;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

@Component
@Slf4j
public class PublicRateLimitFilter extends OncePerRequestFilter {

    private static final DefaultRedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]); "
                    + "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]); end; "
                    + "return count;",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final CacheMetrics cacheMetrics;
    private final TrustedProxyClientIpResolver clientIps;

    @org.springframework.beans.factory.annotation.Autowired
    public PublicRateLimitFilter(StringRedisTemplate redisTemplate,CacheMetrics cacheMetrics,
            TrustedProxyClientIpResolver clientIps){this.redisTemplate=redisTemplate;this.cacheMetrics=cacheMetrics;this.clientIps=clientIps;}
    PublicRateLimitFilter(StringRedisTemplate redisTemplate,CacheMetrics cacheMetrics){
        this(redisTemplate,cacheMetrics,new TrustedProxyClientIpResolver("127.0.0.1/32,::1/128"));
    }

    @Value("${app.rate-limit.enabled:true}")
    private boolean enabled;

    @Value("${app.rate-limit.public-requests-per-minute:120}")
    private long requestsPerMinute;

    /** Public paths that must be throttled: auth, invitations, and the install claim. */
    private static final String[] PUBLIC_PREFIXES = {"/api/v1/auth/", "/api/v1/setup"};

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // SecurityConfig decides what is public; everything else is authenticated.
        // Setup is included because claiming an installation is the highest-value
        // unauthenticated write the product exposes.
        String path = request.getRequestURI();
        return java.util.Arrays.stream(PUBLIC_PREFIXES).noneMatch(path::startsWith);
    }

    private String routeGroup(String path) {
        if (path.startsWith("/api/v1/auth/")) {
            return "auth:" + path.substring("/api/v1/auth/".length());
        }
        if (path.startsWith("/api/v1/setup")) {
            return "setup:" + path.substring("/api/v1/setup".length()).replace('/', ':');
        }
        return "other";
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long epochSeconds = Instant.now().getEpochSecond();
        long minute = epochSeconds / 60;
        String key = "public-rate:%s:%s:%d".formatted(
                routeGroup(request.getRequestURI()), clientIdentity(request), minute
        );

        try {
            Long count = redisTemplate.execute(INCREMENT_SCRIPT, List.of(key), "120");
            cacheMetrics.redisOperation("public-rate-limit", "increment", "success");
            if (count != null && count > requestsPerMinute) {
                writeRateLimited(response, 60 - epochSeconds % 60);
                return;
            }
        } catch (RuntimeException exception) {
            cacheMetrics.redisOperation("public-rate-limit", "increment", "error");
            // Public authentication availability must not depend on Redis uptime.
            log.warn("Public rate limiter unavailable; allowing request path={} reason={}",
                    request.getRequestURI(), exception.getClass().getSimpleName());
        }

        filterChain.doFilter(request, response);
    }

    private String clientIdentity(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && !authorization.isBlank()) {
            return sha256(authorization);
        }
        return sha256(clientIps.resolve(request));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void writeRateLimited(HttpServletResponse response, long retryAfterSeconds)
            throws IOException {
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"status\":429,\"error\":\"Too Many Requests\","
                        + "\"message\":\"Public API rate limit exceeded\"}"
        );
    }
}
