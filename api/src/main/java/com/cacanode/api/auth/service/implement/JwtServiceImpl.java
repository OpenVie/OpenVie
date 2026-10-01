package com.cacanode.api.auth.service.implement;

import com.cacanode.api.auth.service.JwtService;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.function.Function;

@Service
@Slf4j(topic = "JWT-SERVICE")
public class JwtServiceImpl implements JwtService {

    @Value("${jwt.token-key}")
    private String tokenKey;

    @Value("${jwt.expiry-mins}")
    private int expiryMins;

    @Override
    public String generateAccessToken(
            UUID userId,
            UUID orgId,
            UUID activeWorkspaceId,
            String email,
            String orgRole,
            String workspaceRole) {
        SecretKey key = Keys.hmacShaKeyFor(
                tokenKey.getBytes(StandardCharsets.UTF_8));

        return Jwts.builder()
                .subject(email)
                .claim("userId", userId.toString())
                .claim("orgId", orgId.toString())
                .claim("activeWorkspaceId", activeWorkspaceId.toString())
                .claim("orgRole", orgRole)
                .claim("role", workspaceRole)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + (long) expiryMins * 60 * 1000))
                .signWith(key)
                .compact();
    }

    @Override
    public String generateRefreshToken() {
        byte[] bytes = new byte[64];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    @Override
    public long getAccessTokenExpirySeconds() {
        return (long) expiryMins * 60;
    }

    @Override
    public String extractEmail(String token) {
        return extractClaim(token, io.jsonwebtoken.Claims::getSubject);
    }

    @Override
    public String extractOrgId(String token) {
        return extractClaim(token, claims -> claims.get("orgId", String.class));
    }

    @Override
    public String extractActiveWorkspaceId(String token) {
        return extractClaim(token, claims -> claims.get("activeWorkspaceId", String.class));
    }

    @Override
    public String extractUserId(String token) {
        return extractClaim(token, claims -> claims.get("userId", String.class));
    }

    @Override
    public String extractRole(String token) {
        return extractClaim(token, claims -> claims.get("role", String.class));
    }

    @Override
    public String extractOrgRole(String token) {
        return extractClaim(token, claims -> claims.get("orgRole", String.class));
    }

    @Override
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        SecretKey key = Keys.hmacShaKeyFor(
                tokenKey.getBytes(StandardCharsets.UTF_8));

        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return claimsResolver.apply(claims);
    }
}
