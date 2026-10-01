package com.cacanode.api.auth.service;

import java.util.UUID;
import java.util.function.Function;

public interface JwtService {

    public String generateAccessToken(UUID userId, UUID tenantId, String email, String role);

    public String generateRefreshToken();

    public String hashToken(String token);

    public long getAccessTokenExpirySeconds();

    public String extractEmail(String token);

    public String extractTenantId(String token);

    public String extractUserId(String token);

    public String extractRole(String token);

    public <T> T extractClaim(String token, Function<io.jsonwebtoken.Claims, T> claimsResolver);

    /**
     * Generates a short-lived verification token for email activation.
     * 
     * @return JWT token valid for 24 hours
     */
    public String generateVerificationToken(UUID userId, String email);

    /**
     * Validates a verification token and returns its claims.
     * 
     * @throws UnauthorizedException if token is invalid or expired
     */
    public io.jsonwebtoken.Claims validateVerificationToken(String token);

}
