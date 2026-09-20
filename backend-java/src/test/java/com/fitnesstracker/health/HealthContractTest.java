package com.fitnesstracker.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitnesstracker.support.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code /api/v1/health} and {@code /api/v1/ready} must stay byte-compatible with the
 * previous implementation: the Docker healthcheck, nginx and the frontend all depend on them.
 */
class HealthContractTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/v1/health returns the same body as the previous implementation")
    void healthMatchesContract() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"data\":{\"status\":\"ok\"}}", true));
    }

    @Test
    @DisplayName("GET /api/v1/ready reports every dependency up")
    void readyReportsDependencies() throws Exception {
        mockMvc.perform(get("/api/v1/ready"))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"data\":{\"status\":\"ok\",\"checks\":"
                                + "{\"database\":\"up\",\"redis\":\"up\"}}}",
                        true));
    }

    @Test
    @DisplayName("health endpoints need no authentication")
    void healthIsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/ready")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the success envelope omits message when there is none")
    void envelopeOmitsAbsentMessage() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(jsonPath("$.data").exists())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    @DisplayName("X-Correlation-ID is generated when absent and echoed when supplied")
    void correlationIdIsPropagated() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(header().exists("X-Correlation-ID"));

        mockMvc.perform(get("/api/v1/health").header("X-Correlation-ID", "caller-supplied-id"))
                .andExpect(header().string("X-Correlation-ID", "caller-supplied-id"));
    }

}
