package com.marketplace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * A-15 (compliance plan E.3): the generated architectural documentation —
 * «توثيق معماري مولّد آلياً (Documenter)» (reference: Spring Modulith
 * {@code documentation.html}), gate «تجدد الوثائق في CI».
 *
 * <p><b>The renewal is PROVEN, not assumed.</b> The Documenter call alone
 * (the test's former body) would stay green even if it silently wrote
 * nothing — a green that proves nothing. The strengthened gate asserts the
 * generated surface actually lands in the Documenter's default output
 * folder ({@code target/spring-modulith-docs}): the aggregating document
 * ({@code all-docs.adoc}), the C4 component diagram
 * ({@code components.puml}), and exactly one Application Module Canvas
 * ({@code module-&lt;name&gt;.adoc}) plus one per-module diagram
 * ({@code module-&lt;name&gt;.puml}, from {@code writeIndividualModules-
 * AsPlantUml()}) per application module in the verified model — the
 * reference's own two snippet kinds plus the aggregating file.
 *
 * <p>The test runs in the surefire (unit) phase on the developer's machine
 * as much as in CI, so the renewal gate is green-verified locally, and the
 * S9 assumption (the ArchUnit/JDK pairing note) keeps the skip loud on
 * runtimes where ModulithGuardReadinessTest would fail the build anyway.
 */
class ModulithDocumentationTest {

    /** The Documenter's default output folder (its own documented default). */
    private static final Path OUTPUT = Paths.get("target", "spring-modulith-docs");

    @Test
    void writeDocumentation() {
        // S9: not silent — ModulithGuardReadinessTest fails the build on any
        // runtime where this skip fires (the archunit 1.4.2 pairing resolved
        // by spring-modulith-core 2.1.1 lacks JDK 26 class-file support).
        assumeTrue(Runtime.version().feature() < 26, "Modulith docs require ArchUnit with JDK 26 support.");

        var modules = ApplicationModules.of(MarketplaceApplication.class);
        new Documenter(modules)
                .writeDocumentation()
                .writeIndividualModulesAsPlantUml();

        // E.3's gate: the regeneration's output is asserted, never assumed.
        long moduleCount = StreamSupport.stream(modules.spliterator(), false).count();

        assertThat(OUTPUT).as("the Documenter's default output folder").exists();
        assertNonEmpty(OUTPUT.resolve("all-docs.adoc"), "the aggregating Asciidoc document");
        assertNonEmpty(OUTPUT.resolve("components.puml"), "the C4 component diagram");

        assertThat(listMatching("module-*.adoc"))
                .as("one Application Module Canvas per application module (%s)", moduleCount)
                .hasSize((int) moduleCount);
        assertThat(listMatching("module-*.puml"))
                .as("one per-module component diagram per application module (%s)", moduleCount)
                .hasSize((int) moduleCount);
    }

    private static void assertNonEmpty(Path file, String description) {
        assertThat(file).as("%s must exist", description).exists();
        assertThat(file.toFile().length())
                .as("%s must not be empty", description)
                .isPositive();
    }

    private static java.util.List<Path> listMatching(String glob) {
        try (Stream<Path> paths = Files.list(OUTPUT)) {
            return paths.filter(path -> path.getFileName().toString().matches(
                            glob.replace("*", ".*")))
                    .sorted()
                    .toList();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot list the Documenter output folder: " + OUTPUT, e);
        }
    }
}
