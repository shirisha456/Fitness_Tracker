package com.fitnesstracker.traininginsights;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesstracker.support.ContractFixtures;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fitnesstracker.traininginsights.domain.LoggedSet;
import com.fitnesstracker.traininginsights.domain.TrainingAnalytics;
import com.fitnesstracker.traininginsights.dto.ExerciseInsight;
import com.fitnesstracker.traininginsights.dto.ExerciseRef;
import com.fitnesstracker.traininginsights.dto.SessionMetrics;
import com.fitnesstracker.workouts.entity.ExerciseCategory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Cross-language parity for the deterministic analytics engine.
 *
 * <p>Consumes {@code contract/training-insights-fixtures.json} — the same file the the previous test suite
 * suite reads — generated from the previous implementation. <b>the fixture output is the source
 * of truth.</b> This does not assume the Java rounding helper is universally equivalent to
 * CPython's {@code round()}; it asserts the full computed result field by field, so any
 * divergence in rounding, formatting or ordering fails here with the exact difference.
 *
 * <p>No Spring context: the engine is pure, so these run in milliseconds.
 */
class TrainingAnalyticsFixtureTest {

    private static final String FIXTURES = "training-insights-fixtures.json";

    /**
     * Configured to match the application's wire format exactly — snake_case, ISO dates —
     * so comparing serialised output against the fixture compares what the API would emit.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static JsonNode document;

    private static JsonNode document() throws IOException {
        if (document == null) {
            document = MAPPER.readTree(ContractFixtures.read(FIXTURES));
        }
        return document;
    }

    // --- fixture plumbing ----------------------------------------------------

    static Stream<Arguments> cases() throws IOException {
        List<Arguments> arguments = new ArrayList<>();
        document().get("cases").forEach(node ->
                arguments.add(Arguments.of(node.get("name").asText(), node)));
        return arguments.stream();
    }

    static Stream<JsonNode> percentChangeCases() throws IOException {
        return scalars("percent_change");
    }

    static Stream<JsonNode> suggestedLoadCases() throws IOException {
        return scalars("suggest_next_load_kg");
    }

    static Stream<JsonNode> consistencyCases() throws IOException {
        return scalars("consistency");
    }

    static Stream<JsonNode> volumeTrendCases() throws IOException {
        return scalars("volume_trend");
    }

    private static Stream<JsonNode> scalars(String name) throws IOException {
        List<JsonNode> nodes = new ArrayList<>();
        document().get("scalars").get(name).forEach(nodes::add);
        return nodes.stream();
    }

    private static List<LoggedSet> loggedSets(JsonNode testCase) {
        List<LoggedSet> rows = new ArrayList<>();
        testCase.get("logged_sets").forEach(row -> rows.add(new LoggedSet(
                UUID.fromString(row.get("workout_id").asText()),
                LocalDate.parse(row.get("performed_at").asText()),
                Instant.parse(row.get("workout_created_at").asText()),
                row.get("sets").asInt(),
                row.get("reps").asInt(),
                row.get("weight_kg").isNull() ? null : row.get("weight_kg").asDouble(),
                row.get("has_medical_note").asBoolean())));
        return rows;
    }

    private static ExerciseRef exerciseRef(JsonNode testCase) {
        JsonNode exercise = testCase.get("exercise");
        return new ExerciseRef(
                UUID.fromString(exercise.get("id").asText()),
                exercise.get("name").asText(),
                ExerciseCategory.fromValue(exercise.get("category").asText()));
    }

    private static Double nullableDouble(JsonNode node) {
        return node == null || node.isNull() ? null : node.asDouble();
    }

    // --- the parity assertions -----------------------------------------------

    @ParameterizedTest(name = "insight matches the reference fixture: {0}")
    @MethodSource("cases")
    void insightMatchesFixture(String name, JsonNode testCase) {
        ExerciseInsight insight = TrainingAnalytics.buildExerciseInsight(
                exerciseRef(testCase), loggedSets(testCase));

        JsonNode expected = testCase.get("expected").get("insight");
        JsonNode actual = MAPPER.valueToTree(insight);

        // Field-by-field first: a mismatch names the field rather than dumping two blobs.
        assertThat(actual.get("classification").asText())
                .as("%s: classification", name)
                .isEqualTo(expected.get("classification").asText());
        assertThat(actual.get("metric_basis").asText())
                .as("%s: metric_basis", name)
                .isEqualTo(expected.get("metric_basis").asText());
        assertThat(actual.get("sessions_analyzed").asInt())
                .as("%s: sessions_analyzed", name)
                .isEqualTo(expected.get("sessions_analyzed").asInt());
        assertThat(nullableDouble(actual.get("primary_change_percent")))
                .as("%s: primary_change_percent — the rounding boundary check", name)
                .isEqualTo(nullableDouble(expected.get("primary_change_percent")));
        assertThat(nullableDouble(actual.get("volume_change_percent")))
                .as("%s: volume_change_percent", name)
                .isEqualTo(nullableDouble(expected.get("volume_change_percent")));
        assertThat(nullableDouble(actual.get("current_weight_kg")))
                .as("%s: current_weight_kg", name)
                .isEqualTo(nullableDouble(expected.get("current_weight_kg")));
        assertThat(nullableDouble(actual.get("previous_weight_kg")))
                .as("%s: previous_weight_kg", name)
                .isEqualTo(nullableDouble(expected.get("previous_weight_kg")));
        assertThat(actual.get("plateau_detected").asBoolean())
                .as("%s: plateau_detected", name)
                .isEqualTo(expected.get("plateau_detected").asBoolean());

        // Evidence strings carry Python's :g and :+ formatting, so they are compared
        // literally, line by line.
        assertThat(toStrings(actual.get("evidence")))
                .as("%s: evidence lines", name)
                .containsExactlyElementsOf(toStrings(expected.get("evidence")));

        assertThat((Object) actual.get("suggestion"))
                .as("%s: suggestion", name)
                .isEqualTo(expected.get("suggestion"));
        assertThat((Object) actual.get("date_range"))
                .as("%s: date_range", name)
                .isEqualTo(expected.get("date_range"));
        assertThat(actual.get("explanation").asText())
                .as("%s: deterministic explanation", name)
                .isEqualTo(expected.get("explanation").asText());
        assertThat(actual.get("explanation_source").asText()).isEqualTo("deterministic");

        // Then the whole object, so a field neither side thought to check still fails.
        assertThat((Object) actual).as("%s: full insight payload", name).isEqualTo(expected);
    }

    @ParameterizedTest(name = "sessions and personal bests match the reference fixture: {0}")
    @MethodSource("cases")
    void sessionsAndBestsMatchFixture(String name, JsonNode testCase) {
        List<SessionMetrics> sessions = TrainingAnalytics.buildSessions(loggedSets(testCase));

        assertThat((Object) MAPPER.valueToTree(sessions))
                .as("%s: session aggregation and ordering", name)
                .isEqualTo(testCase.get("expected").get("sessions"));

        assertThat((Object) MAPPER.valueToTree(TrainingAnalytics.findPersonalBests(sessions)))
                .as("%s: personal bests", name)
                .isEqualTo(testCase.get("expected").get("personal_bests"));
    }

    @Test
    @DisplayName("all six classifications appear in the shared fixtures")
    void everyClassificationIsCovered() throws IOException {
        List<String> declared = toStrings(document().get("classifications"));
        List<String> covered = new ArrayList<>();
        document().get("cases").forEach(node -> {
            String classification =
                    node.get("expected").get("insight").get("classification").asText();
            if (!covered.contains(classification)) {
                covered.add(classification);
            }
        });
        assertThat(covered).containsExactlyInAnyOrderElementsOf(declared);
        assertThat(declared).hasSize(6);
    }

    // --- scalar parity -------------------------------------------------------

    @ParameterizedTest(name = "percent_change matches the reference fixture")
    @MethodSource("percentChangeCases")
    void percentChangeMatchesFixture(JsonNode scalar) {
        Double actual = TrainingAnalytics.percentChange(
                nullableDouble(scalar.get("baseline")), nullableDouble(scalar.get("latest")));
        assertThat((Object) actual)
                .as("percent_change(%s, %s)", scalar.get("baseline"), scalar.get("latest"))
                .isEqualTo(nullableDouble(scalar.get("expected")));
    }

    @ParameterizedTest(name = "suggest_next_load_kg matches the reference fixture")
    @MethodSource("suggestedLoadCases")
    void suggestedLoadMatchesFixture(JsonNode scalar) {
        Double actual = TrainingAnalytics.suggestNextLoadKg(scalar.get("current").asDouble());
        assertThat((Object) actual)
                .as("suggest_next_load_kg(%s)", scalar.get("current"))
                .isEqualTo(nullableDouble(scalar.get("expected")));
    }

    @ParameterizedTest(name = "consistency matches the reference fixture")
    @MethodSource("consistencyCases")
    void consistencyMatchesFixture(JsonNode scalar) {
        List<LocalDate> dates = new ArrayList<>();
        scalar.get("workout_dates").forEach(node -> dates.add(LocalDate.parse(node.asText())));

        var metrics = TrainingAnalytics.computeConsistency(
                dates, LocalDate.parse(scalar.get("today").asText()));

        assertThat((Object) MAPPER.valueToTree(metrics)).isEqualTo(scalar.get("expected"));
    }

    @ParameterizedTest(name = "volume_trend matches the reference fixture")
    @MethodSource("volumeTrendCases")
    void volumeTrendMatchesFixture(JsonNode scalar) {
        List<LoggedSet> entries = new ArrayList<>();
        scalar.get("entries").forEach(node -> entries.add(new LoggedSet(
                UUID.randomUUID(),
                LocalDate.parse(node.get("performed_at").asText()),
                Instant.parse("2026-06-01T08:00:00Z"),
                node.get("sets").asInt(),
                node.get("reps").asInt(),
                node.get("weight_kg").isNull() ? null : node.get("weight_kg").asDouble())));

        var trend = TrainingAnalytics.computeVolumeTrend(
                entries, LocalDate.parse(scalar.get("today").asText()));

        assertThat((Object) MAPPER.valueToTree(trend)).isEqualTo(scalar.get("expected"));
    }

    private static List<String> toStrings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
