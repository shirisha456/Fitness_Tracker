package com.fitnesstracker.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads the checked-in compatibility fixtures from the test classpath.
 *
 * <p>These files are <b>data, not generated artifacts</b>. They were originally produced by
 * the FastAPI implementation that this service replaced — Python's {@code round()} semantics,
 * PyJWT's token shape, argon2-cffi's hash parameters and the schema Alembic built — and they
 * are what pins this service's behaviour to those exact semantics. The generators are gone
 * along with that implementation; the evidence they produced is kept, because the tests that
 * consume it still guard real behaviour:
 *
 * <ul>
 *   <li>rounding and classification boundaries in the training analytics,
 *   <li>the JWT claim set and TTLs clients already depend on,
 *   <li>the Argon2id parameters the existing password hash corpus requires,
 *   <li>the database schema, so a drifting entity mapping fails the build.
 * </ul>
 *
 * <p>They live under {@code src/test/resources/contract/} so the Java module is
 * self-contained: nothing outside this directory is required to build or test it. To see how
 * they were generated, check out the {@code pre-java-only-cleanup} tag.
 */
public final class ContractFixtures {

    private ContractFixtures() {}

    public static String read(String name) {
        String resource = "/contract/" + name;
        try (InputStream in = ContractFixtures.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(
                        "missing contract fixture on the test classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + resource, e);
        }
    }
}
