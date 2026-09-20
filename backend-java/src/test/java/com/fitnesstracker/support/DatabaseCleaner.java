package com.fitnesstracker.support;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resets the shared container to its just-migrated state between tests.
 *
 * <p>The PostgreSQL container is shared across every test class in the JVM, so without
 * this one test's rows leak into the next one's assertions. Rolling back a transaction
 * would be cheaper, but several tests deliberately assert on <em>committed</em> state —
 * the refresh-token reuse spike exists precisely to prove that a separate transaction
 * committed — so the data really has to be deleted.
 *
 * <p>Deliberately {@code DELETE} rather than {@code TRUNCATE ... CASCADE}. Truncating
 * {@code users} with CASCADE also truncates {@code exercises}, because
 * {@code exercises.created_by_user_id} references it — which silently destroys the 39
 * seeded library rows that the baseline migration inserts. {@code DELETE} follows the
 * declared FK actions instead: user-owned rows cascade away, and a seeded exercise
 * (whose {@code created_by_user_id} is already NULL) is untouched.
 */
@Component
public class DatabaseCleaner {

    private final JdbcTemplate jdbc;
    private volatile List<UUID> seededExerciseIds;

    public DatabaseCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void reset() {
        captureSeedOnce();

        // Every user-owned table declares ON DELETE CASCADE, so one delete clears them all.
        jdbc.update("DELETE FROM users");

        // exercises is a shared library, not user data: restore it rather than empty it.
        if (!seededExerciseIds.isEmpty()) {
            String placeholders =
                    seededExerciseIds.stream().map(id -> "?").collect(Collectors.joining(", "));
            jdbc.update(
                    "DELETE FROM exercises WHERE id NOT IN (" + placeholders + ")",
                    seededExerciseIds.toArray());
        }
    }

    /** Runs before the first test, so it sees the library exactly as the migration left it. */
    private void captureSeedOnce() {
        if (seededExerciseIds == null) {
            synchronized (this) {
                if (seededExerciseIds == null) {
                    seededExerciseIds = jdbc.queryForList("SELECT id FROM exercises", UUID.class);
                }
            }
        }
    }
}
