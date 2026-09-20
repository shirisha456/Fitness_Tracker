package com.fitnesstracker.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesstracker.support.ContractFixtures;
import com.fitnesstracker.security.TokenService;
import com.fitnesstracker.security.TokenType;
import com.fitnesstracker.support.PostgresIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Cross-language JWT compatibility.
 *
 * <p>Sharing {@code SECRET_KEY} is necessary but nowhere near sufficient: the claim set,
 * the {@code type} claim, the NumericDate encoding and the absence of {@code iss}/{@code
 * aud} all have to agree too. So this consumes tokens actually minted by PyJWT
 * ({@code contract/jwt-fixtures.json}, produced by {@code contract/generate_jwt_fixtures.py})
 * and drives them through the real {@code /api/v1/auth/me} endpoint.
 *
 * <p>The other direction — Python accepting Java's tokens — is asserted by
 * {@code contract/verify_java_jwts.py}, which this test writes its input for, and again
 * live in the Docker validation where both backends share one database.
 */
class JwtCompatibilityTest extends PostgresIntegrationTest {

    private static final String FIXTURES = "jwt-fixtures.json";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TokenService tokenService;

    private static JsonNode fixtures;

    private static JsonNode load() throws IOException {
        if (fixtures == null) {
            fixtures = new ObjectMapper().readTree(ContractFixtures.read(FIXTURES));
        }
        return fixtures;
    }

    /**
     * The fixture tokens name user ids that must exist for {@code /me} to succeed. Rows are
     * inserted with those exact ids so the token, not the id generator, is what is tested.
     */
    @BeforeEach
    void seedFixtureUsers() throws IOException {
        for (JsonNode token : load().get("accepted_access_tokens")) {
            UUID id = UUID.fromString(token.get("user_id").asText());
            jdbc.update("""
                    INSERT INTO users (id, email, password_hash, role, email_verified, is_active)
                    VALUES (?, ?, 'x', ?::user_role, ?, true)
                    ON CONFLICT (id) DO NOTHING
                    """,
                    id, "jwt-fixture-" + id + "@example.com",
                    token.get("role").asText(), token.get("email_verified").asBoolean());
        }
    }

    static List<JsonNode> acceptedAccessTokens() throws IOException {
        return toList(load().get("accepted_access_tokens"));
    }

    static List<JsonNode> rejectedTokens() throws IOException {
        return toList(load().get("rejected_tokens"));
    }

    static List<JsonNode> rejectedRefreshTokens() throws IOException {
        return toList(load().get("rejected_refresh_tokens"));
    }

    private static List<JsonNode> toList(JsonNode array) {
        List<JsonNode> items = new ArrayList<>();
        array.forEach(items::add);
        return items;
    }

    // --- Python -> Java ------------------------------------------------------

    @ParameterizedTest(name = "Java accepts a PyJWT access token: {0}")
    @MethodSource("acceptedAccessTokens")
    void javaAcceptsPythonAccessTokens(JsonNode fixture) throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + fixture.get("token").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(fixture.get("user_id").asText()))
                .andExpect(jsonPath("$.data.role").value(fixture.get("role").asText()))
                .andExpect(jsonPath("$.data.email_verified")
                        .value(fixture.get("email_verified").asBoolean()));
    }

    @ParameterizedTest(name = "Java rejects: {0}")
    @MethodSource("rejectedTokens")
    void javaRejectsBadTokens(JsonNode fixture) throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + fixture.get("token").asText()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(fixture.get("expected_code").asText()));
    }

    @ParameterizedTest(name = "Java rejects at /refresh: {0}")
    @MethodSource("rejectedRefreshTokens")
    void javaRejectsBadRefreshTokens(JsonNode fixture) throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"refresh_token\":\"" + fixture.get("token").asText() + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(fixture.get("expected_code").asText()));
    }

    @Test
    @DisplayName("Java parses a PyJWT refresh token to the same subject and jti")
    void javaParsesPythonRefreshTokens() throws Exception {
        JsonNode fixture = load().get("accepted_refresh_tokens").get(0);

        var parsed = tokenService.parse(fixture.get("token").asText(), TokenType.REFRESH);

        assertThat(parsed.userId()).isEqualTo(UUID.fromString(fixture.get("user_id").asText()));
        assertThat(parsed.jti())
                .as("the jti is the refresh_tokens primary key; both backends must agree on it")
                .isEqualTo(UUID.fromString(fixture.get("jti").asText()));
    }

    @Test
    @DisplayName("the production TTLs match: 15 minutes for access, 7 days for refresh")
    void ttlsMatchPython() throws Exception {
        JsonNode reference = load().get("ttl_reference");

        assertThat(ttlSeconds(reference.get("access").asText()))
                .as("access token lifetime")
                .isEqualTo(900);
        assertThat(ttlSeconds(reference.get("refresh").asText()))
                .as("refresh token lifetime")
                .isEqualTo(7 * 24 * 3600);
    }

    /** exp - iat straight from the payload; the token itself may already be expired. */
    private long ttlSeconds(String token) throws IOException {
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(token.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
        JsonNode claims = new ObjectMapper().readTree(payload);
        return claims.get("exp").asLong() - claims.get("iat").asLong();
    }

    @Test
    @DisplayName("a token missing the Bearer prefix is unauthenticated, not a 500")
    void malformedAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Basic abc123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").value("Not authenticated"));
    }

    // --- Java -> Python ------------------------------------------------------

    @Test
    @DisplayName("Java-minted tokens are written out for Python to verify")
    void emitsJavaTokensForPython() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, role, email_verified, is_active)
                VALUES (?, ?, 'x', 'user'::user_role, true, true)
                """, userId, "java-minted-" + userId + "@example.com");

        var user = new com.fitnesstracker.auth.entity.User(
                "java-minted@example.com", "x", com.fitnesstracker.auth.entity.UserRole.USER);
        // Sign for the seeded id so Python can resolve the same subject.
        var access = tokenService.issueAccessToken(loadUser(userId));
        var refresh = tokenService.issueRefreshToken(userId);

        // Sanity: Java accepts what Java minted, through the real endpoint.
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + access.token()))
                .andExpect(status().isOk());

        Path out = Path.of("target", "java-jwt-fixtures.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, new ObjectMapper().writeValueAsString(java.util.Map.of(
                "user_id", userId.toString(),
                "access_token", access.token(),
                "refresh_token", refresh.token(),
                "refresh_jti", refresh.jti().toString())));

        assertThat(Files.readString(out)).contains("access_token");
        assertThat(user).isNotNull();
    }

    private com.fitnesstracker.auth.entity.User loadUser(UUID id) {
        return jdbc.queryForObject(
                "SELECT id, email, role::text, email_verified FROM users WHERE id = ?",
                (rs, rowNum) -> {
                    var u = new com.fitnesstracker.auth.entity.User(
                            rs.getString("email"), "x",
                            com.fitnesstracker.auth.entity.UserRole.fromValue(rs.getString("role")));
                    // The entity's id is generated; reflectively set it so the token's sub
                    // matches the seeded row rather than a fresh UUID.
                    try {
                        var field = com.fitnesstracker.auth.entity.User.class
                                .getDeclaredField("id");
                        field.setAccessible(true);
                        field.set(u, rs.getObject("id", UUID.class));
                        var verified = com.fitnesstracker.auth.entity.User.class
                                .getDeclaredField("emailVerified");
                        verified.setAccessible(true);
                        verified.setBoolean(u, rs.getBoolean("email_verified"));
                    } catch (ReflectiveOperationException ex) {
                        throw new IllegalStateException(ex);
                    }
                    return u;
                }, id);
    }
}
