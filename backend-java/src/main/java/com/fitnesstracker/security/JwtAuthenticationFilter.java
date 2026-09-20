package com.fitnesstracker.security;

import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.auth.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the caller from the {@code Authorization: Bearer} header.
 *
 * <p>Mirrors {@code dependencies.get_current_user} + {@code auth.service
 * .get_current_user_from_token}: decode, require {@code type == "access"}, load the user,
 * and reject a user who is inactive or soft-deleted.
 *
 * <p>A bad token is <em>not</em> rejected here. The filter leaves the context
 * unauthenticated and lets {@link RestAuthenticationEntryPoint} render the 401, so every
 * unauthenticated response has one shape. The failure reason is stashed on the request so
 * the entry point can distinguish {@code TOKEN_EXPIRED} from {@code UNAUTHORIZED} — a
 * distinction the previous implementation makes and the frontend's silent-refresh relies on.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    static final String FAILURE_ATTRIBUTE = "jwtAuthenticationFailure";
    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokens;
    private final UserRepository users;

    public JwtAuthenticationFilter(TokenService tokens, UserRepository users) {
        this.tokens = tokens;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            // No credentials offered. Endpoints that permit anonymous access continue;
            // the rest are stopped by the entry point.
            chain.doFilter(request, response);
            return;
        }

        try {
            TokenService.ParsedToken parsed =
                    tokens.parse(header.substring(BEARER_PREFIX.length()), TokenType.ACCESS);
            Optional<User> user = users.findById(parsed.userId())
                    .filter(candidate -> candidate.isActive() && candidate.getDeletedAt() == null);

            if (user.isEmpty()) {
                // Deliberately the same generic failure as a bad token: whether a user id
                // exists is not something an unauthenticated caller should be able to probe.
                request.setAttribute(FAILURE_ATTRIBUTE, TokenException.invalid());
            } else {
                AuthenticatedUser principal = new AuthenticatedUser(user.get());
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (TokenException ex) {
            request.setAttribute(FAILURE_ATTRIBUTE, ex);
        }

        chain.doFilter(request, response);
    }
}
