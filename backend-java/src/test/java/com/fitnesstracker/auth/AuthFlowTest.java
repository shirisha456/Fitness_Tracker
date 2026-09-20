package com.fitnesstracker.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesstracker.notifications.ConsoleEmailSender;
import com.fitnesstracker.notifications.EmailKind;
import com.fitnesstracker.support.PostgresIntegrationTest;
import com.fitnesstracker.support.SynchronousEmailDispatcher;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The auth endpoints end to end, against the contract captured in
 * {@code docs/api-compatibility.md} from a running reference instance.
 *
 * <p>Emails are delivered by {@link ConsoleEmailSender}, which captures them in memory —
 * no automated test ever sends a real one. The queue is bypassed here (the worker is
 * disabled in tests) and exercised separately in {@code EmailQueueTest}.
 */
@org.springframework.context.annotation.Import(SynchronousEmailDispatcher.class)
class AuthFlowTest extends PostgresIntegrationTest {

    private static final String PASSWORD = "SecurePass123!";
    private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private ConsoleEmailSender emails;

    @BeforeEach
    void clearEmails() {
        emails.clear();
    }

    // --- helpers -------------------------------------------------------------

    private MvcResult register(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "email", email,
                                "password", PASSWORD,
                                "password_confirm", PASSWORD))))
                .andReturn();
    }

    private JsonNode login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private String tokenFromLatestEmail(EmailKind kind) {
        var email = emails.sent().stream()
                .filter(sent -> sent.kind() == kind)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("no " + kind + " email was sent"));
        Matcher matcher = TOKEN_IN_LINK.matcher(email.text());
        assertThat(matcher.find()).as("link in %s email: %s", kind, email.text()).isTrue();
        return matcher.group(1);
    }

    // --- registration --------------------------------------------------------

    @Test
    @DisplayName("register returns 201 with the user and the documented message")
    void registerSucceeds() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "email", "Register.Me@Example.COM ",
                                "password", PASSWORD,
                                "password_confirm", PASSWORD))))
                .andExpect(status().isCreated())
                // Email is lowercased and trimmed, as the documented contract requires.
                .andExpect(jsonPath("$.data.email").value("register.me@example.com"))
                .andExpect(jsonPath("$.data.email_verified").value(false))
                .andExpect(jsonPath("$.data.role").value("user"))
                .andExpect(jsonPath("$.data.created_at").isNotEmpty())
                .andExpect(jsonPath("$.data.password_hash").doesNotExist())
                .andExpect(jsonPath("$.message").value(
                        "Registration successful. Please check your email to verify your account."));

        assertThat(emails.sent()).singleElement()
                .satisfies(sent -> assertThat(sent.kind()).isEqualTo(EmailKind.VERIFICATION));
    }

    @Test
    @DisplayName("a duplicate email is 409 CONFLICT")
    void duplicateRegistrationConflicts() throws Exception {
        register("dupe@example.com");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "email", "dupe@example.com",
                                "password", PASSWORD,
                                "password_confirm", PASSWORD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.message")
                        .value("An account with this email already exists"));
    }

    @Test
    @DisplayName("a weak password is 400 with the field named")
    void weakPasswordRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "email", "weak@example.com",
                                "password", "alllowercase1!",
                                "password_confirm", "alllowercase1!"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field",
                        org.hamcrest.Matchers.hasItem("password")));
    }

    @Test
    @DisplayName("mismatched confirmation is 400, not 500")
    void mismatchedConfirmationRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "email", "mismatch@example.com",
                                "password", PASSWORD,
                                "password_confirm", "DifferentPass123!"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("password_confirm"));
    }

    @Test
    @DisplayName("the stored hash is Argon2id with the pinned parameters")
    void passwordIsHashedCompatibly() throws Exception {
        register("hashcheck@example.com");
        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE email = ?",
                String.class, "hashcheck@example.com");

        assertThat(hash).startsWith("$argon2id$v=19$m=65536,t=3,p=4$");
        assertThat(hash).doesNotContain(PASSWORD);
    }

    // --- login ---------------------------------------------------------------

    @Test
    @DisplayName("login returns the token pair in the documented shape")
    void loginSucceeds() throws Exception {
        register("login@example.com");
        JsonNode data = login("login@example.com", PASSWORD);

        assertThat(data.get("token_type").asText()).isEqualTo("bearer");
        assertThat(data.get("expires_in").asLong()).isEqualTo(900);
        assertThat(data.get("access_token").asText()).isNotBlank();
        assertThat(data.get("refresh_token").asText()).isNotBlank();
        assertThat(data.get("user").get("email").asText()).isEqualTo("login@example.com");
    }

    @Test
    @DisplayName("an unknown email and a wrong password are indistinguishable")
    void loginFailuresAreIdentical() throws Exception {
        register("known@example.com");

        String wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "email", "known@example.com", "password", "WrongPass123!"))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String unknownEmail = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "email", "nobody@example.com", "password", PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        // Same code and message; only the correlation id differs. Anything else would let
        // the endpoint be used to enumerate registered addresses.
        assertThat(json.readTree(wrongPassword).get("error").get("message").asText())
                .isEqualTo(json.readTree(unknownEmail).get("error").get("message").asText())
                .isEqualTo("Invalid email or password");
    }

    @Test
    @DisplayName("a deactivated account is 403, not 401")
    void deactivatedAccountForbidden() throws Exception {
        register("deactivated@example.com");
        jdbc.update("UPDATE users SET is_active = false WHERE email = ?", "deactivated@example.com");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "email", "deactivated@example.com", "password", PASSWORD))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.message").value("Account is deactivated"));
    }

    @Test
    @DisplayName("a soft-deleted user's existing access token stops working immediately")
    void softDeleteRevokesAccessImmediately() throws Exception {
        register("softdelete@example.com");
        String access = login("softdelete@example.com", PASSWORD).get("access_token").asText();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());

        jdbc.update("UPDATE users SET deleted_at = now() WHERE email = ?",
                "softdelete@example.com");

        // The user is resolved on every request, so this does not wait for token expiry.
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized());
    }

    // --- /me -----------------------------------------------------------------

    @Test
    @DisplayName("/me reports has_profile and never leaks the password hash")
    void meReturnsProfileFlag() throws Exception {
        register("me@example.com");
        String access = login("me@example.com", PASSWORD).get("access_token").asText();

        String body = mockMvc.perform(
                        get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.has_profile").value(false))
                .andExpect(jsonPath("$.data.email").value("me@example.com"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("argon2").doesNotContain("password");

        java.util.UUID userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", java.util.UUID.class, "me@example.com");
        jdbc.update("INSERT INTO profiles (user_id, display_name) VALUES (?, 'Alex')", userId);

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(jsonPath("$.data.has_profile").value(true));
    }

    // --- refresh / logout ----------------------------------------------------

    @Test
    @DisplayName("refresh rotates the pair and revokes the presented token")
    void refreshRotates() throws Exception {
        register("refresh@example.com");
        String refreshToken = login("refresh@example.com", PASSWORD).get("refresh_token").asText();

        String body = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("refresh_token", refreshToken))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token_type").value("bearer"))
                .andReturn().getResponse().getContentAsString();

        JsonNode rotated = json.readTree(body).get("data");
        assertThat(rotated.get("refresh_token").asText()).isNotEqualTo(refreshToken);

        // The new access token works.
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + rotated.get("access_token").asText()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("replaying a rotated refresh token is 401 and kills every session")
    void refreshReuseIsDetected() throws Exception {
        register("reuse@example.com");
        String first = login("reuse@example.com", PASSWORD).get("refresh_token").asText();
        // A second, unrelated session that must also be destroyed.
        login("reuse@example.com", PASSWORD);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("refresh_token", first))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("refresh_token", first))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message")
                        .value("Refresh token reuse detected. Please log in again."));

        java.util.UUID userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", java.util.UUID.class, "reuse@example.com");
        // Asserted against the database, not the response — the response is 401 either way.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM refresh_tokens WHERE user_id = ? "
                                + "AND revoked_at IS NULL", Long.class, userId))
                .as("every session must be revoked after a replay")
                .isZero();
    }

    @Test
    @DisplayName("logout is idempotent and revokes the token")
    void logoutIsIdempotent() throws Exception {
        register("logout@example.com");
        String refreshToken = login("logout@example.com", PASSWORD).get("refresh_token").asText();

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(
                                    java.util.Map.of("refresh_token", refreshToken))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Logged out successfully"));
        }

        // Garbage is accepted too — the caller wanted the session gone, and it is.
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("refresh_token", "not-a-token"))))
                .andExpect(status().isOk());
    }

    // --- email verification --------------------------------------------------

    @Test
    @DisplayName("verify-email marks the account verified and is single use")
    void verifyEmailFlow() throws Exception {
        register("verify@example.com");
        String token = tokenFromLatestEmail(EmailKind.VERIFICATION);

        mockMvc.perform(get("/api/v1/auth/verify-email").param("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email_verified").value(true))
                .andExpect(jsonPath("$.message").value("Email verified successfully"));

        mockMvc.perform(get("/api/v1/auth/verify-email").param("token", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message")
                        .value("Verification token has already been used"));
    }

    @Test
    @DisplayName("resend-verification requires auth and invalidates the previous link")
    void resendVerification() throws Exception {
        register("resend@example.com");
        String firstToken = tokenFromLatestEmail(EmailKind.VERIFICATION);
        String access = login("resend@example.com", PASSWORD).get("access_token").asText();

        mockMvc.perform(post("/api/v1/auth/resend-verification"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/resend-verification")
                        .header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());

        String secondToken = tokenFromLatestEmail(EmailKind.VERIFICATION);
        assertThat(secondToken).isNotEqualTo(firstToken);

        mockMvc.perform(get("/api/v1/auth/verify-email").param("token", firstToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/auth/verify-email").param("token", secondToken))
                .andExpect(status().isOk());
    }

    // --- password reset ------------------------------------------------------

    @Test
    @DisplayName("forgot-password always returns 200, even for an unknown address")
    void forgotPasswordDoesNotEnumerate() throws Exception {
        register("forgot@example.com");

        for (String email : new String[] {"forgot@example.com", "nobody-here@example.com"}) {
            mockMvc.perform(post("/api/v1/auth/forgot-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(java.util.Map.of("email", email))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(
                            "If an account exists with this email, a reset link has been sent."));
        }

        // Only the real account got an email.
        assertThat(emails.sent().stream()
                        .filter(sent -> sent.kind() == EmailKind.PASSWORD_RESET).count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("reset-password changes the password and ends every session")
    void resetPasswordRevokesSessions() throws Exception {
        register("reset@example.com");
        login("reset@example.com", PASSWORD);
        login("reset@example.com", PASSWORD);

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("email", "reset@example.com"))))
                .andExpect(status().isOk());
        String token = tokenFromLatestEmail(EmailKind.PASSWORD_RESET);

        String newPassword = "BrandNewPass456!";
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "token", token,
                                "password", newPassword,
                                "password_confirm", newPassword))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password reset successfully"));

        java.util.UUID userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", java.util.UUID.class, "reset@example.com");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM refresh_tokens WHERE user_id = ? "
                                + "AND revoked_at IS NULL", Long.class, userId))
                .as("a password change ends every session, everywhere")
                .isZero();

        // The new password works and the old one does not.
        login("reset@example.com", newPassword);
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "email", "reset@example.com", "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a reset token cannot be replayed")
    void resetTokenIsSingleUse() throws Exception {
        register("reset-replay@example.com");
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("email", "reset-replay@example.com"))))
                .andExpect(status().isOk());
        String token = tokenFromLatestEmail(EmailKind.PASSWORD_RESET);

        var payload = json.writeValueAsString(java.util.Map.of(
                "token", token, "password", "FirstChange123!",
                "password_confirm", "FirstChange123!"));
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message")
                        .value("Reset token has already been used"));
    }

    @Test
    @DisplayName("one-time tokens are stored only as hashes")
    void oneTimeTokensAreHashed() throws Exception {
        register("hashed-tokens@example.com");
        String rawToken = tokenFromLatestEmail(EmailKind.VERIFICATION);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM email_verification_tokens WHERE token_hash = ?",
                        Long.class, rawToken))
                .as("the raw token must never be stored")
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM email_verification_tokens", Long.class))
                .isEqualTo(1);
    }
}
