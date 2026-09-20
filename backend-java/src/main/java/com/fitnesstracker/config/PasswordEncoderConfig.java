package com.fitnesstracker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Argon2 configured to match the previous implementation's existing hash corpus.
 *
 * <p>Every password hash already in the database was produced by argon2-cffi's
 * {@code PasswordHasher()} defaults: argon2id, v=19, m=65536 KiB, t=3, p=4, 32-byte hash,
 * 16-byte salt. Verification would in fact work with any parameters, because Argon2 reads
 * them back out of the encoded string — but the parameters below govern the hashes this
 * application <em>writes</em>.
 *
 * <p>Spring's own defaults ({@code defaultsForSpringSecurity_v5_8()}: m=16384, t=2, p=1)
 * are weaker than the existing corpus. Using them would silently downgrade the cost of
 * every password changed after the cutover, and would leave the database holding two
 * different strengths with nothing recording why. The parameters are therefore pinned
 * explicitly, and {@code contract/argon2-fixtures.json} holds real reference-produced hashes
 * that the test suite verifies against.
 */
@Configuration
public class PasswordEncoderConfig {

    private static final int SALT_LENGTH_BYTES = 16;
    private static final int HASH_LENGTH_BYTES = 32;
    private static final int PARALLELISM = 4;
    private static final int MEMORY_KIB = 65536;
    private static final int ITERATIONS = 3;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(
                SALT_LENGTH_BYTES, HASH_LENGTH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);
    }
}
