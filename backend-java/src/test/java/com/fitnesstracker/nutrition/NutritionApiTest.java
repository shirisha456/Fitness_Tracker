package com.fitnesstracker.nutrition;

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
import org.springframework.test.web.servlet.MockMvc;

/** Meals, water and the daily summary, against docs/api-compatibility.md. */
@Import(AuthenticatedTestClient.class)
class NutritionApiTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private TestUsers testUsers;

    private TestUsers.Session alice;

    @BeforeEach
    void setUp() {
        alice = testUsers.session("alice-nutrition@example.com");
    }

    private Map<String, Object> meal(String name, String day, int calories) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", name);
        payload.put("logged_at", day);
        payload.put("calories", calories);
        payload.put("protein_g", 30.5);
        payload.put("carbs_g", 45.0);
        payload.put("fat_g", 12.25);
        payload.put("notes", "test meal");
        return payload;
    }

    private JsonNode create(String path, Object body) throws Exception {
        String response = mockMvc.perform(post("/api/v1/" + path)
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("data");
    }

    // --- meals ---------------------------------------------------------------

    @Test
    @DisplayName("create returns the meal with macros and created_at")
    void createsMeal() throws Exception {
        mockMvc.perform(post("/api/v1/meals")
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(meal("Oats", "2026-09-10", 420))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Oats"))
                .andExpect(jsonPath("$.data.logged_at").value("2026-09-10"))
                .andExpect(jsonPath("$.data.calories").value(420))
                .andExpect(jsonPath("$.data.protein_g").value(30.5))
                .andExpect(jsonPath("$.data.fat_g").value(12.25))
                .andExpect(jsonPath("$.data.notes").value("test meal"))
                .andExpect(jsonPath("$.data.created_at").isNotEmpty());
    }

    @Test
    @DisplayName("macros are optional and stay null when omitted")
    void macrosAreOptional() throws Exception {
        mockMvc.perform(post("/api/v1/meals")
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "Just calories", "logged_at", "2026-09-10",
                                "calories", 200))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.protein_g").doesNotExist())
                .andExpect(jsonPath("$.data.carbs_g").doesNotExist())
                .andExpect(jsonPath("$.data.notes").doesNotExist());
    }

    @Test
    @DisplayName("meals sort by logged_at desc, then created_at desc")
    void mealsAreSorted() throws Exception {
        create("meals", meal("Older", "2026-09-01", 100));
        create("meals", meal("Newest", "2026-09-10", 200));
        create("meals", meal("Middle", "2026-09-05", 150));

        mockMvc.perform(get("/api/v1/meals").header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Newest"))
                .andExpect(jsonPath("$.data[1].name").value("Middle"))
                .andExpect(jsonPath("$.data[2].name").value("Older"));
    }

    @Test
    @DisplayName("meals filter by date range")
    void mealsFilterByDate() throws Exception {
        create("meals", meal("January", "2026-01-15", 100));
        create("meals", meal("September", "2026-09-15", 200));

        mockMvc.perform(get("/api/v1/meals")
                        .param("date_from", "2026-09-01").param("date_to", "2026-09-30")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].name").value("September"));
    }

    @Test
    @DisplayName("PUT is a full replace: omitted macros are nulled")
    void updateIsFullReplace() throws Exception {
        UUID mealId = UUID.fromString(create("meals", meal("Before", "2026-09-10", 420))
                .get("id").asText());

        mockMvc.perform(put("/api/v1/meals/" + mealId)
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "After", "logged_at", "2026-09-11", "calories", 500))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("After"))
                .andExpect(jsonPath("$.data.calories").value(500))
                .andExpect(jsonPath("$.data.protein_g").doesNotExist())
                .andExpect(jsonPath("$.data.notes").doesNotExist());
    }

    @Test
    @DisplayName("delete returns 204 and the meal is gone")
    void deletesMeal() throws Exception {
        UUID mealId = UUID.fromString(create("meals", meal("Doomed", "2026-09-10", 100))
                .get("id").asText());

        mockMvc.perform(delete("/api/v1/meals/" + mealId)
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/meals/" + mealId)
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.message").value("Meal not found"));
    }

    @Test
    @DisplayName("calorie and macro bounds match the documented schema")
    void validationBounds() throws Exception {
        for (Map<String, ?> invalid : java.util.List.<Map<String, ?>>of(
                Map.of("name", "Too many", "logged_at", "2026-09-10", "calories", 20001),
                Map.of("name", "Negative", "logged_at", "2026-09-10", "calories", -1),
                Map.of("name", "Bad macro", "logged_at", "2026-09-10", "calories", 100,
                        "protein_g", -5.0),
                Map.of("name", "", "logged_at", "2026-09-10", "calories", 100))) {
            mockMvc.perform(post("/api/v1/meals")
                            .header("Authorization", alice.authorization())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(invalid)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
    }

    // --- water ---------------------------------------------------------------

    @Test
    @DisplayName("water entries are created, listed by day and deleted")
    void waterEntryLifecycle() throws Exception {
        JsonNode entry = create("water-entries",
                Map.of("logged_at", "2026-09-10", "amount_ml", 500));
        create("water-entries", Map.of("logged_at", "2026-09-11", "amount_ml", 250));

        mockMvc.perform(get("/api/v1/water-entries")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", Matchers.hasSize(2)));

        mockMvc.perform(get("/api/v1/water-entries").param("date", "2026-09-10")
                        .header("Authorization", alice.authorization()))
                .andExpect(jsonPath("$.data", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].amount_ml").value(500));

        mockMvc.perform(delete("/api/v1/water-entries/" + entry.get("id").asText())
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("water amount is bounded 1–5000")
    void waterBounds() throws Exception {
        for (int amount : new int[] {0, 5001, -100}) {
            mockMvc.perform(post("/api/v1/water-entries")
                            .header("Authorization", alice.authorization())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "logged_at", "2026-09-10", "amount_ml", amount))))
                    .andExpect(status().isBadRequest());
        }
    }

    // --- summary -------------------------------------------------------------

    @Test
    @DisplayName("the daily summary totals meals and water for that day only")
    void dailySummaryTotals() throws Exception {
        create("meals", meal("Breakfast", "2026-09-10", 400));
        create("meals", meal("Lunch", "2026-09-10", 600));
        create("meals", meal("Another day", "2026-09-11", 999));
        create("water-entries", Map.of("logged_at", "2026-09-10", "amount_ml", 500));
        create("water-entries", Map.of("logged_at", "2026-09-10", "amount_ml", 250));
        create("water-entries", Map.of("logged_at", "2026-09-11", "amount_ml", 1000));

        mockMvc.perform(get("/api/v1/nutrition/summary").param("date", "2026-09-10")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value("2026-09-10"))
                .andExpect(jsonPath("$.data.total_calories").value(1000))
                .andExpect(jsonPath("$.data.total_protein_g").value(61.0))
                .andExpect(jsonPath("$.data.total_carbs_g").value(90.0))
                .andExpect(jsonPath("$.data.total_fat_g").value(24.5))
                .andExpect(jsonPath("$.data.total_water_ml").value(750));
    }

    @Test
    @DisplayName("an empty day returns zeros, not nulls")
    void emptyDayReturnsZeros() throws Exception {
        mockMvc.perform(get("/api/v1/nutrition/summary").param("date", "2026-01-01")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total_calories").value(0))
                .andExpect(jsonPath("$.data.total_protein_g").value(0.0))
                .andExpect(jsonPath("$.data.total_water_ml").value(0));
    }

    // --- isolation and auth --------------------------------------------------

    @Test
    @DisplayName("one user never sees or mutates another's nutrition data")
    void strictUserIsolation() throws Exception {
        UUID mealId = UUID.fromString(create("meals", meal("Alice's lunch", "2026-09-10", 600))
                .get("id").asText());
        JsonNode water = create("water-entries",
                Map.of("logged_at", "2026-09-10", "amount_ml", 500));
        var bob = testUsers.session("bob-nutrition@example.com");

        mockMvc.perform(get("/api/v1/meals/" + mealId)
                        .header("Authorization", bob.authorization()))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/meals/" + mealId)
                        .header("Authorization", bob.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(meal("Hijacked", "2026-09-10", 1))))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/meals/" + mealId)
                        .header("Authorization", bob.authorization()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/water-entries/" + water.get("id").asText())
                        .header("Authorization", bob.authorization()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/meals").header("Authorization", bob.authorization()))
                .andExpect(jsonPath("$.data", Matchers.hasSize(0)));
        // Bob's summary must not include Alice's calories.
        mockMvc.perform(get("/api/v1/nutrition/summary").param("date", "2026-09-10")
                        .header("Authorization", bob.authorization()))
                .andExpect(jsonPath("$.data.total_calories").value(0))
                .andExpect(jsonPath("$.data.total_water_ml").value(0));
    }

    @Test
    @DisplayName("every nutrition endpoint requires authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/meals")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/water-entries")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/nutrition/summary").param("date", "2026-09-10"))
                .andExpect(status().isUnauthorized());
    }
}
