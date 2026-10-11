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
 *   <li>community — membership.join, membership.leave (L41 — the
 *       neighborhood membership anchor's two commands; the read stays
 *       unobserved per policy); membership.verification.request,
 *       membership.verification.review, membership.verification.queue
 *       (the verification lifecycle's own three command points — the
 *       member's request, the administrative review, and the queue read
 *       the review rides: a queue read that feeds an administrative
 *       decision surface carries its own observation, the moderation
 *       queue's documented exception family); post.create, post.comment, post.delete
 *       (L42 — the feed layer's three commands; the reads stay
 *       unobserved per the same policy); report.create, report.resolve
 *       (L45 — the moderation layer's two commands: a member's report
 *       and the administrative resolve; the queue read stays
 *       unobserved per the same policy); post.react, post.unreact
 *       (L47 — the reactions layer's two commands, the ONE toggle's two
 *       directions; the feed's count/voice reads stay unobserved per
 *       the same policy)</li>
 *   <li>disputes — open, resolve</li>
 *   <li>identity — sync.oidc, role.update; follow.create, follow.delete
 *       (W4 — the provider-follow pair's two commands, the one toggle's
 *       two directions; the /me list read and the activation bridge's
 *       fan-out stay unobserved per policy — the bridge runs inside the
 *       registry listener's own unit, not a proxied business entry)</li>
 *   <li>ledger — credit.payment, debit.commission, debit.refund (money
 *       movement; the refund debit mirrors the credit — L24); debit.ads
 *       (W5 — the ad bill's debit: the frozen window charge that consumes
 *       the campaign's budget, a money movement like its siblings)</li>
 *   <li>media — upload.request, upload.confirm, asset.delete (layer 8 — the
 *       presigned media channel; commands per policy, reads via
 *       http.server.requests); media.review.upload.request,
 *       media.review.upload.confirm, media.review.asset.delete (W1 — the
 *       review-photo channel on the same rules, one command surface per
 *       action; the real observation names as declared on the service, not
 *       the shortened form — CodeRabbit W1 r2); upload.request.post (L48 —
 *       the SAME channel's post-targeted declare, its own command point:
 *       the member flow's author gate and per-post position lock are its
 *       own work, measured apart from the provider flow's)</li> *   <li>messaging — send</li>
 *   <li>jobs — create, close, application.create, application.move,
 *       application.withdraw (B-12 — the employment vertical's five
 *       commands, the reviews-pattern module)</li>
 *   <li>notifications — delete, mark.all.read, mark.read, preferences.update
 *       (delete + mark.all.read are B-07's feed pair; preferences.update is L22 —
 *       the per-channel unsubscribe switch write)</li>
 *   <li>payments — process, confirm, cancel; psp.create + psp.webhook
 *       (layer 9 — the real PSP channel; the webhook observation lives on
 *       the Stripe entry point, not the shared dispatch helper, so the
 *       legacy HMAC channel keeps its exact legacy behavior)</li>
 *   <li>pricing — calculate (+ calculate.window — the L26 day-sliced
 *       quote engine, the same read exception as the flat quote),
 *       rule.create, rule.activate, rule.deactivate, rule.delete;
 *       calendar.weekend.upsert, calendar.weekend.delete,
 *       calendar.seasonal.create, calendar.seasonal.update,
 *       calendar.seasonal.delete (L26 — the host's price-calendar commands;
 *       the calendar READ stays unobserved per policy)</li>
 *   <li>provider — create, update, verify, suspend; rating.stats (L21 — the
 *       event-driven stored-rating recompute; the reads stay unobserved)</li>
 *   <li>reviews — create, update, create.reverse, reply (I8 + L21);
 *       create.organic, moderate.approve, moderate.reject, moderate.hide,
 *       vote, vote.remove (W1 §4.5 — the organic path's commands and the
 *       moderation outcomes; the moderation queue READ stays unobserved
 *       per the same policy)</li>
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
            Map.entry("marketplace-catalog", List.of(
                    "catalog.category-attributes.register",
                    "catalog.category-attributes.remove",
                    "catalog.category-attributes.update",
                    "catalog.create.listing",
                    // W3 (G19): the favorites surface's two commands — the
                    // same commands-not-reads policy as every house entry.
                    "catalog.favorites.save", "catalog.favorites.unsave",
                    // A-17 (C.7 — the M1 store root): the registration command;
                    // the owner read stays unobserved (the commands-not-reads policy).
                    // B-16 (C.8 — the M2 store wave) adds the Q&A pair's two
                    // commands — the same commands-not-reads policy; the wave's
                    // two public reads stay unobserved.
                    "catalog.product.answer.publish", "catalog.product.question.ask",
                    "catalog.product.register")),
            Map.entry("marketplace-community", List.of(
                    "community.event.create", "community.event.delete",
                    "community.event.rsvp", "community.event.unrsvp",
                    "community.group.join", "community.group.leave",
                    "community.market.create", "community.market.delete",
                    "community.membership.join", "community.membership.leave",
                    "community.membership.verification.queue",
                    "community.membership.verification.request",
                    "community.membership.verification.review",
                    "community.poll.create", "community.poll.vote", "community.poll.withdraw",
                    "community.post.comment", "community.post.create", "community.post.delete",
                    // waves D1-D4 (JT-20): the owner's lost&found lifecycle
                    // resolution command (ACTIVE -> RESOLVED/FOUND).
                    "community.post.lostFoundState",
                    "community.post.react", "community.post.unreact",
                    // B-19 (compliance plan C.11): the automatic moderation
                    // rules' operator commands — register/revise/toggle/retire.
                    // The engine's own evaluation rides the creation command's
                    // span (community.report.create) exactly as the human
                    // resolve's hide rides community.report.resolve: the
                    // commands-not-reads policy, the automatic action a side
                    // effect of the report's creation.
                    "community.report.create", "community.report.resolve",
                    "community.rule.register", "community.rule.retire",
                    "community.rule.toggle", "community.rule.update")),
            Map.entry("marketplace-disputes", List.of("dispute.open", "dispute.resolve")),
            Map.entry("marketplace-identity", List.of(
                    "email.verification.complete", "email.verification.resend", "email.verification.send",
                    "password.reset.complete", "password.reset.request",
                    "provider.follow.create", "provider.follow.delete",
                    "user.audit.purge", "user.content.purge", "user.pseudonymize", "user.role.update",
                    "user.self.deletion", "user.status.update", "user.sync.oidc")),
            // B-13 (compliance plan C.3): the institution registry's three
            // business commands (register + the verification request + the
            // review verdict) — the module is not yet in the app reactor
            // (CR-7's wiring rows), but this guard scans the source tree:
            // the pin carries the commands from the day they exist.
            Map.entry("marketplace-institutions", List.of(
                    "institution.register", "institution.verification.request",
                    "institution.verification.review",
                    // waves D1-D4 (CMP-46/JT-10): the delegated urgent-alert
                    // surface — source lifecycle (create + verification
                    // request/review) and the publish/withdraw pair; the
                    // eligibility gates themselves are reads, not commands.
                    "urgentAlert.publish", "urgentAlert.source.create",
                    "urgentAlert.source.verification.request",
                    "urgentAlert.source.verification.review", "urgentAlert.withdraw")),
            // B-14 (compliance plan C.4): the knowledge guide's three
            // business commands (the contribution + the revision + the
            // withdrawal) — the same source-tree-scan discipline.
            Map.entry("marketplace-knowledge", List.of(
                    "knowledge.entry.create", "knowledge.entry.update",
                    "knowledge.entry.withdraw")),
            // B-15 (compliance plan C.5): the console's business commands
            // (the flag/config/geo-setting registrations + updates) — the
            // reads (view/flags/metrics/audit/effective) carry no
            // observation by the commands-not-reads policy. B-18 (C.10)
            // adds the geographic setting pair.
            Map.entry("marketplace-console", List.of(
                    "console.config.register", "console.config.update",
                    "console.flag.register", "console.flag.update",
                    "console.geo.setting.register", "console.geo.setting.update")),
            Map.entry("marketplace-jobs", List.of(
                    "job.application.create", "job.application.move",
                    "job.application.withdraw", "job.close", "job.create")),
            Map.entry("marketplace-ledger", List.of(
                    "ledger.credit.payment", "ledger.debit.ads", "ledger.debit.commission", "ledger.debit.refund")),
            Map.entry("marketplace-media", List.of(
                    "media.asset.delete", "media.review.asset.delete",
                    "media.review.upload.confirm", "media.review.upload.request",
                    "media.thumbnail.process", "media.upload.confirm",
                    "media.upload.request", "media.upload.request.post")),            Map.entry("marketplace-messaging", List.of("messaging.send")),
            Map.entry("marketplace-orders", List.of(
                    // A-11 (compliance plan wave C: C.1) — the order machine's
                    // four commands (the reads stay unobserved per the
                    // commands-not-reads policy).
                    "order.cancel", "order.confirm", "order.fulfill", "order.place")),
            Map.entry("marketplace-notifications", List.of(
                    "notification.delete", "notification.mark.all.read",
                    "notification.mark.read", "notification.preferences.update")),
            Map.entry("marketplace-payments", List.of(
                    "payment.cancel", "payment.confirm", "payment.fail", "payment.process",
                    "payment.psp.create", "payment.psp.refund", "payment.psp.webhook")),
            Map.entry("marketplace-pricing", List.of(
                    "pricing.calculate", "pricing.calculate.window",
                    "pricing.calendar.seasonal.create",
                    "pricing.calendar.seasonal.delete",
                    "pricing.calendar.seasonal.update",
                    "pricing.calendar.weekend.delete",
                    "pricing.calendar.weekend.upsert",
                    "pricing.currency.convert",
                    "pricing.rule.activate",
                    "pricing.rule.create", "pricing.rule.deactivate",
                    "pricing.rule.delete")),
            Map.entry("marketplace-provider", List.of(
                    "provider.business-hours.replace", "provider.create",
                    "provider.rating.stats", "provider.service-areas.add",
                    "provider.service-areas.remove", "provider.services.add",
                    "provider.services.move", "provider.services.remove",
                    "provider.services.update", "provider.suspend",
                    "provider.update", "provider.verification.confirm",
                    "provider.verification.reject", "provider.verification.submit",
                    "provider.verify")),
            Map.entry("marketplace-realestate", List.of("realestate.property.upsert")),
            Map.entry("marketplace-reviews", List.of(
                    "review.create", "review.create.organic", "review.create.reverse",
                    "review.moderate.approve", "review.moderate.hide",
                    "review.moderate.reject", "review.reply", "review.update",
                    "review.vote", "review.vote.remove")),
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
     *
     * <p>Names are deduplicated: the inventory is a set of observation NAMES
     * per module, not a count of annotated methods. One command may expose
     * several proxy-visible entry forms (e.g. {@code catalog.create.listing}
     * — the six-argument SPI/GraphQL form and the seven-argument REST form
     * after I6): every external call crosses the proxy exactly once at its
     * own entry form (the official AOP self-invocation rule — the delegation
     * between the forms runs unproxied and cannot double-count), so both
     * forms legitimately carry the SAME name. A duplicate NAME in the scan
     * is that pattern, not a new observation.
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
        byModule.replaceAll((k, v) -> v.stream().sorted().distinct().toList());
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
