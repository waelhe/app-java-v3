package com.marketplace.shared.jpa;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * I7 Phase 3 (account-pseudonymization-plan §2 gate b-4 — the audit
 * history purge): the infrastructure-level scrub of the
 * {@code created_by}/{@code updated_by} audit columns. The plan's purge
 * option, verbatim: "كنس {@code created_by}/{@code updated_by} عبر نحو
 * 20 جدولاً — عملية ثقيلة تُشغَّل خارج المعاملة، تقبل التدرّج".
 *
 * <p><b>Why this lives in shared-jpa (the convention's own home):</b> the
 * two columns are the BaseEntity/@CreatedBy/@LastModifiedBy convention
 * this package owns ({@code AuditorAwareImpl} writes
 * {@code Authentication.getName()} — the subject text, the plan's P5
 * measurement), not domain content of any module: the predicate is
 * identity-string equality on every table alike, zero domain logic. The
 * identity module orchestrates the purge and depends on
 * {@code shared :: shared-jpa} already ({@code User extends BaseEntity})
 * — no module boundary moves, {@code ModulithVerificationTest} stays the
 * unedited guard.</p>
 *
 * <p><b>Why the tables are DISCOVERED, not listed (measured at runtime):
 * </b> the purge's contract is "every table carrying these columns" —
 * {@code information_schema.columns} is that set's own source of truth,
 * so a future table joins the scrub by existing, and no hand-maintained
 * list can drift from the schema (the measured facts today: every
 * BaseEntity base table plus the V24 Envers mirrors carry both columns;
 * {@code disputes} the base table does NOT — V20 — while its mirror
 * does; the discovery encodes each such fact automatically). The single
 * exclusion is {@code users_aud}: that mirror dies wholesale in the
 * orchestrator's {@code DELETE FROM users_aud WHERE id = …} (the plan's
 * letter — deletion subsumes scrubbing; scrubbing those rows first would
 * double-count them).</p>
 *
 * <p><b>Statement shape:</b> one UPDATE per (table, column) — never a
 * combined statement, because a row may carry the subject in
 * {@code created_by} while {@code updated_by} belongs to a different
 * (legitimate) auditor: combined nulling would destroy that other
 * auditor's trail. Each statement runs on its own autocommit (no
 * {@code @Transactional} — the plan's "خارج المعاملة" and per-table
 * gradation: a partial failure resumes on re-run, every predicate being
 * idempotent). Table names come from the catalog itself and are
 * validated against a strict identifier pattern before interpolation.</p>
 *
 * <p><b>CWE-532 discipline:</b> the log lines carry table names and row
 * counts only — the subject strings (the direct identifiers being
 * scrubbed) never enter the log store.</p>
 */
@Component
public class AuditColumnScrubAdapter {

    private static final Logger log = LoggerFactory.getLogger(AuditColumnScrubAdapter.class);

    /**
     * The catalog's identifiers are plain lowercase names (the project's
     * whole history); the pattern is the belt that guarantees only such
     * names ever reach the statement text.
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]*");

    private static final String DISCOVERY_SQL = """
            SELECT table_name, column_name
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND column_name IN ('created_by', 'updated_by')
              AND table_name <> 'users_aud'
            """;

    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public AuditColumnScrubAdapter(NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.namedParameterJdbcTemplate = namedParameterJdbcTemplate;
    }

    /**
     * Nulls every {@code created_by}/{@code updated_by} cell that holds
     * one of the given identity strings, across every table the live
     * schema reports carrying the columns. Returns the total number of
     * affected rows (one row matching both columns on both statements
     * counts twice — the count is per statement, the exact fan-out shape
     * the orchestrator logs). An empty identity set is a no-op answering
     * zero (no statement runs — {@code IN ()} is not a valid list).
     *
     * @param subjects the identity strings to scrub — the erasure
     *                 target's complete subject closure (the original
     *                 subject and the pseudonymized replacement)
     */
    public int scrubAuditColumns(Set<String> subjects) {
        if (subjects == null || subjects.isEmpty()) {
            return 0;
        }

        Map<String, Set<String>> tablesByColumns = discoverAuditTables();
        int total = 0;
        // Deterministic order (sorted) — the gradation is observable and
        // the partial-failure resume is reproducible.
        for (Map.Entry<String, Set<String>> entry : new TreeMap<>(tablesByColumns).entrySet()) {
            String table = entry.getKey();
            int tableRows = 0;
            for (String column : entry.getValue()) {
                tableRows += scrubColumn(table, column, subjects);
            }
            if (tableRows > 0) {
                log.info("Audit column scrub: table={}, rows={}", table, tableRows);
            }
            total += tableRows;
        }
        return total;
    }

    private int scrubColumn(String table, String column, Set<String> subjects) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("subjects", subjects);
        String sql = "UPDATE " + table + " SET " + column + " = NULL WHERE " + column + " IN (:subjects)";
        return namedParameterJdbcTemplate.update(sql, parameters);
    }

    private Map<String, Set<String>> discoverAuditTables() {
        Map<String, Set<String>> tables = new LinkedHashMap<>();
        List<Map<String, Object>> rows = namedParameterJdbcTemplate.getJdbcTemplate()
                .queryForList(DISCOVERY_SQL);
        for (Map<String, Object> row : rows) {
            String table = String.valueOf(row.get("table_name"));
            String column = String.valueOf(row.get("column_name"));
            if (!SAFE_IDENTIFIER.matcher(table).matches() || !SAFE_IDENTIFIER.matcher(column).matches()) {
                // The catalog itself is the source; this only fires if the
                // schema somehow holds a name the statements cannot quote
                // safely — refuse it loudly rather than interpolate it.
                throw new IllegalStateException("Unsafe audit table/column identifier: " + table + "." + column);
            }
            tables.computeIfAbsent(table, ignored -> new java.util.LinkedHashSet<>()).add(column);
        }
        return tables;
    }
}
