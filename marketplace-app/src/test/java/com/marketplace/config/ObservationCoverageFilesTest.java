package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate test for the observation-coverage layer: it pins the complete inventory
 * of {@code @Observed} business observations plus the two framework switches
 * that make them live. Same class of latent "config that lies" defect as
 * {@code PlatformGovernanceFilesTest}: nothing at runtime rejects a service
 * command that silently loses its observation — the metrics exporter just
 * stops showing it — only a pinned test keeps the coverage honest.
 *
 * <p>Framework channel (verified from the official bits):
 * {@code ObservationAutoConfiguration$ObservedAspectConfiguration} in
 * spring-boot-micrometer-observation registers the {@code ObservedAspect}
 * when BOTH hold —
 * <ul>
 *   <li>{@code management.observations.annotations.enabled=true} (Boot
 *       reference, Observability: "To enable scanning of observability
 *       annotations like @Observed … set the property to true")</li>
 *   <li>{@code org.aspectj:aspectjweaver} on the classpath ("A dependency on
 *       org.aspectj:aspectjweaver, which is part of
 *       spring-boot-starter-aspectj, is also required") — present via
 *       marketplace-platform-infra</li>
 * </ul>
 *
 * <p>Inventory policy — <b>business commands are observed, reads are not</b>:
 * HTTP-level latency of read endpoints is already measured by the framework's
 * own {@code http.server.requests} observation; a service-level @Observed on
 * the same path would double-count. That is why {@code search} (a read-only
 * projection module) has no entries, while every module that mutates business
 * state does. The exact pinned inventory:
 * <ul>
 *   <li>availability — timeoff.create</li>
 *   <li>booking — create, confirm, complete, cancel, auto.cancel</li>
 *   <li>catalog — create.listing</li>
 *   <li>disputes — open, resolve</li>
 *   <li>identity — sync.oidc, role.update</li>
 *   <li>ledger — credit.payment, debit.commission (money movement)</li>
 *   <li>media — upload.request, upload.confirm, asset.delete (layer 8 — the
 *       presigned media channel; commands per policy, reads via
 *       http.server.requests)</li>
 *   <li>messaging — send</li>
 *   <li>notifications — mark.read</li>
 *   <li>payments — process, confirm, cancel; psp.create + psp.webhook
 *       (layer 9 — the real PSP channel; the webhook observation lives on
 *       the Stripe entry point, not the shared dispatch helper, so the
 *       legacy HMAC channel keeps its exact legacy behavior)</li>
 *   <li>pricing — calculate, rule.create, rule.activate, rule.deactivate,
 *       rule.delete</li>
 *   <li>provider — create, update, verify, suspend</li>
 *   <li>reviews — create, update</li>
 *   <li>shared infra — email.send (the open MAIL-provider gate: whatever the
 *       provider decision, delivery latency and outcome become visible)</li>
 * </ul>
 *
 * <p>File-location note: surefire runs with the module basedir
 * ({@code marketplace-app}) as working directory, so the repo root resolves
 * to {@code ../}; running from the repo root is handled by the fallback.
 */
class ObservationCoverageFilesTest {

    private static final Pattern OBSERVED = Pattern.compile(
            "@Observed\\s*\\(\\s*name\\s*=\\s*\"([^\"]+)\"\\s*\\)");

    /** module (from path segment) -> sorted observation names. */
    private static final Map<String, List<String>> EXPECTED = Map.ofEntries(
            Map.entry("marketplace-availability", List.of("availability.timeoff.create")),
            Map.entry("marketplace-booking", List.of(
                    "booking.auto.cancel", "booking.cancel", "booking.complete",
                    "booking.confirm", "booking.create")),
            Map.entry("marketplace-catalog", List.of("catalog.create.listing")),
            Map.entry("marketplace-disputes", List.of("dispute.open", "dispute.resolve")),
            Map.entry("marketplace-identity", List.of("user.role.update", "user.sync.oidc")),
            Map.entry("marketplace-ledger", List.of(
                    "ledger.credit.payment", "ledger.debit.commission")),
            Map.entry("marketplace-media", List.of(
                    "media.asset.delete", "media.upload.confirm", "media.upload.request")),
            Map.entry("marketplace-messaging", List.of("messaging.send")),
            Map.entry("marketplace-notifications", List.of("notification.mark.read")),
            Map.entry("marketplace-payments", List.of(
                    "payment.cancel", "payment.confirm", "payment.process",
                    "payment.psp.create", "payment.psp.webhook")),
            Map.entry("marketplace-pricing", List.of(
                    "pricing.calculate", "pricing.rule.activate",
                    "pricing.rule.create", "pricing.rule.deactivate",
                    "pricing.rule.delete")),
            Map.entry("marketplace-provider", List.of(
                    "provider.create", "provider.suspend", "provider.update",
                    "provider.verify")),
            Map.entry("marketplace-reviews", List.of("review.create", "review.update")),
            Map.entry("marketplace-platform-infra", List.of("email.send")));

    private Path repoRoot() {
        Path fromModule = Paths.get("../");
        if (Files.isDirectory(fromModule.resolve(".github"))) {
            return fromModule;
        }
        return Paths.get(".");
    }

    @Test
    void observationAspectSwitchesAreLive() throws IOException {
        // 1) yml switch: management.observations.annotations.enabled=true
        String yml = Files.readString(repoRoot()
                .resolve("marketplace-app/src/main/resources/application.yml"));
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(yml);
        @SuppressWarnings("unchecked")
        Map<String, Object> management = (Map<String, Object>) root.get("management");
        assertThat(management).as("management section must exist").isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> observations = (Map<String, Object>) management.get("observations");
        assertThat(observations).as("management.observations must exist").isNotNull();
        assertThat(observations.get("annotations"))
                .as("management.observations.annotations must exist")
                .isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> annotations = (Map<String, Object>) observations.get("annotations");
        assertThat(annotations.get("enabled"))
                .as("management.observations.annotations.enabled must be true — "
                        + "without it the ObservedAspect never registers and every "
                        + "@Observed in the reactor is dead config")
                .isEqualTo(true);

        // 2) aspectj weaver on the classpath (platform-infera pom, BOM version)
        String infraPom = Files.readString(repoRoot()
                .resolve("marketplace-platform-infra/pom.xml"));
        assertThat(infraPom)
                .as("spring-boot-starter-aspectj must stay a dependency of "
                        + "marketplace-platform-infra — ObservedAspectConfiguration is "
                        + "@ConditionalOnClass(ObservedAspect + Advice) and the weaver "
                        + "is what satisfies the Advice half")
                .contains("spring-boot-starter-aspectj");
    }

    @Test
    void observedInventoryMatchesThePinExactly() throws IOException {
        Map<String, List<String>> actual = scanInventory();
        Map<String, List<String>> expected = new TreeMap<>(EXPECTED);

        assertThat(new TreeMap<>(actual))
                .as("the @Observed inventory must match the pin exactly — a missing "
                        + "entry means a business command went dark; an extra entry "
                        + "means someone added an observation outside the "
                        + "commands-not-reads policy (update this pin deliberately "
                        + "in the same PR)")
                .containsExactlyEntriesOf(expected);
    }

    /**
     * Scans every module's main sources for @Observed(name="...") and groups
     * the names by Maven module. testFixtures and test sources are excluded.
     */
    private Map<String, List<String>> scanInventory() throws IOException {
        Map<String, List<String>> byModule = new HashMap<>();
        try (Stream<Path> files = Files.walk(repoRoot())) {
            files.filter(p -> {
                        String s = p.toString().replace('\\', '/');
                        return s.contains("/src/main/java/")
                                && s.endsWith(".java")
                                && s.contains("/marketplace-");
                    })
                    .forEach(p -> {
                        String module = moduleOf(p);
                        try {
                            Matcher m = OBSERVED.matcher(Files.readString(p));
                            while (m.find()) {
                                byModule.computeIfAbsent(module, k -> new java.util.ArrayList<>())
                                        .add(m.group(1));
                            }
                        } catch (IOException e) {
                            throw unChecked(e);
                        }
                    });
        }
        byModule.replaceAll((k, v) -> v.stream().sorted().toList());
        return byModule;
    }

    private static String moduleOf(Path p) {
        for (Path seg : p) {
            String s = seg.toString();
            if (s.startsWith("marketplace-")) {
                return s;
            }
        }
        return "unknown";
    }

    private static RuntimeException unChecked(IOException e) {
        return new RuntimeException(e);
    }
}
