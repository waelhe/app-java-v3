package com.marketplace.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.StringResource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Guard against modifying already-applied VERSIONED migrations.
 *
 * <p>Why this exists (measured incident, 2026-09-14): PR #307 edited a comment
 * inside the already-applied {@code V51__postgis_radius_index_concurrently.sql}.
 * Flyway's checksum covers the <em>entire</em> file content — comments included —
 * so boot-time validation failed on every environment that had already applied V51
 * (production error: {@code applied=-38705425 resolved=591793313}), while CI's
 * fresh-database integration tests stayed green: they apply the modified file
 * from scratch and can never observe the drift. Production went 502 and was
 * restored via the documented rollback (docs/release/rollout-strategy.md §6).
 *
 * <p>This test closes that CI blind spot deterministically: every {@code V__*.sql}
 * on the classpath must carry exactly the checksum recorded in
 * {@code migration-checksums.properties} (computed with Flyway's own
 * {@link ChecksumCalculator}, BOM-pinned 12.4.0 — the same value
 * {@code flyway_schema_history.checksum} stores). Modifying an applied migration
 * now fails the build <em>before</em> merge instead of production at boot.
 *
 * <p>Scope — {@code V__} files only. Repeatable {@code R__} migrations are
 * re-applied by Flyway by design whenever their checksum changes, so freezing
 * them here would block legitimate updates. Adding a new versioned migration is
 * the one workflow change: register its line in the manifest in the same PR
 * (the failure message explains this).
 *
 * <p>Official basis: AGENTS.md — "Flyway: any schema change = new V{number}
 * migration file. Never modify existing migrations"; Flyway validation error
 * guidance ("Either revert the changes to the migration, or run repair" — we
 * revert, per the repository rule).
 */
class MigrationChecksumGuardTest {

    private static final String MIGRATION_PATTERN = "classpath*:db/migration/*.sql";
    private static final String MANIFEST = "/migration-checksums.properties";

    /**
     * Asserts every classpath {@code V__*.sql} carries exactly the checksum recorded
     * in the manifest.
     *
     * <p>Three loud failure modes (each with an explanatory message):
     * <ol>
     *   <li><b>checksum drift</b> — an already-applied migration was edited (the
     *       2026-09-14 incident class: comment-only edit, production 502 while CI
     *       stayed green on its fresh database);</li>
     *   <li><b>unregistered migration</b> — a new {@code V__} file must add its
     *       manifest line in the same PR;</li>
     *   <li><b>orphaned entry</b> — the manifest references a file that no longer
     *       exists (deleted or renamed).</li>
     * </ol>
     * Repeatable {@code R__} migrations are intentionally out of scope: Flyway
     * re-applies them when their checksum changes, so freezing them would block
     * legitimate updates.
     */
    @Test
    void versionedMigrationChecksumsMatchTheAppliedManifest() throws IOException {
        Map<String, Integer> actual = new TreeMap<>();
        var resolver = new PathMatchingResourcePatternResolver();
        for (Resource resource : resolver.getResources(MIGRATION_PATTERN)) {
            String name = resource.getFilename();
            if (name == null || !name.endsWith(".sql")) {
                continue;
            }
            if (Character.toUpperCase(name.charAt(0)) != 'V') {
                continue; // repeatable R__ migrations: re-applied by design, not frozen
            }
            actual.put(name, checksum(resource));
        }
        assertThat(actual).as("versioned migrations discovered on the classpath").isNotEmpty();

        Map<String, Integer> manifest = loadManifest();

        // 1) a registered migration drifted from its applied content — the incident class
        Map<String, String> mismatches = new TreeMap<>();
        for (Map.Entry<String, Integer> e : actual.entrySet()) {
            Integer expected = manifest.get(e.getKey());
            if (expected == null) {
                continue; // handled by rule 3 below
            }
            if (!expected.equals(e.getValue())) {
                mismatches.put(e.getKey(), "manifest=" + expected + " actual=" + e.getValue());
            }
        }
        assertThat(mismatches)
                .as("Applied VERSIONED migrations must stay byte-identical (checksum covers comments "
                        + "too — incident 2026-09-14). Fix by REVERTING the file to its applied content; "
                        + "schema changes go in a NEW V__ migration (AGENTS.md)")
                .isEmpty();

        // 2) a new migration is not registered yet
        var unregistered = new java.util.TreeSet<>(actual.keySet());
        unregistered.removeAll(manifest.keySet());
        assertThat(unregistered)
                .as("New versioned migration(s) not registered. Add a line '<file>=<checksum>' to "
                        + "src/test/resources/migration-checksums.properties in this same PR "
                        + "(see MigrationChecksumGuardTest javadoc)")
                .isEmpty();

        // 3) the manifest references a file that no longer exists
        var stale = new java.util.TreeSet<>(manifest.keySet());
        stale.removeAll(actual.keySet());
        assertThat(stale)
                .as("Manifest entries without a classpath migration (deleted/renamed file?)")
                .isEmpty();
    }

    /**
     * Computes the Flyway checksum of a classpath migration resource.
     *
     * @param resource a {@code db/migration/*.sql} classpath resource
     * @return the checksum Flyway stores in {@code flyway_schema_history.checksum}
     *         (same value, same calculator version — BOM-pinned 12.4.0)
     * @throws IOException if the resource cannot be read
     */
    private static Integer checksum(Resource resource) throws IOException {
        String content;
        try (InputStream in = resource.getInputStream()) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return ChecksumCalculator.calculate(new StringResource(content));
    }

    /**
     * Loads the expected checksums from {@value #MANIFEST} on the test classpath.
     *
     * @return filename to expected-checksum map (never {@code null}; the manifest's
     *         absence fails the test with a pointer to its location)
     * @throws IOException if the manifest cannot be read or parsed
     */
    private static Map<String, Integer> loadManifest() throws IOException {
        Properties props = new Properties();
        try (InputStream in = MigrationChecksumGuardTest.class.getResourceAsStream(MANIFEST)) {
            assertThat(in).as("manifest %s on the test classpath", MANIFEST).isNotNull();
            props.load(in);
        }
        Map<String, Integer> manifest = new HashMap<>();
        for (String name : props.stringPropertyNames()) {
            manifest.put(name, Integer.valueOf(props.getProperty(name).trim()));
        }
        return manifest;
    }
}
