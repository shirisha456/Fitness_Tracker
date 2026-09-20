package com.fitnesstracker.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitnesstracker.support.AuthenticatedTestClient;
import com.fitnesstracker.support.AuthenticatedTestClient.TestUsers;
import com.fitnesstracker.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A required query parameter that is simply absent must be a 400, not a 500.
 *
 * <p>These exist because the Phase 13 load test recorded 8,720 HTTP 500s on
 * {@code GET /nutrition/summary}. Spring raises {@code
 * MissingServletRequestParameterException}, nothing handled it, and it fell through to the
 * catch-all. the previous implementation answers 400 with {@code query.<name>} / "Field required".
 *
 * <p>The response-parity suite never caught this, because every request it compares is
 * well-formed. Absent input is a case worth testing in its own right — it is the cheapest
 * request an attacker or a broken client can send, and it was reaching the generic 500 path.
 */
@Import(AuthenticatedTestClient.class)
class MissingParameterTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestUsers testUsers;

    private TestUsers.Session alice;

    @BeforeEach
    void setUp() {
        alice = testUsers.session("alice-missing-param@example.com");
    }

    @Test
    @DisplayName("GET /nutrition/summary without date is 400, not 500")
    void nutritionSummaryRequiresDate() throws Exception {
        mockMvc.perform(get("/api/v1/nutrition/summary").header("Authorization", alice.authorization()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("query.date"))
                .andExpect(jsonPath("$.error.details[0].message").value("Field required"))
                .andExpect(jsonPath("$.error.details[0].code").value("INVALID_FORMAT"));
    }

    @Test
    @DisplayName("GET /auth/verify-email without token is 400, not 500")
    void verifyEmailRequiresToken() throws Exception {
        mockMvc.perform(get("/api/v1/auth/verify-email"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("query.token"));
    }

    @Test
    @DisplayName("a query parameter of the wrong type reports query.<snake_case_name>")
    void wrongTypeQueryParameterIsLocated() throws Exception {
        mockMvc.perform(get("/api/v1/nutrition/summary?date=not-a-date")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("query.date"));
    }

    @Test
    @DisplayName("a path variable of the wrong type reports path.<snake_case_name>")
    void wrongTypePathVariableIsLocated() throws Exception {
        // Spring's parameter is `workoutId`; the client wrote `workout_id`. the previous implementation reports
        // the latter, so this does too.
        mockMvc.perform(get("/api/v1/workouts/not-a-uuid")
                        .header("Authorization", alice.authorization()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("path.workout_id"));
    }

    @Test
    @DisplayName("the 400 body leaks no internals")
    void missingParameterLeaksNothing() throws Exception {
        String body = mockMvc.perform(get("/api/v1/nutrition/summary").header("Authorization", alice.authorization()))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("MissingServletRequestParameter")
                .doesNotContain("com.fitnesstracker")
                .doesNotContain("org.springframework");
    }
}
