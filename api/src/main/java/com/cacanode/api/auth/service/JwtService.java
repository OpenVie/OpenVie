package com.cacanode.api.auth.service;

import java.util.UUID;
import java.util.function.Function;

/**
 * Access tokens carry an organization, an ACTIVE workspace, and the caller's
 * role inside that workspace. The active workspace is revalidated against
 * membership on every request; tokens are reissued (not mutated) when the
 * caller switches workspaces.
 */
public interface JwtService {

    String generateAccessToken(
            UUID userId,
            UUID orgId,
            UUID activeWorkspaceId,
            String email,
            String orgRole,
            String workspaceRole);

    String generateRefreshToken();

    String hashToken(String token);

    long getAccessTokenExpirySeconds();

    String extractEmail(String token);

    String extractOrgId(String token);

    /** The workspace the token is scoped to; null for pre-workspace tokens. */
    String extractActiveWorkspaceId(String token);

    String extractUserId(String token);

    /** Role inside the active workspace (WORKSPACE_ADMIN|MEMBER). */
    String extractRole(String token);

    /** Organization-level role (ORG_OWNER|MEMBER). */
    String extractOrgRole(String token);

    <T> T extractClaim(String token, Function<io.jsonwebtoken.Claims, T> claimsResolver);
}
