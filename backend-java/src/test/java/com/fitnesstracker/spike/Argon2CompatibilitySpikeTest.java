package com.fitnesstracker.spike;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesstracker.support.ContractFixtures;
import com.fitnesstracker.config.PasswordEncoderConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Risk R2 — the Java backend must verify the Argon2 hashes the Python backend already
 * wrote, or every existing user is locked out the moment traffic moves to Java.
 *
 * <p>The fixtures in {@code contract/argon2-fixtures.json} were produced by the real
 * Python implementation ({@code app.core.security.hash_password}), not by Java. Verifying
 * a Java-made hash with a Java verifier would prove nothing about compatibility.
 */
class Argon2CompatibilitySpikeTest {

    /** {@code $argon2id$v=19$m=65536,t=3,p=4$<salt>$<hash>} */
    private static final Pattern ENCODING = Pattern.compile(
            "^\\$argon2(?<variant>id|i|d)\\$v=(?<version>\\d+)"
                    + "\\$m=(?<memory>\\d+),t=(?<iterations>\\d+),p=(?<parallelism>\\d+)"
                    + "\\$(?<salt>[^$]+)\\$(?<hash>.+)$");

    private static final PasswordEncoder ENCODER = new PasswordEncoderConfig().passwordEncoder();

    private static JsonNode fixtures;

    @BeforeAll
    static void loadFixtures() throws IOException {
        // Deliberately the single shared file at the repository root rather than a copy
        // under test resources, so Python and Java cannot drift apart silently.

        fixtures = new ObjectMapper().readTree(ContractFixtures.read("argon2-fixtures.json"));
    }

    static List<String[]> pythonHashes() throws IOException {
        loadFixtures();
        List<String[]> cases = new ArrayList<>();
        for (JsonNode node : fixtures.get("fixtures")) {
            cases.add(new String[] {
                node.get("password").asText(), node.get("hash").asText(), node.get("note").asText()
            });
        }
        return cases;
    }

    @ParameterizedTest(name = "verifies a Python-produced hash: {2}")
    @MethodSource("pythonHashes")
    void verifiesHashesProducedByPython(String password, String hash, String note) {
        assertThat(ENCODER.matches(password, hash))
                .as("Java must verify the Python hash for: %s", note)
                .isTrue();
    }

    @ParameterizedTest(name = "rejects a wrong password against a Python hash: {2}")
    @MethodSource("pythonHashes")
    void rejectsWrongPasswords(String password, String hash, String note) {
        assertThat(ENCODER.matches(password + "-wrong", hash)).isFalse();
        assertThat(ENCODER.matches("", hash)).isFalse();
    }

    @Test
    @DisplayName("the configured encoder writes the same parameters Python writes")
    void encoderWritesMatchingParameters() {
        JsonNode expected = fixtures.get("parameters");

        String javaHash = ENCODER.encode("SecurePass123!");
        Matcher java = ENCODING.matcher(javaHash);
        assertThat(java.matches()).as("unexpected Argon2 encoding: %s", javaHash).isTrue();

        assertThat(java.group("variant")).isEqualTo("id");
        assertThat(Integer.parseInt(java.group("version"))).isEqualTo(19);
        assertThat(Integer.parseInt(java.group("memory")))
                .isEqualTo(expected.get("memory_cost_kib").asInt());
        assertThat(Integer.parseInt(java.group("iterations")))
                .isEqualTo(expected.get("time_cost").asInt());
        assertThat(Integer.parseInt(java.group("parallelism")))
                .isEqualTo(expected.get("parallelism").asInt());
    }

    @Test
    @DisplayName("the encoder writes the same cost parameters as the existing corpus")
    void parametersMatchTheExistingCorpus() {
        String javaHash = ENCODER.encode("SecurePass123!");
        String pythonHash = fixtures.get("fixtures").get(0).get("hash").asText();

        String javaParams = javaHash.substring(0, javaHash.indexOf('$', 10));
        String pythonParams = pythonHash.substring(0, pythonHash.indexOf('$', 10));

        // Not merely "verifiable" — identical cost. A Java-written hash must be as
        // expensive to attack as the ones already in the database.
        assertThat(javaParams).isEqualTo(pythonParams);
    }

    @Test
    @DisplayName("Spring's own defaults would have written weaker hashes")
    void springDefaultsWouldDowngradeTheCorpus() {
        PasswordEncoder springDefaults =
                org.springframework.security.crypto.argon2.Argon2PasswordEncoder
                        .defaultsForSpringSecurity_v5_8();

        Matcher defaults = ENCODING.matcher(springDefaults.encode("SecurePass123!"));
        assertThat(defaults.matches()).isTrue();

        int defaultMemory = Integer.parseInt(defaults.group("memory"));
        int defaultParallelism = Integer.parseInt(defaults.group("parallelism"));

        // Documents *why* PasswordEncoderConfig pins its parameters explicitly. If a
        // future Spring release changes its defaults to match ours this will fail, which
        // is the right moment to revisit the comment rather than discover it silently.
        assertThat(defaultMemory).isLessThan(fixtures.get("parameters").get("memory_cost_kib").asInt());
        assertThat(defaultParallelism)
                .isLessThan(fixtures.get("parameters").get("parallelism").asInt());

        // Even so, verification is parameter-independent: Argon2 reads cost from the
        // encoded string, so a weaker-configured encoder still verifies stronger hashes.
        String pythonHash = fixtures.get("fixtures").get(0).get("hash").asText();
        String pythonPassword = fixtures.get("fixtures").get(0).get("password").asText();
        assertThat(springDefaults.matches(pythonPassword, pythonHash)).isTrue();
    }

    @Test
    @DisplayName("Java-produced hashes are written to a file for Python to verify")
    void emitsHashesForPythonToVerify() throws IOException {
        List<String> lines = new ArrayList<>();
        for (JsonNode node : fixtures.get("fixtures")) {
            String password = node.get("password").asText();
            String javaHash = ENCODER.encode(password);
            assertThat(ENCODER.matches(password, javaHash)).isTrue();
            lines.add(javaHash);
        }
        Path out = Path.of("target", "java-argon2-hashes.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, lines);

        // The reverse direction (Python verifying these) is asserted by the spike script,
        // because during the side-by-side period both backends write hashes the other reads.
        assertThat(Files.readAllLines(out)).hasSize(fixtures.get("fixtures").size());
    }
}
