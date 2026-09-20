package com.fitnesstracker.spike;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.auth.entity.UserRole;
import com.fitnesstracker.auth.repository.UserRepository;
import com.fitnesstracker.support.PostgresIntegrationTest;
import com.fitnesstracker.workouts.entity.Exercise;
import com.fitnesstracker.workouts.entity.ExerciseCategory;
import com.fitnesstracker.workouts.repository.ExerciseRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Risk R3 — native PostgreSQL enum columns under Hibernate.
 *
 * <p>Five columns in the existing schema are {@code CREATE TYPE ... AS ENUM} types rather
 * than varchar, and their labels are lowercase while Java constants are uppercase. This
 * verifies schema validation, insert, read and query for that mapping, and asserts the
 * value actually stored in PostgreSQL — not merely what Hibernate hands back.
 */
class EnumMappingSpikeTest extends PostgresIntegrationTest {

    @Autowired private UserRepository users;
    @Autowired private ExerciseRepository exercises;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("ddl-auto=validate accepts the entity mapping against native enum columns")
    void schemaValidationPasses() {
        // Reaching this point at all means the context started, which means Hibernate's
        // schema validator accepted every entity against the Flyway-built schema.
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("the column really is a native enum type, not varchar")
    void columnsAreNativeEnums() {
        String userRoleType = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'users' AND column_name = 'role'",
                String.class);
        String categoryType = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = 'exercises' AND column_name = 'category'",
                String.class);

        assertThat(userRoleType).isEqualTo("USER-DEFINED");
        assertThat(categoryType).isEqualTo("USER-DEFINED");
    }

    @Test
    @DisplayName("insert writes the lowercase database label, not the Java constant name")
    void insertWritesTheDatabaseLabel() {
        User saved = users.saveAndFlush(
                new User("enum-insert@example.com", "hash", UserRole.ADMIN));

        String stored = jdbc.queryForObject(
                "SELECT role::text FROM users WHERE id = ?", String.class, saved.getId());

        // If this reads "ADMIN", the mapping is writing Enum.name() and the database
        // label set has silently been redefined.
        assertThat(stored).isEqualTo("admin");
    }

    @Test
    @DisplayName("read maps the lowercase label back to the Java constant")
    void readMapsBackToConstant() {
        UUID id = users.saveAndFlush(
                new User("enum-read@example.com", "hash", UserRole.USER)).getId();

        User reloaded = users.findById(id).orElseThrow();

        assertThat(reloaded.getRole()).isEqualTo(UserRole.USER);
        assertThat(reloaded.getRole().getValue()).isEqualTo("user");
    }

    @Test
    @DisplayName("a row written outside Hibernate reads back correctly")
    void readsRowsWrittenByOtherClients() {
        // Simulates a row the previous implementation wrote during the side-by-side period.
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, 'admin')",
                id, "written-by-python@example.com", "hash");

        User loaded = users.findById(id).orElseThrow();

        assertThat(loaded.getRole()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    @DisplayName("derived queries filter on an enum column")
    void derivedQueryFiltersByEnum() {
        users.saveAndFlush(new User("q-admin@example.com", "h", UserRole.ADMIN));
        users.saveAndFlush(new User("q-user1@example.com", "h", UserRole.USER));
        users.saveAndFlush(new User("q-user2@example.com", "h", UserRole.USER));

        List<User> admins = users.findByRole(UserRole.ADMIN);

        assertThat(admins).extracting(User::getEmail).contains("q-admin@example.com");
        assertThat(admins).allMatch(u -> u.getRole() == UserRole.ADMIN);
        assertThat(users.countByRole(UserRole.USER)).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("JPQL and derived queries agree on the second enum type")
    void secondEnumTypeQueriesConsistently() {
        exercises.saveAndFlush(
                new Exercise("Spike Bench", ExerciseCategory.STRENGTH, "chest", "barbell"));
        exercises.saveAndFlush(new Exercise("Spike Jog", ExerciseCategory.CARDIO, "legs", null));

        List<Exercise> derived = exercises.findByCategoryOrderByName(ExerciseCategory.STRENGTH);
        List<Exercise> jpql = exercises.findByCategoryJpql(ExerciseCategory.STRENGTH);

        assertThat(derived).extracting(Exercise::getName).contains("Spike Bench");
        assertThat(derived).noneMatch(e -> e.getCategory() == ExerciseCategory.CARDIO);
        assertThat(jpql).hasSameSizeAs(derived);

        String stored = jdbc.queryForObject(
                "SELECT category::text FROM exercises WHERE name = 'Spike Jog'", String.class);
        assertThat(stored).isEqualTo("cardio");
    }

    @Test
    @DisplayName("updating an enum field persists the new label")
    void updatePersistsNewLabel() {
        UUID id = users.saveAndFlush(
                new User("enum-update@example.com", "h", UserRole.USER)).getId();

        User loaded = users.findById(id).orElseThrow();
        loaded.setRole(UserRole.ADMIN);
        users.saveAndFlush(loaded);

        assertThat(jdbc.queryForObject(
                        "SELECT role::text FROM users WHERE id = ?", String.class, id))
                .isEqualTo("admin");
    }
}
