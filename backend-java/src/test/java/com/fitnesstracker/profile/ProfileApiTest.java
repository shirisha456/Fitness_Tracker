package com.fitnesstracker.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesstracker.support.AuthenticatedTestClient;
import com.fitnesstracker.support.AuthenticatedTestClient.TestUsers;
import com.fitnesstracker.support.PostgresIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** One profile per user, upserted by PUT. */
@Import(AuthenticatedTestClient.class)
class ProfileApiTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private TestUsers testUsers;
    @Autowired private JdbcTemplate jdbc;

    private TestUsers.Session alice;

    @BeforeEach
    void setUp() {
        alice = testUsers.session("alice-profile@example.com");
    }

    private void putProfile(TestUsers.Session session, Map<String, ?> body, int expectedStatus)
            throws Exception {
        mockMvc.perform(put("/api/v1/profile")
                        .header("Authorization", session.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    @DisplayName("GET is 404 until a profile has been saved")
    void getIsNotFoundBeforeCreate() throws Exception {
        mockMvc.perform(get("/api/v1/profile").header("Authorization", alice.authorization()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));

        // /auth/me reports the same fact, which is what the frontend branches on.
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", alice.authorization()))
                .andExpect(jsonPath("$.data.has_profile").value(false));
    }

    @Test
    @DisplayName("PUT creates the profile and echoes every field")
    void putCreatesProfile() throws Exception {
        mockMvc.perform(put("/api/v1/profile")
                        .header("Authorization", alice.authorization())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "display_name", "Alex",
                                "date_of_birth", "1995-04-12",
                                "sex", "female",
                                "height_cm", 168.5,
                                "fitness_goal", "Build strength",
                                "activity_level", "very_active"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.display_name").value("Alex"))
                .andExpect(jsonPath("$.data.date_of_birth").value("1995-04-12"))
                .andExpect(jsonPath("$.data.sex").value("female"))
                .andExpect(jsonPath("$.data.height_cm").value(168.5))
                .andExpect(jsonPath("$.data.activity_level").value("very_active"))
                .andExpect(jsonPath("$.data.created_at").isNotEmpty())
                .andExpect(jsonPath("$.data.updated_at").isNotEmpty());

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", alice.authorization()))
                .andExpect(jsonPath("$.data.has_profile").value(true));
    }

    @Test
    @DisplayName("PUT upserts: one row per user, never a second")
    void putUpsertsRatherThanDuplicating() throws Exception {
        putProfile(alice, Map.of("display_name", "First"), 200);
        putProfile(alice, Map.of("display_name", "Second"), 200);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM profiles WHERE user_id = ?",
                        Long.class, alice.user().getId()))
                .as("UNIQUE(user_id) — one profile per user")
                .isEqualTo(1);

        mockMvc.perform(get("/api/v1/profile").header("Authorization", alice.authorization()))
                .andExpect(jsonPath("$.data.display_name").value("Second"));
    }

    @Test
    @DisplayName("PUT is a replace: omitted fields become null")
    void putIsFullReplace() throws Exception {
        putProfile(alice, Map.of(
                "display_name", "Alex", "height_cm", 168.5, "sex", "female"), 200);
        putProfile(alice, Map.of("display_name", "Alex only"), 200);

        mockMvc.perform(get("/api/v1/profile").header("Authorization", alice.authorization()))
                .andExpect(jsonPath("$.data.display_name").value("Alex only"))
                .andExpect(jsonPath("$.data.height_cm").doesNotExist())
                .andExpect(jsonPath("$.data.sex").doesNotExist());
    }

    @Test
    @DisplayName("enum values are the lowercase labels, and unknown ones are rejected")
    void enumHandling() throws Exception {
        putProfile(alice, Map.of("sex", "unspecified", "activity_level", "sedentary"), 200);
        mockMvc.perform(get("/api/v1/profile").header("Authorization", alice.authorization()))
                .andExpect(jsonPath("$.data.sex").value("unspecified"))
                .andExpect(jsonPath("$.data.activity_level").value("sedentary"));

        putProfile(alice, Map.of("sex", "FEMALE"), 400);
        putProfile(alice, Map.of("activity_level", "extremely_active"), 400);
    }

    @Test
    @DisplayName("height is bounded and display_name length limited")
    void validationBounds() throws Exception {
        putProfile(alice, Map.of("height_cm", 0), 400);
        putProfile(alice, Map.of("height_cm", 301), 400);
        putProfile(alice, Map.of("display_name", "x".repeat(101)), 400);
    }

    @Test
    @DisplayName("each user has their own profile and cannot see another's")
    void profilesAreIsolated() throws Exception {
        putProfile(alice, Map.of("display_name", "Alice"), 200);
        var bob = testUsers.session("bob-profile@example.com");

        mockMvc.perform(get("/api/v1/profile").header("Authorization", bob.authorization()))
                .andExpect(status().isNotFound());

        putProfile(bob, Map.of("display_name", "Bob"), 200);
        mockMvc.perform(get("/api/v1/profile").header("Authorization", bob.authorization()))
                .andExpect(jsonPath("$.data.display_name").value("Bob"));
        mockMvc.perform(get("/api/v1/profile").header("Authorization", alice.authorization()))
                .andExpect(jsonPath("$.data.display_name").value("Alice"));
    }

    @Test
    @DisplayName("profile endpoints require authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/profile")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/profile")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
