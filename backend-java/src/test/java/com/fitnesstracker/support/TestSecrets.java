package com.fitnesstracker.support;

/**
 * The shared secret used by every test and by the reference contract-fixture generator.
 *
 * <p>Cross-backend token compatibility only means anything if both sides sign with the
 * same key, so it lives in one place. 64 characters because HS256 requires a key at least
 * as long as its hash output (RFC 7518 §3.2) — PyJWT merely warns about a shorter key,
 * Nimbus refuses it.
 */
public final class TestSecrets {

    public static final String SHARED_JWT_SECRET =
            "contract-test-shared-secret-key-0123456789abcdefghijklmnopqrstuv";

    private TestSecrets() {}
}
