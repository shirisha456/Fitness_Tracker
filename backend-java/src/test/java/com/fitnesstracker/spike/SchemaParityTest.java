package com.fitnesstracker.spike;

import static org.assertj.core.api.Assertions.assertThat;

import com.fitnesstracker.support.ContractFixtures;
import com.fitnesstracker.support.PostgresIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Flyway baseline must reproduce the schema the Python backend owns.
 *
 * <p>{@code contract/expected-schema.txt} was captured by introspecting a database built
 * with {@code alembic upgrade head}. This test introspects the Flyway-built container the
 * same way and compares. Any drift between the two backends' idea of the schema fails the
 * build rather than surfacing at cutover.
 */
class SchemaParityTest extends PostgresIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    private static final String EXPECTED = "expected-schema.txt";

    /** Re-implements the Python introspection script's output format, verbatim. */
    private String introspect() {
        StringBuilder out = new StringBuilder();
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",
                String.class);

        for (String table : tables) {
            out.append("\n=== ").append(table).append(" ===\n");
            for (Map<String, Object> col : jdbc.queryForList(
                    "SELECT column_name, data_type, character_maximum_length, is_nullable, "
                            + "column_default FROM information_schema.columns "
                            + "WHERE table_name = ? ORDER BY ordinal_position", table)) {
                Object len = col.get("character_maximum_length");
                String type = col.get("data_type") + (len != null ? "(" + len + ")" : "");
                String nullable = "YES".equals(col.get("is_nullable")) ? "NULL" : "NOT NULL";
                Object dflt = col.get("column_default");
                out.append(String.format("  %-24s %-28s %s%s%n",
                        col.get("column_name"), type, nullable,
                        dflt != null ? " DEFAULT " + dflt : ""));
            }
            for (Map<String, Object> con : jdbc.queryForList(
                    "SELECT con.conname, pg_get_constraintdef(con.oid) AS def "
                            + "FROM pg_constraint con JOIN pg_class rel ON rel.oid = con.conrelid "
                            + "WHERE rel.relname = ? ORDER BY con.contype, con.conname", table)) {
                out.append("  CONSTRAINT ").append(con.get("conname")).append(": ")
                        .append(con.get("def")).append("\n");
            }
            for (String idx : jdbc.queryForList(
                    "SELECT indexdef FROM pg_indexes WHERE tablename = ? ORDER BY indexname",
                    String.class, table)) {
                out.append("  ").append(idx).append("\n");
            }
        }

        out.append("\n=== ENUM TYPES ===\n");
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT t.typname, string_agg(e.enumlabel, ', ' ORDER BY e.enumsortorder) vals "
                        + "FROM pg_type t JOIN pg_enum e ON e.enumtypid = t.oid "
                        + "GROUP BY t.typname ORDER BY t.typname")) {
            out.append("  ").append(row.get("typname")).append(": ")
                    .append(row.get("vals")).append("\n");
        }

        out.append("\n=== SEEDED EXERCISES ===\n");
        out.append("  ").append(jdbc.queryForObject("SELECT count(*) FROM exercises", Long.class))
                .append(" rows\n");
        return out.toString();
    }

    /**
     * Strips the documentation header and each backend's own migration-history table.
     *
     * <p>{@code alembic_version} exists only in the Alembic-built database and
     * {@code flyway_schema_history} only in the Flyway-built one. Each records how its
     * owner tracks migrations, not what the application schema is, so neither belongs in
     * the comparison.
     */
    private static final List<String> HISTORY_TABLES =
            List.of("=== alembic_version ===", "=== flyway_schema_history ===");

    private static List<String> normalise(String schema) {
        List<String> lines = new ArrayList<>();
        boolean skipping = false;
        for (String raw : schema.split("\n")) {
            String line = raw.stripTrailing();
            if (line.startsWith("#") || line.isBlank()) {
                continue;
            }
            if (line.startsWith("=== ")) {
                skipping = HISTORY_TABLES.contains(line);
            }
            if (!skipping) {
                lines.add(line);
            }
        }
        return lines;
    }

    @Test
    @DisplayName("the Flyway baseline reproduces the Alembic schema exactly")
    void flywayBaselineMatchesAlembicSchema() throws IOException {
        List<String> expected = normalise(ContractFixtures.read(EXPECTED));
        List<String> actual = normalise(introspect());

        assertThat(actual)
                .as("Flyway-built schema must match the Alembic-built schema line for line")
                .containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("the baseline does not recreate Alembic's version table")
    void baselineOmitsAlembicVersionTable() {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_tables WHERE schemaname='public' "
                        + "AND tablename='alembic_version'", Long.class);
        assertThat(count)
                .as("a Flyway-built database has no Alembic history; the deployed database "
                        + "keeps its own copy untouched")
                .isZero();
    }

    @Test
    @DisplayName("the seeded exercise library is present and shared")
    void seedDataIsPresent() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exercises", Long.class))
                .isEqualTo(39L);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM exercises WHERE created_by_user_id IS NOT NULL",
                        Long.class))
                .as("every seeded row is a library entry, not a user's custom exercise")
                .isZero();
    }
}
