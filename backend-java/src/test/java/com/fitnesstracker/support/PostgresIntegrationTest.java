package com.fitnesstracker.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests: a real PostgreSQL 16 container, migrated by Flyway,
 * with Hibernate in {@code validate} mode.
 *
 * <p>PostgreSQL rather than H2 deliberately — this schema uses native enum types, partial
 * indexes and {@code gen_random_uuid()}, none of which H2 reproduces faithfully. It also
 * matches how this schema has always been tested: against real PostgreSQL.
 *
 * <p>The container is {@code static}, so one instance is shared by every test class in
 * the JVM rather than started per class.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(DatabaseCleaner.class)
public abstract class PostgresIntegrationTest {

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withReuse(false);

    /**
     * Redis is present because {@code /api/v1/ready} probes it and must report 503 when it
     * is down. Testing readiness against a stubbed client would test the stub.
     */
    public static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @Autowired private DatabaseCleaner databaseCleaner;

    /** Every test starts from the just-migrated state; see {@link DatabaseCleaner}. */
    @BeforeEach
    void resetDatabase() {
        databaseCleaner.reset();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        // The point of the exercise: a mapping that disagrees with the schema must fail
        // the build here, not in production.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        // The same secret the reference contract fixtures are generated with, so a token
        // minted by either backend verifies against the other. 64 chars: HS256 requires
        // at least 32 bytes (RFC 7518 section 3.2), which Nimbus enforces.
        registry.add("app.jwt.secret", () -> TestSecrets.SHARED_JWT_SECRET);
        // Never send real email from a test.
        registry.add("app.email.backend", () -> "console");
        // One Redis container is shared by every test context, and each context runs an
        // email worker. Without a per-context stream they consume one another's messages.
        registry.add("app.email.stream", () -> "test:email:" + java.util.UUID.randomUUID());
        // Actuator shares the random web port in tests rather than a fixed 9000.
        registry.add("management.server.port", () -> "");
    }
}
