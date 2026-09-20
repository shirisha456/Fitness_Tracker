package com.fitnesstracker.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Two filter chains: one for the management port, one for the application.
 *
 * <h2>Why a separate management port</h2>
 *
 * Actuator used to share the application port, which meant {@code /actuator/prometheus}
 * was reachable by anyone who could reach the API. It now listens on its own port
 * ({@code management.server.port}), which Docker Compose deliberately does <em>not</em>
 * publish to the host — so it is reachable from inside the Compose network (where a
 * Prometheus container will scrape it) and from nowhere else.
 *
 * <p>That is the access control. Adding a password on top would mean putting a shared
 * credential in the scrape config for no gain over network isolation, and
 * {@code /api/v1/health} and {@code /api/v1/ready} — the contracts nginx and Docker
 * actually probe — stay on the application port regardless.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /** Auth endpoints that must work without a token, matching the documented route layout. */
    private static final String[] PUBLIC_AUTH_POST = {
        "/api/v1/auth/register",
        "/api/v1/auth/login",
        "/api/v1/auth/refresh",
        "/api/v1/auth/logout",
        "/api/v1/auth/forgot-password",
        "/api/v1/auth/reset-password",
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    /**
     * The management port. Ordered first so it claims Actuator requests before the
     * application chain sees them.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain managementSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    public SecurityFilterChain applicationSecurity(HttpSecurity http, CorsConfigurationSource cors)
            throws Exception {
        return http.cors(c -> c.configurationSource(cors))
                // No cookies are used for authentication — the Next.js BFF holds the tokens
                // server-side and sends them as a Bearer header — so there is no ambient
                // credential for CSRF to exploit.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.GET, "/api/v1/health", "/api/v1/ready")
                            .permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_POST).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/verify-email").permitAll()
                        // Everything else, including /auth/me and /auth/resend-verification.
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * Mirrors the documented CORS behaviour, reading the same comma-separated
     * {@code CORS_ORIGINS} variable.
     */
    // @Primary: Spring Security contributes its own CorsConfigurationSource, and the
    // application's origins must win.
    @Bean
    @Primary
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.origins:http://localhost:3000}") List<String> origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("*"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setExposedHeaders(List.of("X-Correlation-ID"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
