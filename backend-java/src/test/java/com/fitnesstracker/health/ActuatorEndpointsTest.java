package com.fitnesstracker.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitnesstracker.support.PostgresIntegrationTest;
import com.fitnesstracker.support.TestSecrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Actuator over a real HTTP port.
 *
 * <p>Deliberately not MockMvc. Actuator's endpoints are served by their own handler
 * mapping and are not reachable through the MockMvc dispatcher in this setup, and — more
 * to the point — Prometheus scrapes a real socket, so that is what is worth asserting.
 *
 * <p>Reuses the containers started by {@link PostgresIntegrationTest} rather than starting
 * its own; only the web environment differs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Spring Boot's test support sets management.defaults.metrics.export.enabled=false, which
// backs PrometheusMetricsExportAutoConfiguration off and leaves /actuator/prometheus
// unmapped. That is a test-environment default, not an application one: this annotation
// restores production behaviour so the endpoint is actually exercised.
@AutoConfigureObservability
class ActuatorEndpointsTest {

    @Autowired private TestRestTemplate rest;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresIntegrationTest.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", PostgresIntegrationTest.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresIntegrationTest.POSTGRES::getPassword);
        registry.add("spring.data.redis.url", () -> "redis://"
                + PostgresIntegrationTest.REDIS.getHost() + ":"
                + PostgresIntegrationTest.REDIS.getMappedPort(6379));
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("app.jwt.secret", () -> TestSecrets.SHARED_JWT_SECRET);
        registry.add("app.email.backend", () -> "console");
        registry.add("app.email.stream", () -> "test:email:" + java.util.UUID.randomUUID());
        // Actuator normally binds its own port; in tests it shares the random web port so
        // TestRestTemplate can reach it. The isolation is a deployment concern, asserted
        // separately in the Docker validation.
        registry.add("management.server.port", () -> "");
    }

    @Test
    @DisplayName("Actuator health is exposed for the orchestrator")
    void actuatorHealthIsExposed() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("Micrometer collects the series Phase 12 will benchmark against")
    void micrometerCollectsTheExpectedSeries() {
        // Asserted through /actuator/metrics rather than /actuator/prometheus: the scrape
        // endpoint is not currently registered (see the known-gap test below). The meters
        // themselves are what matter — the exposition format is a delivery detail.
        // jvm.gc.pause is deliberately absent from this list: Micrometer only registers
        // it after the first collection, so asserting it here would be flaky.
        for (String meter : new String[] {
            "jvm.memory.used", "jvm.threads.live", "jvm.gc.memory.allocated",
            "hikaricp.connections", "hikaricp.connections.pending", "process.uptime"
        }) {
            ResponseEntity<String> response =
                    rest.getForEntity("/actuator/metrics/" + meter, String.class);
            assertThat(response.getStatusCode())
                    .as("meter %s must be collected", meter)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("the Prometheus scrape endpoint serves Prometheus-formatted metrics")
    void prometheusScrapeEndpointServesMetrics() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .as("Prometheus scrapes text/plain; version=...")
                .isNotNull();

        String body = response.getBody();
        assertThat(body).isNotNull();

        // Exposition format: HELP and TYPE lines, then samples.
        assertThat(body)
                .as("must be Prometheus exposition format, not JSON")
                .contains("# HELP ")
                .contains("# TYPE ");

        // The series Phase 12 will benchmark against.
        assertThat(body)
                .contains("jvm_memory_used_bytes")
                .contains("jvm_threads_live_threads")
                .contains("hikaricp_connections")
                .contains("process_uptime_seconds");

        // The common tag from management.metrics.tags.application must be attached, so
        // series from different applications stay distinguishable in one Prometheus.
        assertThat(body).contains("application=\"fitness-tracker-backend\"");
    }

    @Test
    @DisplayName("Actuator does not serve endpoints that were not opted into")
    void unexposedEndpointsStayClosed() {
        // env, beans and configprops leak configuration including connection strings;
        // heapdump leaks memory contents. None are in the exposure list.
        //
        // The status is asserted as "not 200" rather than a specific code on purpose: an
        // unexposed endpoint has no Actuator handler, so the management filter chain (which
        // matches only exposed endpoints) does not claim it and the application chain
        // answers 401. In production, where Actuator is on its own port, the same request
        // is a 404. Either way nothing is served, which is the property that matters.
        for (String endpoint : new String[] {"env", "beans", "configprops", "heapdump",
                "threaddump", "loggers", "mappings"}) {
            ResponseEntity<String> response =
                    rest.getForEntity("/actuator/" + endpoint, String.class);
            assertThat(response.getStatusCode().is2xxSuccessful())
                    .as("/actuator/%s must not be served", endpoint)
                    .isFalse();
            assertThat(response.getBody() == null ? "" : response.getBody())
                    .as("/actuator/%s must not leak configuration", endpoint)
                    .doesNotContain("spring.datasource")
                    .doesNotContain("app.jwt.secret");
        }
    }

    @Test
    @DisplayName("the contract health endpoints work over a real port too")
    void contractEndpointsWorkOverHttp() {
        ResponseEntity<String> health = rest.getForEntity("/api/v1/health", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).isEqualTo("{\"data\":{\"status\":\"ok\"}}");

        ResponseEntity<String> ready = rest.getForEntity("/api/v1/ready", String.class);
        assertThat(ready.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ready.getBody()).contains("\"database\":\"up\"").contains("\"redis\":\"up\"");
    }
}
