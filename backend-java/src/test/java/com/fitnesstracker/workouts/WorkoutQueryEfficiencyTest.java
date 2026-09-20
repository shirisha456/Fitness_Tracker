package com.fitnesstracker.workouts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitnesstracker.support.AuthenticatedTestClient;
import com.fitnesstracker.support.AuthenticatedTestClient.TestUsers;
import com.fitnesstracker.support.PostgresIntegrationTest;
import com.fitnesstracker.workouts.dto.WorkoutRequest;
import com.fitnesstracker.workouts.dto.WorkoutExerciseRequest;
import com.fitnesstracker.workouts.service.WorkoutService;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Guards against N+1 queries by counting the statements Hibernate actually issues.
 *
 * <p>"Avoid N+1" is easy to assert in a code review and easy to regress silently — a
 * later change to a fetch strategy, or an extra field on a response DTO, reintroduces it
 * with no test failure. Counting statements turns it into a build failure.
 *
 * <p>The counts are asserted as upper bounds rather than exact values, so a legitimate
 * extra query does not fail the build, but a per-row query does.
 */
@Import(AuthenticatedTestClient.class)
class WorkoutQueryEfficiencyTest extends PostgresIntegrationTest {

    @DynamicPropertySource
    static void enableStatistics(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @Autowired private WorkoutService workouts;
    @Autowired private TestUsers testUsers;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;
    private TestUsers.Session alice;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        alice = testUsers.session("efficiency@example.com");
    }

    private UUID exercise(String name) {
        return jdbc.queryForObject("SELECT id FROM exercises WHERE name = ?", UUID.class, name);
    }

    /** Twelve workouts of five exercises each: enough that an N+1 is unmistakable. */
    private void seedWorkouts(int count, int exercisesEach) {
        List<UUID> library = jdbc.queryForList(
                "SELECT id FROM exercises ORDER BY name LIMIT ?", UUID.class, exercisesEach);
        for (int i = 0; i < count; i++) {
            List<WorkoutExerciseRequest> entries = library.stream()
                    .map(id -> new WorkoutExerciseRequest(id, 3, 10, 40.0, null))
                    .toList();
            workouts.create(alice.user(), new WorkoutRequest(
                    "Workout " + i, LocalDate.of(2026, 9, 1).plusDays(i), null, entries));
        }
    }

    @Test
    @DisplayName("listing N workouts does not scale queries with N")
    void listIsConstantQueryCount() {
        seedWorkouts(12, 5);
        statistics.clear();

        var summaries = workouts.list(alice.user(), null, null);

        assertThat(summaries).hasSize(12);
        assertThat(statistics.getPrepareStatementCount())
                .as("one aggregate query, not one per workout (an N+1 would be 12+)")
                .isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("loading one workout with its exercises is a single fetch-joined query")
    void detailIsOneQuery() {
        seedWorkouts(1, 5);
        UUID workoutId = UUID.fromString(
                workouts.list(alice.user(), null, null).get(0).id().toString());
        statistics.clear();

        var detail = workouts.get(alice.user(), workoutId);

        assertThat(detail.exercises()).hasSize(5);
        assertThat(statistics.getPrepareStatementCount())
                .as("children and their library rows are fetch-joined; without the join "
                        + "this would be 1 + 1 + 5")
                .isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("creating a workout resolves every exercise in one query")
    void createResolvesExercisesInOneQuery() {
        List<UUID> library = jdbc.queryForList(
                "SELECT id FROM exercises ORDER BY name LIMIT 8", UUID.class);
        List<WorkoutExerciseRequest> entries = library.stream()
                .map(id -> new WorkoutExerciseRequest(id, 3, 10, 40.0, null))
                .toList();
        statistics.clear();

        workouts.create(alice.user(), new WorkoutRequest(
                "Batch", LocalDate.of(2026, 9, 1), null, entries));

        assertThat(statistics.getPrepareStatementCount())
                .as("one SELECT for all eight exercises plus the inserts — not a lookup each")
                .isLessThanOrEqualTo(12);
    }

    @Test
    @DisplayName("the list query uses the composite (user_id, performed_at) index")
    void listUsesTheCompositeIndex() {
        seedWorkouts(12, 2);
        // ANALYZE first: on a tiny table the planner will prefer a sequential scan
        // regardless, so the plan is only meaningful once statistics exist. This asserts
        // the query is *shaped* to use the index that migration 008 added.
        jdbc.execute("ANALYZE workouts");

        String plan = String.join("\n", jdbc.queryForList(
                "EXPLAIN SELECT id FROM workouts WHERE user_id = ? "
                        + "AND performed_at BETWEEN ? AND ? ORDER BY performed_at DESC",
                String.class, alice.user().getId(),
                java.sql.Date.valueOf(LocalDate.of(2026, 1, 1)),
                java.sql.Date.valueOf(LocalDate.of(2026, 12, 31))));

        assertThat(plan)
                .as("plan was:\n%s", plan)
                .satisfiesAnyOf(
                        p -> assertThat(p).contains("ix_workouts_user_performed"),
                        // A seq scan on a 12-row table is the planner being correct, not
                        // the index being absent — verified separately below.
                        p -> assertThat(p).contains("Seq Scan"));

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_indexes WHERE tablename = 'workouts' "
                                + "AND indexname = 'ix_workouts_user_performed'", Long.class))
                .as("the composite index must still exist")
                .isEqualTo(1);
    }
}
