package com.fitnesstracker.workouts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesstracker.support.AuthenticatedTestClient;
import com.fitnesstracker.support.AuthenticatedTestClient.TestUsers;
import com.fitnesstracker.support.PostgresIntegrationTest;
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
 * Workouts and the exercise library, against the contract in docs/api-compatibility.md.
 */
@Import(AuthenticatedTestClient.class)
class WorkoutApiTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private TestUsers testUsers;
    @Autowired private JdbcTemplate jdbc;

    private TestUsers.Session alice;
    private UUID benchId;
    private UUID squatId;

    @BeforeEach
    void setUp() {
        alice = testUsers.session("alice-workouts@example.com");
        benchId = seededExercise("Bench Press");
        squatId = seededExercise("Squat");
    }

    private UUID seededExercise(String name) {
        return jdbc.queryForObject(
                "SELECT id FROM exercises WHERE name = ?", UUID.class, name);
    }

    private String workoutPayload(Object... exerciseEntries) throws Exception {
        return json.writeValueAsString(Map.of(
                "name", "Leg day",
                "performed_at", "2026-09-01",
                "notes", "Felt strong",
                "exercises", List.of(exerciseEntries)));
    }

    private Map<String, Object> entry(UUID exerciseId, int sets, int reps, Double weight) {
        return weight == null
                ? Map.of("exercise_id", exerciseId.toString(), "sets", sets, "reps", reps)
                : Map.of("exercise_id", exerciseId.toString(), "sets", sets, "reps", reps,
                        "weight_kg", weight);
    }

    private JsonNode createWorkout(TestUsers.Session session, String body) throws Exception {
        String response = mockMvc.perform(post("/api/v1/workouts")
                        .header("Authorization", session.authorization())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("data");
    }

    // --- exercise library ----------------------------------------------------

    @Test
    @DisplayName("the seeded library is listed alphabetically and can be filtered")
    void listsExercises() throws Exception {
        mockMvc.perform(get("/api/v1/exercises").header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(39)))
                .andExpect(jsonPath("$.data[0].name").value("Barbell Row"))
                .andExpect(jsonPath("$.data[*].name", Matchers.hasItem("Bench Press")));

        mockMvc.perform(get("/api/v1/exercises").param("category", "cardio")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].category",
                        Matchers.everyItem(Matchers.is("cardio"))));
    }

    @Test
    @DisplayName("creating a custom exercise records its owner")
    void createsCustomExercise() throws Exception {
        mockMvc.perform(post("/api/v1/exercises")
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("name", "Zercher Squat", "category", "strength"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Zercher Squat"))
                .andExpect(jsonPath("$.data.category").value("strength"));

        assertThat(jdbc.queryForObject(
                        "SELECT created_by_user_id FROM exercises WHERE name = 'Zercher Squat'",
                        UUID.class))
                .as("a custom exercise is owned; a seeded one is not")
                .isEqualTo(alice.user().getId());
    }

    @Test
    @DisplayName("get-or-create is case-insensitive and still returns 201")
    void getOrCreateIsCaseInsensitive() throws Exception {
        // The quirk is deliberate: the Python endpoint returns 201 even when it returned an
        // existing row, and the exercise picker relies on it.
        mockMvc.perform(post("/api/v1/exercises")
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "bench press"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(benchId.toString()))
                .andExpect(jsonPath("$.data.name").value("Bench Press"));

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM exercises WHERE lower(name) = 'bench press'",
                        Long.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("category defaults to other when omitted")
    void categoryDefaults() throws Exception {
        mockMvc.perform(post("/api/v1/exercises")
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Sled Push"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.category").value("other"));
    }

    // --- workout CRUD --------------------------------------------------------

    @Test
    @DisplayName("create returns the detail shape with the exercise nested")
    void createsWorkout() throws Exception {
        String body = workoutPayload(entry(benchId, 3, 8, 45.0));

        mockMvc.perform(post("/api/v1/workouts")
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Leg day"))
                .andExpect(jsonPath("$.data.performed_at").value("2026-09-01"))
                .andExpect(jsonPath("$.data.notes").value("Felt strong"))
                .andExpect(jsonPath("$.data.created_at").isNotEmpty())
                .andExpect(jsonPath("$.data.updated_at").isNotEmpty())
                .andExpect(jsonPath("$.data.exercises[0].order_index").value(0))
                .andExpect(jsonPath("$.data.exercises[0].sets").value(3))
                .andExpect(jsonPath("$.data.exercises[0].reps").value(8))
                // An integer weight comes back as a float, as Python returns it.
                .andExpect(jsonPath("$.data.exercises[0].weight_kg").value(45.0))
                .andExpect(jsonPath("$.data.exercises[0].notes").doesNotExist())
                .andExpect(jsonPath("$.data.exercises[0].exercise.name").value("Bench Press"))
                .andExpect(jsonPath("$.data.exercises[0].exercise.muscle_group").value("chest"));
    }

    @Test
    @DisplayName("order_index follows array position, not the client")
    void orderIndexFollowsPosition() throws Exception {
        JsonNode created = createWorkout(alice, workoutPayload(
                entry(squatId, 5, 5, 100.0), entry(benchId, 3, 8, 45.0)));

        assertThat(created.get("exercises").get(0).get("exercise").get("name").asText())
                .isEqualTo("Squat");
        assertThat(created.get("exercises").get(0).get("order_index").asInt()).isZero();
        assertThat(created.get("exercises").get(1).get("order_index").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a workout with no weight stores null, not zero")
    void bodyweightEntryKeepsNullWeight() throws Exception {
        JsonNode created = createWorkout(alice, workoutPayload(entry(benchId, 3, 10, null)));
        assertThat(created.get("exercises").get(0).get("weight_kg").isNull()).isTrue();
    }

    @Test
    @DisplayName("list returns summaries with a counted exercise_count, newest first")
    void listReturnsSummaries() throws Exception {
        createWorkout(alice, json.writeValueAsString(Map.of(
                "name", "Older", "performed_at", "2026-08-01",
                "exercises", List.of(entry(benchId, 3, 8, 45.0)))));
        createWorkout(alice, json.writeValueAsString(Map.of(
                "name", "Newer", "performed_at", "2026-09-01",
                "exercises", List.of(entry(benchId, 3, 8, 45.0), entry(squatId, 5, 5, 100.0)))));

        mockMvc.perform(get("/api/v1/workouts").header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.data[0].name").value("Newer"))
                .andExpect(jsonPath("$.data[0].exercise_count").value(2))
                .andExpect(jsonPath("$.data[1].exercise_count").value(1))
                // The summary shape must not carry the children.
                .andExpect(jsonPath("$.data[0].exercises").doesNotExist());
    }

    @Test
    @DisplayName("list filters by date range")
    void listFiltersByDate() throws Exception {
        createWorkout(alice, json.writeValueAsString(Map.of(
                "name", "January", "performed_at", "2026-01-15", "exercises", List.of())));
        createWorkout(alice, json.writeValueAsString(Map.of(
                "name", "September", "performed_at", "2026-09-15", "exercises", List.of())));

        mockMvc.perform(get("/api/v1/workouts")
                        .param("date_from", "2026-09-01").param("date_to", "2026-09-30")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].name").value("September"));
    }

    @Test
    @DisplayName("PUT replaces the exercise collection wholesale")
    void updateReplacesExercises() throws Exception {
        JsonNode created = createWorkout(alice, workoutPayload(entry(benchId, 3, 8, 45.0)));
        UUID workoutId = UUID.fromString(created.get("id").asText());

        mockMvc.perform(put("/api/v1/workouts/" + workoutId)
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Renamed", "performed_at", "2026-09-02",
                                "exercises", List.of(entry(squatId, 5, 5, 100.0))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Renamed"))
                .andExpect(jsonPath("$.data.exercises", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data.exercises[0].exercise.name").value("Squat"))
                // Omitted notes are nulled — PUT is a replace, not a patch.
                .andExpect(jsonPath("$.data.notes").doesNotExist());

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM workout_exercises WHERE workout_id = ?",
                        Long.class, workoutId))
                .as("the replaced child rows must be deleted, not orphaned")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("DELETE returns 204 and removes the children")
    void deleteCascades() throws Exception {
        JsonNode created = createWorkout(alice, workoutPayload(entry(benchId, 3, 8, 45.0)));
        UUID workoutId = UUID.fromString(created.get("id").asText());

        mockMvc.perform(delete("/api/v1/workouts/" + workoutId)
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isNoContent())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .as("204 carries no body").isEmpty());

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM workout_exercises WHERE workout_id = ?",
                        Long.class, workoutId))
                .isZero();
    }

    @Test
    @DisplayName("an unknown exercise id is 400 naming the ids")
    void unknownExerciseIsBadRequest() throws Exception {
        UUID missing = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/workouts")
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workoutPayload(entry(missing, 3, 8, 45.0))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.error.message")
                        .value("Unknown exercise id(s): " + missing));
    }

    @Test
    @DisplayName("validation bounds match the Python schema")
    void validationBounds() throws Exception {
        mockMvc.perform(post("/api/v1/workouts")
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Bad", "performed_at", "2026-09-01",
                                "exercises", List.of(Map.of(
                                        "exercise_id", benchId.toString(),
                                        "sets", 51, "reps", 0, "weight_kg", -1))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("a missing workout is 404")
    void missingWorkoutIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/workouts/" + UUID.randomUUID())
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("Workout not found"));
    }

    @Test
    @DisplayName("every workout endpoint requires authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/workouts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/exercises")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/workouts")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // --- user isolation ------------------------------------------------------

    @Test
    @DisplayName("one user can never read, update or delete another's workout")
    void strictUserIsolation() throws Exception {
        JsonNode aliceWorkout = createWorkout(alice, workoutPayload(entry(benchId, 3, 8, 45.0)));
        UUID workoutId = UUID.fromString(aliceWorkout.get("id").asText());
        var bob = testUsers.session("bob-workouts@example.com");

        // 404, never 403 — whether the id exists is not Bob's to learn.
        mockMvc.perform(get("/api/v1/workouts/" + workoutId)
                        .header("Authorization", bob.authorization()))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/workouts/" + workoutId)
                        .header("Authorization", bob.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workoutPayload(entry(squatId, 1, 1, 1.0))))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/workouts/" + workoutId)
                        .header("Authorization", bob.authorization()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/workouts").header("Authorization", bob.authorization()))
                .andExpect(jsonPath("$.data", Matchers.hasSize(0)));

        // Alice's workout is untouched by any of that.
        mockMvc.perform(get("/api/v1/workouts/" + workoutId)
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exercises[0].exercise.name").value("Bench Press"));
    }
}
