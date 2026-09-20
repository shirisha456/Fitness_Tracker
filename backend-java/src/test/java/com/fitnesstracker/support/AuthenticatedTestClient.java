package com.fitnesstracker.support;

import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.auth.entity.UserRole;
import com.fitnesstracker.auth.repository.UserRepository;
import com.fitnesstracker.security.TokenService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Creates users and their Authorization headers.
 *
 * <p>Domain tests need "a user with a valid token" constantly; going through the real
 * register/login endpoints for each one would add two Argon2 hashes (65 MB, 3 iterations)
 * per user and dominate the runtime. The token is still minted by the real
 * {@link TokenService}, so authentication is exercised exactly as in production.
 */
@TestConfiguration
public class AuthenticatedTestClient {

    @Bean
    TestUsers testUsers(UserRepository users, TokenService tokens) {
        return new TestUsers(users, tokens);
    }

    @Component
    public static class TestUsers {

        private final UserRepository users;
        private final TokenService tokens;

        public TestUsers(UserRepository users, TokenService tokens) {
            this.users = users;
            this.tokens = tokens;
        }

        public User create(String email) {
            return users.saveAndFlush(new User(email, "not-used-in-these-tests", UserRole.USER));
        }

        public String bearerFor(User user) {
            return "Bearer " + tokens.issueAccessToken(user).token();
        }

        /** A user plus their header, the pair most tests actually want. */
        public Session session(String email) {
            User user = create(email);
            return new Session(user, bearerFor(user));
        }

        public record Session(User user, String authorization) {}
    }
}
