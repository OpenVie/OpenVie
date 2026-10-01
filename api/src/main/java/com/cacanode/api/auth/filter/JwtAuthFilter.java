package com.cacanode.api.auth.filter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.cacanode.api.common.security.AppUserDetailsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cacanode.api.auth.service.JwtService;
import com.cacanode.api.tenant.api.TenantIdentityApi;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j(topic = "JWT-AUTH-FILTER")
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

  private final JwtService jwtService;
  private final AppUserDetailsService userDetailsService;
  private final TenantIdentityApi tenantIdentityApi;

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
      // 1. Validate and extract claims
      String email = jwtService.extractEmail(token);
      String tenantId = jwtService.extractTenantId(token);
      String role = jwtService.extractRole(token);
      String authenticatedRole = role;

      log.debug("JWT valid - email: {}, tenantId: {}, role: {}", email, tenantId, role);

      // 2. Only set auth if not already authenticated
      if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
        
        // 3. Load user details from database
        UserDetails userDetails = userDetailsService.loadUserByUsername(email);

        if (!userDetails.isEnabled() || !userDetails.isAccountNonLocked()
          || !userDetails.isAccountNonExpired() || !userDetails.isCredentialsNonExpired()) {
          throw new IllegalStateException("User account is disabled");
        }
        String tokenUserId = jwtService.extractUserId(token);
        var user = tenantIdentityApi.requireUser(
                java.util.UUID.fromString(tenantId), java.util.UUID.fromString(tokenUserId));
        if (!"ACTIVE".equals(user.status())) {
          throw new IllegalStateException("User account is disabled or token scope is invalid");
        }
        if (!user.role().equals(role)) {
          throw new IllegalStateException("JWT role is stale or invalid");
        }
        authenticatedRole = user.role();

        // 4. Build authentication token
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
          userDetails,
          null,
          userDetails.getAuthorities()
        );
        authentication.setDetails(
          new WebAuthenticationDetailsSource().buildDetails(request)
        );

        // 5. Set in SecurityContext — request is now authenticated
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 6. Store tenantId in request attribute for controllers
        request.setAttribute("tenantId", tenantId);
        request.setAttribute("userId", jwtService.extractUserId(token));
        request.setAttribute("role", authenticatedRole);
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
    response.setCharacterEncoding("UTF-8");

    Map<String, Object> body = Map.of(
      "timestamp", LocalDateTime.now().toString(),
      "status", status.value(),
      "error", status.getReasonPhrase(),
      "message", message
    );

    new ObjectMapper().writeValue(response.getWriter(), body);
  }

}
