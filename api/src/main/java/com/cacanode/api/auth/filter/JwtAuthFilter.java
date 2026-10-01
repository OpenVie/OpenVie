package com.cacanode.api.auth.filter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.cacanode.api.auth.service.JwtService;
import com.cacanode.api.common.security.AppUserDetailsService;
import com.cacanode.api.tenant.api.TenantIdentityApi;
import com.cacanode.api.tenant.api.TenantIdentityApi.MembershipSnapshot;
import com.cacanode.api.tenant.api.WorkspaceRole;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Establishes the request principal and its ACTIVE workspace scope.
 *
 * <p>The workspace role from the JWT is never trusted on its own: membership is
 * re-resolved from the database on every request, so a revoked membership or a
 * demotion takes effect immediately rather than at token expiry.
 */
@Slf4j(topic = "JWT-AUTH-FILTER")
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

  private final JwtService jwtService;
  private final AppUserDetailsService userDetailsService;
  private final TenantIdentityApi identityApi;

  @Override
  protected void doFilterInternal(
    HttpServletRequest request,
    HttpServletResponse response,
    FilterChain filterChain
  ) throws ServletException, IOException {

    final String authHeader = request.getHeader("Authorization");

    // No token — pass through (SecurityConfig handles what's public/protected)
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      filterChain.doFilter(request, response);
      return;
    }

    final String token = authHeader.substring(7);

    try {
      String email = jwtService.extractEmail(token);
      String userIdClaim = jwtService.extractUserId(token);
      String orgIdClaim = jwtService.extractOrgId(token);
      String workspaceClaim = jwtService.extractActiveWorkspaceId(token);

      if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
        if (userIdClaim == null || orgIdClaim == null || workspaceClaim == null) {
          throw new IllegalStateException("Token is missing workspace scope");
        }
        UUID userId = UUID.fromString(userIdClaim);
        UUID workspaceId = UUID.fromString(workspaceClaim);

        // Authority comes from live membership, not from the token.
        MembershipSnapshot membership = identityApi.requireMembership(userId, workspaceId);
        if (!membership.orgId().toString().equals(orgIdClaim)) {
          throw new IllegalStateException("Token organization scope is invalid");
        }

        // Effective workspace authority: an organization owner acting inside a
        // workspace they belong to holds admin rights there.
        WorkspaceRole effectiveRole = membership.isWorkspaceAdmin()
          ? WorkspaceRole.WORKSPACE_ADMIN
          : membership.workspaceRole();

        var userDetails = userDetailsService.loadUserByUsername(email);
        List<SimpleGrantedAuthority> authorities = List.of(
          new SimpleGrantedAuthority("ROLE_" + effectiveRole.name()),
          new SimpleGrantedAuthority("ROLE_" + membership.orgRole().name()));

        UsernamePasswordAuthenticationToken authentication =
          new UsernamePasswordAuthenticationToken(userDetails, null, authorities);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // Request attributes are the only workspace source controllers may use.
        request.setAttribute("tenantId", workspaceId.toString());
        request.setAttribute("orgId", membership.orgId().toString());
        request.setAttribute("userId", userId.toString());
        request.setAttribute("role", effectiveRole.name());
        request.setAttribute("orgRole", membership.orgRole().name());
      }

    } catch (Exception e) {
      log.error("JWT validation failed: {}", e.getMessage());
      sendErrorResponse(response, HttpStatus.UNAUTHORIZED, e.getMessage());
      return;
    }

    filterChain.doFilter(request, response);
  }

  private void sendErrorResponse(
    HttpServletResponse response,
    HttpStatus status,
    String message
  ) throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    new ObjectMapper().writeValue(response.getOutputStream(), Map.of(
      "error", status.getReasonPhrase(),
      "message", message == null ? "Unauthorized" : message,
      "status", status.value()));
  }

}
