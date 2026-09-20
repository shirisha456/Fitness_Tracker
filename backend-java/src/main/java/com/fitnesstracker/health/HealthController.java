package com.fitnesstracker.health;

import com.fitnesstracker.common.api.ApiResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness and readiness, preserving the previous implementation's exact contract.
 *
 * <p>Deliberately <em>not</em> replaced by Spring Boot Actuator. Actuator's
 * {@code /actuator/health} has a different path and a different body, and three things
 * depend on these: the Docker healthcheck, nginx, and the frontend. Actuator is mounted
 * alongside these for metrics and Prometheus scraping — it is an addition, not a
 * substitute.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private static final String UP = "up";
    private static final String DOWN = "down";

    private final DataSource dataSource;
    private final StringRedisTemplate redis;

    public HealthController(DataSource dataSource, StringRedisTemplate redis) {
        this.dataSource = dataSource;
        this.redis = redis;
    }

    /** Liveness: the process is running. No dependency is touched. */
    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.of(Map.of("status", "ok"));
    }

    /**
     * Readiness: returns <b>503</b> when any dependency is down, so a load balancer or
     * orchestrator takes this instance out of rotation.
     */
    @GetMapping("/ready")
    public ResponseEntity<ApiResponse<ReadyBody>> ready() {
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("database", check("database", this::pingDatabase));
        checks.put("redis", check("redis", this::pingRedis));

        boolean allUp = checks.values().stream().allMatch(UP::equals);
        ReadyBody body = new ReadyBody(allUp ? "ok" : "degraded", checks);
        return ResponseEntity.status(allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.of(body));
    }

    public record ReadyBody(String status, Map<String, String> checks) {}

    /**
     * A probe reports "down"; it never fails the request.
     *
     * <p>The broad catch is intentional and confined to this method: the whole point of a
     * readiness probe is to convert any dependency failure into a status, and a driver can
     * throw anything. The cause is logged at debug so a flapping probe stays diagnosable
     * without filling production logs.
     */
    private String check(String name, Runnable probe) {
        try {
            probe.run();
            return UP;
        } catch (Exception ex) {
            log.debug("readiness probe failed dependency={}", name, ex);
            return DOWN;
        }
    }

    private void pingDatabase() {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        } catch (Exception ex) {
            throw new IllegalStateException("database probe failed", ex);
        }
    }

    private void pingRedis() {
        String pong = redis.getConnectionFactory().getConnection().ping();
        if (pong == null) {
            throw new IllegalStateException("redis did not respond to PING");
        }
    }
}
