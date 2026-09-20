package com.fitnesstracker.progress;

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
import java.util.HashMap;
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

/** Body measurements and goals, against docs/api-compatibility.md. */
@Import(AuthenticatedTestClient.class)
class ProgressApiTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private TestUsers testUsers;
    @Autowired private JdbcTemplate jdbc;

    private TestUsers.Session alice;

    @BeforeEach
    void setUp() {
        alice = testUsers.session("alice-progress@example.com");
    }

    private Map<String, Object> measurement(String day, double weight) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("recorded_at", day);
        payload.put("weight_kg", weight);
        payload.put("body_fat_pct", 18.5);
        payload.put("waist_cm", 82.0);
        payload.put("notes", "morning");
        return payload;
    }

    /** Named create(), not post(), so it does not shadow the static MockMvc builder. */
    private JsonNode create(String path, Object body) throws Exception {
        String response = mockMvc.perform(post("/api/v1/" + path)
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("data");
    }

    // --- measurements --------------------------------------------------------

    @Test
    @DisplayName("create returns the measurement with all recorded fields")
    void createsMeasurement() throws Exception {
        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(measurement("2026-09-10", 82.5))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.recorded_at").value("2026-09-10"))
                .andExpect(jsonPath("$.data.weight_kg").value(82.5))
                .andExpect(jsonPath("$.data.body_fat_pct").value(18.5))
                .andExpect(jsonPath("$.data.waist_cm").value(82.0))
                .andExpect(jsonPath("$.data.chest_cm").doesNotExist())
                .andExpect(jsonPath("$.data.notes").value("morning"))
                .andExpect(jsonPath("$.data.created_at").isNotEmpty());
    }

    @Test
    @DisplayName("posting twice for one day upserts in place and still returns 201")
    void sameDayUpserts() throws Exception {
        JsonNode first = create("measurements", measurement("2026-09-10", 82.5));
        JsonNode second = create("measurements", Map.of(
                "recorded_at", "2026-09-10", "weight_kg", 81.0));

        assertThat(second.get("id").asText())
                .as("the same row is updated, not a second one inserted")
                .isEqualTo(first.get("id").asText());
        assertThat(second.get("weight_kg").asDouble()).isEqualTo(81.0);
        // Full replace: the fields omitted the second time are nulled.
        assertThat(second.get("body_fat_pct").isNull()).isTrue();

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM body_measurements WHERE user_id = ?",
                        Long.class, alice.user().getId()))
                .as("UNIQUE(user_id, recorded_at) allows one check-in per day")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("measurements sort newest first and filter by date range")
    void listsAndFilters() throws Exception {
        create("measurements", measurement("2026-09-01", 84.0));
        create("measurements", measurement("2026-09-10", 82.5));
        create("measurements", measurement("2026-01-05", 90.0));

        mockMvc.perform(get("/api/v1/measurements")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(3)))
                .andExpect(jsonPath("$.data[0].recorded_at").value("2026-09-10"));

        mockMvc.perform(get("/api/v1/measurements")
                        .param("date_from", "2026-09-01").param("date_to", "2026-09-30")
                        .header("Authorization", alice.authorization()))
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)));
    }

    @Test
    @DisplayName("measurement update and delete work and 404 when missing")
    void updateAndDelete() throws Exception {
        UUID id = UUID.fromString(create("measurements", measurement("2026-09-10", 82.5))
                .get("id").asText());

        mockMvc.perform(put("/api/v1/measurements/" + id)
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "recorded_at", "2026-09-11", "weight_kg", 80.0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weight_kg").value(80.0))
                .andExpect(jsonPath("$.data.recorded_at").value("2026-09-11"))
                .andExpect(jsonPath("$.data.notes").doesNotExist());

        mockMvc.perform(delete("/api/v1/measurements/" + id)
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/measurements/" + id)
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("Measurement not found"));
    }

    @Test
    @DisplayName("measurement bounds match the Python schema, including the > 0 / >= 0 split")
    void measurementBounds() throws Exception {
        // weight must be strictly positive; body fat may be exactly zero.
        for (Map<String, ?> invalid : java.util.List.<Map<String, ?>>of(
                Map.of("recorded_at", "2026-09-10", "weight_kg", 0),
                Map.of("recorded_at", "2026-09-10", "weight_kg", 501),
                Map.of("recorded_at", "2026-09-10", "body_fat_pct", 101),
                Map.of("recorded_at", "2026-09-10", "arm_cm", 101),
                Map.of("recorded_at", "2026-09-10", "waist_cm", 301))) {
            mockMvc.perform(post("/api/v1/measurements")
                            .header("Authorization", alice.authorization())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest());
        }

        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "recorded_at", "2026-09-12", "body_fat_pct", 0))))
                .andExpect(status().isCreated());
    }

    // --- goals ---------------------------------------------------------------

    @Test
    @DisplayName("goals are created active and listed newest first")
    void goalLifecycle() throws Exception {
        JsonNode goal = create("goals", Map.of(
                "title", "Reach 78kg", "target_weight_kg", 78.0, "target_date", "2026-12-31"));

        assertThat(goal.get("status").asText())
                .as("a new goal is active; status is not settable on create")
                .isEqualTo("active");

        create("goals", Map.of("title", "Second goal"));
        mockMvc.perform(get("/api/v1/goals").header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.data[0].title").value("Second goal"));

        UUID goalId = UUID.fromString(goal.get("id").asText());
        mockMvc.perform(put("/api/v1/goals/" + goalId)
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "title", "Reach 78kg", "status", "achieved"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("achieved"))
                .andExpect(jsonPath("$.data.target_weight_kg").doesNotExist());

        mockMvc.perform(delete("/api/v1/goals/" + goalId)
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("goal status omitted on update defaults back to active")
    void goalStatusDefaults() throws Exception {
        UUID goalId = UUID.fromString(create("goals", Map.of("title", "Goal")).get("id").asText());

        mockMvc.perform(put("/api/v1/goals/" + goalId)
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", "Renamed"))))
                .andExpect(jsonPath("$.data.status").value("active"));
    }

    @Test
    @DisplayName("an unknown goal status is rejected, not coerced")
    void invalidGoalStatusRejected() throws Exception {
        UUID goalId = UUID.fromString(create("goals", Map.of("title", "Goal")).get("id").asText());

        mockMvc.perform(put("/api/v1/goals/" + goalId)
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "title", "Goal", "status", "not-a-status"))))
                .andExpect(status().isBadRequest());
    }

    // --- isolation -----------------------------------------------------------

    @Test
    @DisplayName("one user never sees or mutates another's progress data")
    void strictUserIsolation() throws Exception {
        UUID measurementId = UUID.fromString(
                create("measurements", measurement("2026-09-10", 82.5)).get("id").asText());
        UUID goalId = UUID.fromString(create("goals", Map.of("title", "Private")).get("id").asText());
        var bob = testUsers.session("bob-progress@example.com");

        mockMvc.perform(get("/api/v1/measurements/" + measurementId)
                        .header("Authorization", bob.authorization()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/measurements/" + measurementId)
                        .header("Authorization", bob.authorization()))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/goals/" + goalId)
                        .header("Authorization", bob.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", "Hijacked"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/goals/" + goalId)
                        .header("Authorization", bob.authorization()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/measurements").header("Authorization", bob.authorization()))
                .andExpect(jsonPath("$.data", Matchers.hasSize(0)));
        mockMvc.perform(get("/api/v1/goals").header("Authorization", bob.authorization()))
                .andExpect(jsonPath("$.data", Matchers.hasSize(0)));

        // Bob can keep his own same-day measurement: the unique constraint is per user.
        mockMvc.perform(post("/api/v1/measurements")
                        .header("Authorization", bob.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(measurement("2026-09-10", 70.0))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("progress endpoints require authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/measurements")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/goals")).andExpect(status().isUnauthorized());
    }
}
