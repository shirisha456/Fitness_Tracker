package com.fitnesstracker.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fitnesstracker.auth.dto.TokenResponse;
import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.auth.entity.UserRole;
import com.fitnesstracker.auth.repository.UserRepository;
import com.fitnesstracker.auth.service.AuthService;
import com.fitnesstracker.auth.service.TokenRotationService;
import com.fitnesstracker.common.exception.AppException;
import com.fitnesstracker.common.exception.RefreshTokenReuseException;
import com.fitnesstracker.support.NaiveTokenRotation;
import com.fitnesstracker.support.NaiveTokenRotation.NaiveTokenRotationService;
import com.fitnesstracker.support.PostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Risk R1 — refresh-token reuse detection must survive the transaction rollback caused by
 * the 401 it raises.
 *
 * <p>Every assertion about revocation is made with {@link JdbcTemplate} against the
 * database, deliberately bypassing the JPA persistence context and its first-level cache.
 * A test that only checked the thrown exception, or that read back through the same
 * repository inside the same transaction, would pass against an implementation that
 * silently revokes nothing — which is exactly the regression this guards.
 *
 * <p>The reference implementation solved this by committing before raising; see
 * {@code app/modules/auth/service.py::refresh_tokens} and commit 6c32800, where this same
 * bug was found and fixed once already.
 */
@Import(NaiveTokenRotation.class)
class RefreshTokenReuseTransactionSpikeTest extends PostgresIntegrationTest {

    @Autowired private TokenRotationService rotation;
    @Autowired private NaiveTokenRotationService naiveRotation;
    @Autowired private AuthService authService;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private record Session(UUID userId, String replayedToken, List<UUID> siblingJtis) {}

    /**
     * A user with one already-rotated (therefore revoked) token — the one an attacker
     * replays — and three still-active sibling sessions, as a multi-device user would have.
     */
    private Session givenSessionFamily(String email) {
        User user = users.saveAndFlush(new User(email, "hash", UserRole.USER));

        TokenResponse first = authService.issueTokenPair(user, "device-a", "10.0.0.1");
        // Rotating it legitimately leaves the original revoked, which is the precondition
        // for reuse: a token that was already exchanged.
        rotation.rotate(first.refreshToken(), "device-a", "10.0.0.1");

        List<UUID> siblings = new ArrayList<>();
        for (String device : new String[] {"device-b", "device-c", "device-d"}) {
            TokenResponse pair = authService.issueTokenPair(user, device, "10.0.0.2");
            siblings.add(jtiOf(pair.refreshToken()));
        }
        return new Session(user.getId(), first.refreshToken(), siblings);
    }

    private UUID jtiOf(String rawToken) {
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(rawToken.split("\\.")[1]), java.nio.charset.StandardCharsets.UTF_8);
        return UUID.fromString(payload.replaceAll(".*\"jti\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
    }

    /** Reads revocation state straight from PostgreSQL — no Hibernate, no caching. */
    private boolean isRevokedInDatabase(UUID jti) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM refresh_tokens WHERE id = ?",
                Boolean.class, jti));
    }

    private long activeTokenCount(UUID userId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Long.class, userId);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("reuse revokes every sibling token, and the revocation is committed")
    void reuseRevokesSiblingsDurably() {
        Session session = givenSessionFamily("reuse-durable@example.com");
        // 3 siblings + the replacement issued by the legitimate rotation.
        assertThat(activeTokenCount(session.userId())).isEqualTo(4);

        assertThatThrownBy(() -> rotation.rotate(session.replayedToken(), "attacker", "10.9.9.9"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("reuse detected");

        // The claim under test: the API failed, and the database still changed.
        for (UUID sibling : session.siblingJtis()) {
            assertThat(isRevokedInDatabase(sibling))
                    .as("sibling token %s must be revoked in the database", sibling)
                    .isTrue();
        }
        assertThat(activeTokenCount(session.userId()))
                .as("no active token may remain for a user whose token was replayed")
                .isZero();
    }

    @Test
    @DisplayName("reuse does not leave a newly issued replacement token behind")
    void reuseIssuesNothing() {
        Session session = givenSessionFamily("reuse-no-issue@example.com");
        long before = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ?",
                Long.class, session.userId());

        assertThatThrownBy(() -> rotation.rotate(session.replayedToken(), null, null))
                .isInstanceOf(AppException.class);

        long after = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ?",
                Long.class, session.userId());
        assertThat(after)
                .as("the outer transaction must still roll back — only the revocation survives")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("the naive approach loses the revocation — the regression this guards")
    void naiveImplementationSilentlyLosesTheRevocation() {
        Session session = givenSessionFamily("reuse-naive@example.com");

        assertThatThrownBy(() -> naiveRotation.rotate(session.replayedToken()))
                .isInstanceOf(RefreshTokenReuseException.class);

        // Identical HTTP behaviour, opposite security outcome: the exception rolled the
        // revocation back with it. Asserted explicitly so that anyone who "simplifies"
        // TokenRevocationService by inlining it gets a failing test rather than a silent
        // downgrade. The naive version lives in test code only — see NaiveTokenRotation.
        for (UUID sibling : session.siblingJtis()) {
            assertThat(isRevokedInDatabase(sibling))
                    .as("demonstrating the hazard: sibling %s survives the naive path", sibling)
                    .isFalse();
        }
        assertThat(activeTokenCount(session.userId())).isEqualTo(4);
    }

    @Test
    @DisplayName("a normal rotation revokes only the presented token and issues one replacement")
    void normalRotationIsUnaffected() {
        User user = users.saveAndFlush(new User("rotate-ok@example.com", "hash", UserRole.USER));
        TokenResponse presented = authService.issueTokenPair(user, "device-a", "10.0.0.1");
        TokenResponse other = authService.issueTokenPair(user, "device-b", "10.0.0.2");

        TokenResponse rotated = rotation.rotate(presented.refreshToken(), "device-a", "10.0.0.1");

        assertThat(isRevokedInDatabase(jtiOf(presented.refreshToken()))).isTrue();
        assertThat(isRevokedInDatabase(jtiOf(other.refreshToken())))
                .as("rotation must not touch other sessions")
                .isFalse();
        assertThat(isRevokedInDatabase(jtiOf(rotated.refreshToken()))).isFalse();
        assertThat(activeTokenCount(user.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("revocation is scoped to the affected user only")
    void revocationDoesNotCrossUsers() {
        Session victim = givenSessionFamily("reuse-victim@example.com");
        Session bystander = givenSessionFamily("reuse-bystander@example.com");

        assertThatThrownBy(() -> rotation.rotate(victim.replayedToken(), null, null))
                .isInstanceOf(AppException.class);

        assertThat(activeTokenCount(victim.userId())).isZero();
        assertThat(activeTokenCount(bystander.userId()))
                .as("another user's sessions must be untouched")
                .isEqualTo(4);
    }
}
