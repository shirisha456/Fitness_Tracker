package com.fitnesstracker.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesstracker.ai.provider.AiProviderException;
import com.fitnesstracker.support.AuthenticatedTestClient;
import com.fitnesstracker.support.AuthenticatedTestClient.TestUsers;
import com.fitnesstracker.support.FakeAiProvider;
import com.fitnesstracker.support.FakeAiProvider.ScriptedProvider;
import com.fitnesstracker.support.PostgresIntegrationTest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The boundary between computed facts and generated prose.
 *
 * <p>The claim under test is narrow and important: asking the AI to explain an insight can
 * change the wording and nothing else. Every metric, the classification and the evidence
 * must be byte-identical with and without {@code explain=true}, and an AI failure must
 * leave a 200 with the deterministic text.
 */
@Import({AuthenticatedTestClient.class, FakeAiProvider.class})
class TrainingInsightExplanationTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private TestUsers testUsers;
    @Autowired private ScriptedProvider provider;
    @Autowired private JdbcTemplate jdbc;

    private TestUsers.Session alice;
    private UUID benchId;

    @BeforeEach
    void setUp() throws Exception {
        provider.reset();
        alice = testUsers.session("alice-explain@example.com");
        benchId = jdbc.queryForObject(
                "SELECT id FROM exercises WHERE name = 'Bench Press'", UUID.class);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        double[] weights = {40.0, 42.5, 45.0};
        int[] daysAgo = {21, 14, 7};
        for (int i = 0; i < weights.length; i++) {
            mockMvc.perform(post("/api/v1/workouts")
                            .header("Authorization", alice.authorization())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "name", "Session",
                                    "performed_at", today.minusDays(daysAgo[i]).toString(),
                                    "notes", "Felt heavy today",
                                    "exercises", List.of(Map.of(
                                            "exercise_id", benchId.toString(),
                                            "sets", 3, "reps", 8, "weight_kg", weights[i]))))))
                    .andExpect(status().isCreated());
        }
    }

    private JsonNode fetchInsight(boolean explain) throws Exception {
        String body = mockMvc.perform(
                        get("/api/v1/training/exercises/" + benchId + "/insights")
                                .param("explain", String.valueOf(explain))
                                .header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    @Test
    @DisplayName("explain=true changes only the prose and its source label")
    void aiChangesOnlyTheWording() throws Exception {
        JsonNode deterministic = fetchInsight(false);
        assertThat(deterministic.get("explanation_source").asText()).isEqualTo("deterministic");

        provider.respondWith("Nice work — your bench has climbed steadily.");
        JsonNode explained = fetchInsight(true);

        assertThat(explained.get("explanation_source").asText()).isEqualTo("ai");
        assertThat(explained.get("explanation").asText())
                .isEqualTo("Nice work — your bench has climbed steadily.")
                .isNotEqualTo(deterministic.get("explanation").asText());

        // Everything the engine computed must be untouched. Compared as whole subtrees so
        // a field neither side thought to check still fails.
        for (String field : new String[] {
            "classification", "metric_basis", "sessions_analyzed", "date_range",
            "current_weight_kg", "previous_weight_kg", "primary_change_percent",
            "volume_change_percent", "plateau_detected", "evidence", "suggestion",
        }) {
            assertThat((Object) explained.get(field))
                    .as("AI must not alter computed field: %s", field)
                    .isEqualTo(deterministic.get(field));
        }
    }

    @Test
    @DisplayName("the prompt carries computed metrics, never the history or the notes")
    void promptIsFactsOnly() throws Exception {
        provider.respondWith("Steady progress.");
        fetchInsight(true);

        String prompt = provider.lastUserPrompt();
        assertThat(prompt)
                .contains("Classification: progressing")
                .contains("Sessions analyzed: 3")
                .contains("Evidence:");
        // Free-text workout notes never enter the analytics layer, so they cannot reach
        // the prompt either.
        assertThat(prompt)
                .as("private note text must not be sent to a third party")
                .doesNotContain("Felt heavy today");
        assertThat(prompt.length())
                .as("a compact summary, not a history dump")
                .isLessThan(900);

        assertThat(provider.lastSystemPrompt())
                .as("the model is told to restate, not to compute")
                .contains("Do not calculate anything.")
                .contains("ALREADY been computed");
    }

    @Test
    @DisplayName("an AI failure still returns 200 with the deterministic explanation")
    void failureFallsBackWithoutChangingStatus() throws Exception {
        JsonNode deterministic = fetchInsight(false);

        for (AiProviderException failure : List.of(
                AiProviderException.notConfigured(),
                AiProviderException.rateLimited(),
                AiProviderException.misconfigured(),
                AiProviderException.requestFailed(new RuntimeException("upstream down")))) {
            provider.reset();
            provider.failWith(failure);

            JsonNode degraded = fetchInsight(true);

            assertThat(degraded.get("explanation_source").asText())
                    .as("failure mode: %s", failure.getKind())
                    .isEqualTo("deterministic");
            assertThat(degraded.get("explanation").asText())
                    .isEqualTo(deterministic.get("explanation").asText());
            assertThat(degraded.get("classification").asText()).isEqualTo("progressing");
        }
    }

    @Test
    @DisplayName("an empty AI response keeps the deterministic text")
    void emptyResponseFallsBack() throws Exception {
        provider.respondWith("   ");
        JsonNode result = fetchInsight(true);

        assertThat(result.get("explanation_source").asText()).isEqualTo("deterministic");
        assertThat(result.get("explanation").asText()).isNotBlank();
    }

    @Test
    @DisplayName("explain=false never calls the provider at all")
    void defaultPathDoesNotTouchTheProvider() throws Exception {
        fetchInsight(false);
        assertThat(provider.prompts())
                .as("analytics must not depend on AI availability, even to be asked")
                .isEmpty();
    }

    @Test
    @DisplayName("the other training endpoints never involve the AI")
    void aggregateEndpointsAreDeterministicOnly() throws Exception {
        provider.setConfigured(false);

        mockMvc.perform(get("/api/v1/training/overview")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exercises[0].explanation_source")
                        .value("deterministic"));
        mockMvc.perform(get("/api/v1/training/recommendations")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/training/exercises/" + benchId + "/history")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isOk());

        assertThat(provider.prompts()).isEmpty();
    }
}
