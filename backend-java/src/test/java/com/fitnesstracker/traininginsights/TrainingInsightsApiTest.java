package com.fitnesstracker.traininginsights;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesstracker.support.AuthenticatedTestClient;
import com.fitnesstracker.support.AuthenticatedTestClient.TestUsers;
import com.fitnesstracker.support.PostgresIntegrationTest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The training-insight endpoints over real data.
 *
 * <p>The engine itself is covered exhaustively by {@link TrainingAnalyticsFixtureTest}
 * against the shared Python fixtures; this covers what that cannot — routing, ownership,
 * the 404-vs-insufficient-data distinction, and analytics computed from rows that actually
 * went through the workout API.
 */
@Import(AuthenticatedTestClient.class)
class TrainingInsightsApiTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private TestUsers testUsers;
    @Autowired private JdbcTemplate jdbc;

    private TestUsers.Session alice;
    private UUID benchId;
    private UUID runningId;

    /** The service derives "today" from UTC, so fixtures must be relative to that. */
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        alice = testUsers.session("alice-training@example.com");
        benchId = exerciseId("Bench Press");
        runningId = exerciseId("Running");
    }

    private UUID exerciseId(String name) {
        return jdbc.queryForObject("SELECT id FROM exercises WHERE name = ?", UUID.class, name);
    }

    private void logWorkout(TestUsers.Session session, UUID exercise, int daysAgo,
                            int sets, int reps, Double weight, String notes) throws Exception {
        Map<String, Object> entry = new java.util.HashMap<>();
        entry.put("exercise_id", exercise.toString());
        entry.put("sets", sets);
        entry.put("reps", reps);
        if (weight != null) {
            entry.put("weight_kg", weight);
        }
        if (notes != null) {
            entry.put("notes", notes);
        }
        mockMvc.perform(post("/api/v1/workouts")
                        .header("Authorization", session.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Session",
                                "performed_at", today.minusDays(daysAgo).toString(),
                                "exercises", List.of(entry)))))
                .andExpect(status().isCreated());
    }

    private void logBench(int daysAgo, double weight) throws Exception {
        logWorkout(alice, benchId, daysAgo, 3, 8, weight, null);
    }

    // --- classification over real rows ---------------------------------------

    @Test
    @DisplayName("a rising load is classified progressing, with its evidence")
    void progressingEndToEnd() throws Exception {
        logBench(21, 40.0);
        logBench(14, 42.5);
        logBench(7, 45.0);

        mockMvc.perform(get("/api/v1/training/exercises/" + benchId + "/insights")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.classification").value("progressing"))
                .andExpect(jsonPath("$.data.metric_basis").value("load"))
                .andExpect(jsonPath("$.data.sessions_analyzed").value(3))
                .andExpect(jsonPath("$.data.current_weight_kg").value(45.0))
                .andExpect(jsonPath("$.data.previous_weight_kg").value(42.5))
                .andExpect(jsonPath("$.data.primary_change_percent").value(12.5))
                .andExpect(jsonPath("$.data.plateau_detected").value(false))
                .andExpect(jsonPath("$.data.suggestion.kind").value("maintain"))
                .andExpect(jsonPath("$.data.explanation_source").value("deterministic"))
                .andExpect(jsonPath("$.data.evidence[0]").value(
                        "Top load moved from 40 kg to 45 kg across 3 sessions (+12.5%)"))
                .andExpect(jsonPath("$.data.date_range.from")
                        .value(today.minusDays(21).toString()))
                .andExpect(jsonPath("$.data.date_range.to").value(today.minusDays(7).toString()));
    }

    @Test
    @DisplayName("four flat sessions over three weeks are a possible plateau")
    void plateauEndToEnd() throws Exception {
        for (int daysAgo : new int[] {28, 21, 14, 7}) {
            logBench(daysAgo, 60.0);
        }

        mockMvc.perform(get("/api/v1/training/exercises/" + benchId + "/insights")
                        .header("Authorization", alice.authorization()))
                .andExpect(jsonPath("$.data.classification").value("possible_plateau"))
                .andExpect(jsonPath("$.data.plateau_detected").value(true))
                .andExpect(jsonPath("$.data.suggestion.kind").value("review_exercise"))
                .andExpect(jsonPath("$.data.suggestion.suggested_load_kg").doesNotExist());
    }

    @Test
    @DisplayName("cardio is not_applicable, not a plateau")
    void cardioIsNotApplicable() throws Exception {
        for (int daysAgo : new int[] {21, 14, 7, 1}) {
            logWorkout(alice, runningId, daysAgo, 1, 1, null, null);
        }

        mockMvc.perform(get("/api/v1/training/exercises/" + runningId + "/insights")
                        .header("Authorization", alice.authorization()))
                .andExpect(jsonPath("$.data.classification").value("not_applicable"))
                .andExpect(jsonPath("$.data.metric_basis").value("none"))
                .andExpect(jsonPath("$.data.sessions_analyzed").value(0));
    }

    @Test
    @DisplayName("a future-dated workout is excluded from the analysis")
    void futureWorkoutsExcluded() throws Exception {
        logBench(21, 40.0);
        logBench(14, 42.5);
        logBench(7, 45.0);
        logBench(-7, 100.0);

        mockMvc.perform(get("/api/v1/training/exercises/" + benchId + "/insights")
                        .header("Authorization", alice.authorization()))
                .andExpect(jsonPath("$.data.current_weight_kg").value(45.0))
                .andExpect(jsonPath("$.data.sessions_analyzed").value(3));
    }

    @Test
    @DisplayName("a pain note withholds the prescription without hiding the verdict")
    void medicalNoteSuppressesSuggestion() throws Exception {
        logBench(21, 100.0);
        logBench(14, 100.5);
        logWorkout(alice, benchId, 7, 3, 8, 101.0, "Sharp pain in my left shoulder");

        String body = mockMvc.perform(
                        get("/api/v1/training/exercises/" + benchId + "/insights")
                        .header("Authorization", alice.authorization()))
                .andExpect(jsonPath("$.data.classification").value("stable"))
                .andExpect(jsonPath("$.data.suggestion.kind").value("consult_professional"))
                .andExpect(jsonPath("$.data.suggestion.suggested_load_kg").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .as("the note text must never be echoed back")
                .doesNotContain("shoulder");
    }

    // --- history and personal bests -------------------------------------------

    @Test
    @DisplayName("history returns the session table and records computed over all of it")
    void historyAndPersonalBests() throws Exception {
        logWorkout(alice, benchId, 16, 3, 8, 40.0, null);
        logWorkout(alice, benchId, 12, 3, 8, 42.5, null);
        logWorkout(alice, benchId, 8, 5, 8, 45.0, null);

        mockMvc.perform(get("/api/v1/training/exercises/" + benchId + "/history")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessions", Matchers.hasSize(3)))
                .andExpect(jsonPath("$.data.sessions[0].top_weight_kg").value(40.0))
                .andExpect(jsonPath("$.data.sessions[0].total_sets").value(3))
                .andExpect(jsonPath("$.data.sessions[0].reps_per_set").value(8))
                .andExpect(jsonPath("$.data.sessions[0].total_reps").value(24))
                .andExpect(jsonPath("$.data.sessions[0].volume_kg").value(960.0))
                .andExpect(jsonPath("$.data.sessions[2].volume_kg").value(1800.0))
                .andExpect(jsonPath("$.data.personal_bests.heaviest_weight_kg").value(45.0))
                .andExpect(jsonPath("$.data.personal_bests.best_session_volume_kg").value(1800.0))
                .andExpect(jsonPath("$.data.personal_bests.most_reps_at_heaviest_weight")
                        .value(40));
    }

    @Test
    @DisplayName("limit trims the table without losing a record set outside it")
    void historyLimitKeepsRecords() throws Exception {
        logWorkout(alice, benchId, 30, 5, 8, 80.0, null);
        logBench(10, 40.0);
        logBench(5, 42.5);

        mockMvc.perform(get("/api/v1/training/exercises/" + benchId + "/history")
                        .param("limit", "2")
                        .header("Authorization", alice.authorization()))
                .andExpect(jsonPath("$.data.sessions", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.data.personal_bests.heaviest_weight_kg").value(80.0));
    }

    @Test
    @DisplayName("history of an exercise never logged is empty, not an error")
    void emptyHistoryIsNotAnError() throws Exception {
        mockMvc.perform(get("/api/v1/training/exercises/" + benchId + "/history")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessions", Matchers.hasSize(0)))
                .andExpect(jsonPath("$.data.personal_bests.heaviest_weight_kg").doesNotExist())
                .andExpect(jsonPath("$.data.date_range").doesNotExist());
    }

    // --- overview and recommendations -----------------------------------------

    @Test
    @DisplayName("the overview reports consistency, weighted volume and per-exercise verdicts")
    void overviewAggregates() throws Exception {
        for (int daysAgo : new int[] {1, 3, 5}) {
            logWorkout(alice, benchId, daysAgo, 3, 10, 50.0, null);
        }
        for (int daysAgo : new int[] {8, 10}) {
            logWorkout(alice, benchId, daysAgo, 3, 10, 40.0, null);
        }

        mockMvc.perform(get("/api/v1/training/overview")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.generated_on").value(today.toString()))
                .andExpect(jsonPath("$.data.consistency.workouts_last_7_days").value(3))
                .andExpect(jsonPath("$.data.consistency.workouts_previous_7_days").value(2))
                .andExpect(jsonPath("$.data.consistency.change").value(1))
                .andExpect(jsonPath("$.data.volume.current_7_day_volume_kg").value(4500.0))
                .andExpect(jsonPath("$.data.volume.previous_7_day_volume_kg").value(2400.0))
                .andExpect(jsonPath("$.data.volume.change_percent").value(87.5))
                .andExpect(jsonPath("$.data.exercises", Matchers.hasSize(1)));
    }

    @Test
    @DisplayName("a new user's overview is empty but well-formed")
    void overviewForNewUser() throws Exception {
        mockMvc.perform(get("/api/v1/training/overview")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exercises", Matchers.hasSize(0)))
                .andExpect(jsonPath("$.data.consistency.workouts_last_7_days").value(0))
                .andExpect(jsonPath("$.data.volume.current_7_day_volume_kg").value(0.0))
                .andExpect(jsonPath("$.data.volume.change_percent").doesNotExist());
    }

    @Test
    @DisplayName("recommendations surface only actionable suggestions")
    void recommendationsAreActionableOnly() throws Exception {
        UUID squatId = exerciseId("Squat");
        for (int daysAgo : new int[] {28, 21, 14, 7}) {
            logWorkout(alice, squatId, daysAgo, 3, 8, 60.0, null);
        }
        logBench(21, 40.0);
        logBench(14, 42.5);
        logBench(7, 45.0);

        mockMvc.perform(get("/api/v1/training/recommendations")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recommendations[*].exercise.name",
                        Matchers.hasItem("Squat")))
                // Bench is progressing -> "maintain", which is true but not actionable.
                .andExpect(jsonPath("$.data.recommendations[*].exercise.name",
                        Matchers.not(Matchers.hasItem("Bench Press"))))
                .andExpect(jsonPath("$.data.recommendations[0].evidence",
                        Matchers.not(Matchers.empty())));
    }

    // --- routing, ownership, auth ---------------------------------------------

    @Test
    @DisplayName("an unknown exercise is 404; one never logged is insufficient_data")
    void unknownVersusUnlogged() throws Exception {
        mockMvc.perform(get("/api/v1/training/exercises/" + UUID.randomUUID() + "/insights")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        mockMvc.perform(get("/api/v1/training/exercises/" + benchId + "/insights")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.classification").value("insufficient_data"))
                .andExpect(jsonPath("$.data.sessions_analyzed").value(0));
    }

    @Test
    @DisplayName("one user's training history never reaches another")
    void strictUserIsolation() throws Exception {
        logBench(21, 40.0);
        logBench(14, 42.5);
        logBench(7, 45.0);
        var bob = testUsers.session("bob-training@example.com");

        mockMvc.perform(get("/api/v1/training/exercises/" + benchId + "/insights")
                        .header("Authorization", bob.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.classification").value("insufficient_data"))
                .andExpect(jsonPath("$.data.sessions_analyzed").value(0))
                .andExpect(jsonPath("$.data.current_weight_kg").doesNotExist());

        mockMvc.perform(get("/api/v1/training/exercises/" + benchId + "/history")
                        .header("Authorization", bob.authorization()))
                .andExpect(jsonPath("$.data.sessions", Matchers.hasSize(0)))
                .andExpect(jsonPath("$.data.personal_bests.heaviest_weight_kg").doesNotExist());

        mockMvc.perform(get("/api/v1/training/overview")
                        .header("Authorization", bob.authorization()))
                .andExpect(jsonPath("$.data.exercises", Matchers.hasSize(0)))
                .andExpect(jsonPath("$.data.volume.current_7_day_volume_kg").value(0.0));

        mockMvc.perform(get("/api/v1/training/recommendations")
                        .header("Authorization", bob.authorization()))
                .andExpect(jsonPath("$.data.recommendations", Matchers.hasSize(0)));
    }

    @Test
    @DisplayName("a custom exercise behaves like any other strength exercise")
    void customExerciseWorks() throws Exception {
        String created = mockMvc.perform(post("/api/v1/exercises")
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("name", "Zercher Squat", "category", "strength"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID customId = UUID.fromString(json.readTree(created).get("data").get("id").asText());

        logWorkout(alice, customId, 21, 3, 8, 20.0, null);
        logWorkout(alice, customId, 14, 3, 8, 22.5, null);
        logWorkout(alice, customId, 7, 3, 8, 25.0, null);

        mockMvc.perform(get("/api/v1/training/exercises/" + customId + "/insights")
                        .header("Authorization", alice.authorization()))
                .andExpect(jsonPath("$.data.classification").value("progressing"))
                .andExpect(jsonPath("$.data.exercise.name").value("Zercher Squat"));
    }

    @Test
    @DisplayName("every training endpoint requires authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/training/overview")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/training/recommendations"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/training/exercises/" + benchId + "/insights"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/training/exercises/" + benchId + "/history"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("limit is bounded to the documented 1-50 range")
    void limitIsBounded() throws Exception {
        mockMvc.perform(get("/api/v1/training/exercises/" + benchId + "/history")
                        .param("limit", "0")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/training/exercises/" + benchId + "/history")
                        .param("limit", "51")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isBadRequest());
    }
}
