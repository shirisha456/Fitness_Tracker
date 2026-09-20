package com.fitnesstracker.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fitnesstracker.common.api.ApiResponse;
import com.fitnesstracker.common.api.ErrorCode;
import com.fitnesstracker.common.exception.AppException;
import com.fitnesstracker.support.PostgresIntegrationTest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The error envelope is shared by every endpoint, so it is tested once, here, against
 * endpoints that exist only for this purpose.
 *
 * <p>The shapes asserted were captured from a running Python instance; see
 * docs/api-compatibility.md.
 */
@Import({ErrorEnvelopeTest.ProbeController.class, ErrorEnvelopeTest.ProbeSecurity.class})
class ErrorEnvelopeTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;

    /**
     * Lets the probe endpoints through the filter chain.
     *
     * <p>Ordered ahead of the application chain so these paths are anonymous. The error
     * envelope is shared by every endpoint regardless of authentication, so testing it
     * does not require inventing a user.
     */
    @TestConfiguration
    static class ProbeSecurity {

        @Bean
        @Order(1)
        SecurityFilterChain probeChain(HttpSecurity http) throws Exception {
            return http.securityMatcher("/api/v1/__probe/**")
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .csrf(csrf -> csrf.disable())
                    .build();
        }
    }

    /** Test-only endpoints that raise each error class the handler must render. */
    @TestConfiguration
    @RestController
    @RequestMapping("/api/v1/__probe")
    static class ProbeController {

        record Payload(@NotBlank String name, @Positive Integer sets) {}

        @GetMapping("/app-exception")
        ApiResponse<Void> appException() {
            throw new AppException(ErrorCode.NOT_FOUND, "Workout not found", 404);
        }

        @GetMapping("/conflict")
        ApiResponse<Void> conflict() {
            throw new AppException(
                    ErrorCode.CONFLICT, "An account with this email already exists", 409);
        }

        @GetMapping("/boom")
        ApiResponse<Void> boom() {
            throw new IllegalStateException(
                    "jdbc connection string postgres://user:hunter2@db:5432 leaked");
        }

        @GetMapping("/uuid/{id}")
        ApiResponse<String> uuid(@PathVariable UUID id) {
            return ApiResponse.of(id.toString());
        }

        @PostMapping("/validated")
        ApiResponse<String> validated(@Valid @RequestBody Payload payload) {
            return ApiResponse.of(payload.name());
        }
    }

    @Test
    @DisplayName("an AppException renders the full envelope with its own status")
    void appExceptionRendersEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/__probe/app-exception"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("Workout not found"))
                .andExpect(jsonPath("$.error.details").isArray())
                .andExpect(jsonPath("$.error.details").isEmpty())
                .andExpect(jsonPath("$.error.correlation_id").isNotEmpty());
    }

    @Test
    @DisplayName("correlation_id is snake_case and echoes the caller's id")
    void correlationIdIsSnakeCaseAndPropagated() throws Exception {
        mockMvc.perform(get("/api/v1/__probe/conflict").header("X-Correlation-ID", "abc-123"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.correlation_id").value("abc-123"))
                .andExpect(jsonPath("$.error.correlationId").doesNotExist());
    }

    @Test
    @DisplayName("validation failures return 400, not Spring's 422 or 500")
    void validationReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/__probe/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"sets\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.message").value("Request validation failed"))
                .andExpect(jsonPath("$.error.details", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.error.details[*].field",
                        Matchers.containsInAnyOrder("name", "sets")))
                .andExpect(jsonPath("$.error.details[*].code",
                        Matchers.everyItem(Matchers.is("INVALID_FORMAT"))));
    }

    @Test
    @DisplayName("a malformed body is a 400, not a 500")
    void malformedBodyReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/__probe/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("a non-UUID path variable is a 400, not a 500")
    void badPathVariableReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/__probe/uuid/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                // "path.id", not "id": FastAPI prefixes the parameter's location, and the
                // field name is part of the contract clients read.
                .andExpect(jsonPath("$.error.details[0].field").value("path.id"));
    }

    @Test
    @DisplayName("an unhandled exception leaks nothing to the client")
    void unhandledExceptionLeaksNothing() throws Exception {
        String body = mockMvc.perform(get("/api/v1/__probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.error.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.error.correlation_id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        // The message carried a password and a connection string; neither may appear, and
        // nor may a stack trace or an exception class name.
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("hunter2")
                .doesNotContain("postgres://")
                .doesNotContain("IllegalStateException")
                .doesNotContain("com.fitnesstracker");
    }

    @Test
    @DisplayName("an unknown route under the probe prefix returns the NOT_FOUND envelope")
    void unknownRouteReturnsEnvelope() throws Exception {
        // Probed under the anonymous prefix: an unknown path elsewhere is answered by the
        // security chain with 401 before routing is consulted, which is correct — an
        // unauthenticated caller should not be able to map the API surface.
        mockMvc.perform(get("/api/v1/__probe/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.details").isArray());
    }

    @Test
    @DisplayName("an unknown route outside the public paths is 401, not a route map")
    void unknownProtectedRouteIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
