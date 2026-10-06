package com.cacanode.api.bootstrap.config;

import com.cacanode.api.auth.filter.JwtAuthFilter;
import com.cacanode.api.common.config.CorsProperties;
import com.cacanode.api.common.filter.PublicRateLimitFilter;
import com.cacanode.api.common.security.AppUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.springframework.security.config.http.SessionCreationPolicy.STATELESS;

import java.util.List;

// Self-hosted boundary: only auth, OpenAPI and health/info are public.
// Everything else requires an authenticated tenant-scoped principal.
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;                  // auth module
    private final PublicRateLimitFilter publicRateLimitFilter;
    private final AppUserDetailsService userDetailsService;     // common interface
    private final CorsProperties corsProperties;

    private static final String[] PUBLIC_ENDPOINTS = {
            // Login is password-only; email is optional, so no 2FA routes remain.
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/workspaces/switch",
            "/api/v1/auth/registration-status",
            "/api/v1/auth/register",
            "/api/v1/auth/invitations/validate",
            "/api/v1/auth/invitations/accept",
            // One-time claim: the service refuses every call once an account
            // exists, so the route table does not have to close itself.
            "/api/v1/setup",
            "/api/v1/setup/status",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/swagger-resources/**",
            "/webjars/**",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationProvider authenticationProvider
    ) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .headers(headers -> headers.frameOptions(frame -> frame.disable()))
            .authorizeHttpRequests(request -> request
                .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                .anyRequest().authenticated()
            )
            .sessionManagement(manager -> manager
                .sessionCreationPolicy(STATELESS)
            )
            .authenticationProvider(authenticationProvider)
            .addFilterBefore(publicRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider(PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config
    ) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        if (!corsProperties.getAllowedOriginPatterns().isEmpty()) {
            config.setAllowedOriginPatterns(corsProperties.getAllowedOriginPatterns());
        } else if (!corsProperties.getAllowedOrigins().isEmpty()) {
            config.setAllowedOrigins(corsProperties.getAllowedOrigins());
        } else {
            config.setAllowedOrigins(List.of("http://localhost:3000"));
        }

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Total-Count", "X-Next-Cursor"));
        config.setAllowCredentials(true); // required for HttpOnly cookies

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
