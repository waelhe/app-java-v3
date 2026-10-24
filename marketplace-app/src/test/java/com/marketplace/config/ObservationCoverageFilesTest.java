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
 *   <li>provider — create, update, verify, suspend; team.add, team.list,
 *       team.revoke (W5b — the delegation surface's three commands; the
 *       roster read rides the owner's decision surface, the documented
 *       queue-read exception family); rating.stats (L21 — the
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
    /**
     * The pinned inventory, regenerated from the measured source scan in
     * the post-merge integration round of PR #696: the campaign rounds
     * (agent-1's identity/CMS packet, the security and multi-instance
     * rotation rounds, the sibling agents' catalog/orders/media packets)
     * landed their @Observed commands WITHOUT re-pinning — exactly the
     * "config that lies" defect this gate exists to catch. The pin now
     * carries the full measured inventory: every entry is a business
     * command per the commands-not-reads policy (the CMS menu/page and
     * account/staff lifecycle commands of the identity and console
     * owning use cases, the token lifecycle's three commands, and every
     * previously pinned wave).
     */
    private static final Map<String, List<String>> EXPECTED = Map.ofEntries(
            Map.entry("marketplace-app", List.of(
                    "graphql.token.create",
                    "graphql.token.refresh",
                    "graphql.token.verify")),
            Map.entry("marketplace-availability", List.of("availability.timeoff.create")),
            Map.entry("marketplace-booking", List.of(
                    "booking.auto.cancel",
                    "booking.cancel",
                    "booking.complete",
                    "booking.confirm",
                    "booking.create")),
            Map.entry("marketplace-catalog", List.of(
                    "catalog.attribute-definition.register",
                    "catalog.attribute-definition.update",
                    "catalog.attribute-option.register",
                    "catalog.attribute-option.remove",
                    "catalog.category-attributes.register",
                    "catalog.category-attributes.remove",
                    "catalog.category-attributes.update",
                    "catalog.create.listing",
                    "catalog.favorites.save",
                    "catalog.favorites.unsave",
                    "catalog.product-type-attribute.assign",
                    "catalog.product-type-attribute.remove",
                    "catalog.product-type.register",
                    "catalog.product-type.remove",
                    "catalog.product-type.update",
                    "catalog.product.answer.publish",
                    "catalog.product.question.ask",
                    "catalog.product.register",
                    "catalog.store-category.register",
                    "catalog.store-category.remove",
                    "catalog.store-category.update",
                    "catalog.variant.register")),
            Map.entry("marketplace-community", List.of(
                    "community.event.create",
                    "community.event.delete",
                    "community.event.rsvp",
                    "community.event.unrsvp",
                    "community.group.join",
                    "community.group.leave",
                    "community.market.create",
                    "community.market.delete",
                    "community.market.markSold",
                    "community.membership.join",
                    "community.membership.leave",
                    "community.membership.verification.queue",
                    "community.membership.verification.request",
                    "community.membership.verification.review",
                    "community.poll.create",
                    "community.poll.vote",
                    "community.poll.withdraw",
                    "community.post.comment",
                    "community.post.create",
                    "community.post.delete",
                    "community.post.lostFoundState",
                    "community.post.react",
                    "community.post.unreact",
                    "community.report.create",
                    "community.report.resolve",
                    "community.rule.register",
                    "community.rule.retire",
                    "community.rule.toggle",
                    "community.rule.update")),
            Map.entry("marketplace-console", List.of(
                    "cms.menu.bulk_delete",
                    "cms.menu.create",
                    "cms.menu.delete",
                    "cms.menu.update",
                    "cms.menu_item.bulk_delete",
                    "cms.menu_item.create",
                    "cms.menu_item.delete",
                    "cms.menu_item.move",
                    "cms.menu_item.translate",
                    "cms.menu_item.update",
                    "cms.navigation.assign",
                    "cms.page.bulk_delete",
                    "cms.page.bulk_publish",
                    "cms.page.create",
                    "cms.page.delete",
                    "cms.page.translate",
                    "cms.page.update",
                    "cms.page_type.bulk_delete",
                    "cms.page_type.create",
                    "cms.page_type.delete",
                    "cms.page_type.update",
                    "console.config.register",
                    "console.config.update",
                    "console.flag.flip",
                    "console.flag.register",
                    "console.flag.update",
                    "console.geo.setting.flip",
                    "console.geo.setting.register",
                    "console.geo.setting.update")),
            Map.entry("marketplace-delivery", List.of(
                    "delivery.courier.availability",
                    "delivery.courier.reactivate",
                    "delivery.courier.suspend",
                    "delivery.dispatch.claim",
                    "delivery.dispatch.queues",
                    "delivery.dispatch.sweep",
                    "delivery.job.transition",
                    "delivery.order.leg.create",
                    "delivery.order.leg.split",
                    "delivery.order.quote.create",
                    "delivery.payment.settle",
                    "delivery.quote.create",
                    "delivery.reconciliation.window",
                    "delivery.request.cancel",
                    "delivery.request.create")),
            Map.entry("marketplace-disputes", List.of(
                    "dispute.open",
                    "dispute.open.loan",
                    "dispute.resolve")),
            Map.entry("marketplace-identity", List.of(
                    "account.address.create",
                    "account.address.delete",
                    "account.address.set_default",
                    "account.address.update",
                    "account.deletion.execute",
                    "account.deletion.request",
                    "account.email_change.confirm",
                    "account.email_change.request",
                    "account.password.change",
                    "account.tokens.deactivate_all",
                    "customer.admin.bulk_delete",
                    "customer.admin.bulk_update",
                    "customer.admin.create",
                    "customer.admin.delete",
                    "customer.admin.update",
                    "customer_type.assign_attributes",
                    "customer_type.create",
                    "customer_type.delete",
                    "customer_type.reorder_attributes",
                    "customer_type.unassign_attributes",
                    "customer_type.update",
                    "email.verification.complete",
                    "email.verification.resend",
                    "email.verification.send",
                    "follow.group.create",
                    "follow.group.delete",
                    "follow.user.create",
                    "follow.user.delete",
                    "identity.attestation.grant",
                    "identity.attestation.reject",
                    "identity.attestation.request",
                    "identity.attestation.revoke",
                    "identity.role.grant",
                    "identity.role.revoke",
                    "password.reset.complete",
                    "password.reset.request",
                    "provider.follow.create",
                    "provider.follow.delete",
                    "staff.admin.bulk_delete",
                    "staff.admin.create",
                    "staff.admin.delete",
                    "staff.admin.update",
                    "staff_notification_recipient.create",
                    "staff_notification_recipient.delete",
                    "staff_notification_recipient.update",
                    "user.address.create",
                    "user.address.delete",
                    "user.address.set_default",
                    "user.address.update",
                    "user.admin.bulk_set_active",
                    "user.audit.purge",
                    "user.content.purge",
                    "user.pseudonymize",
                    "user.role.update",
                    "user.self.deletion",
                    "user.status.update",
                    "user.sync.oidc")),
            Map.entry("marketplace-institutions", List.of(
                    "institution.register",
                    "institution.verification.request",
                    "institution.verification.review",
                    "urgentAlert.publish",
                    "urgentAlert.source.create",
                    "urgentAlert.source.verification.request",
                    "urgentAlert.source.verification.review",
                    "urgentAlert.withdraw")),
            Map.entry("marketplace-jobs", List.of(
                    "job.application.create",
                    "job.application.move",
                    "job.application.withdraw",
                    "job.close",
                    "job.create")),
            Map.entry("marketplace-knowledge", List.of(
                    "knowledge.entry.create",
                    "knowledge.entry.update",
                    "knowledge.entry.withdraw")),
            Map.entry("marketplace-ledger", List.of(
                    "ledger.credit.payment",
                    "ledger.debit.ads",
                    "ledger.debit.commission",
                    "ledger.debit.refund",
                    "ledger.delivery.payment.capture",
                    "ledger.delivery.payment.refund",
                    "ledger.delivery.revenue.recognize")),
            Map.entry("marketplace-lending", List.of(
                    "lending.loan.approve",
                    "lending.loan.cancel",
                    "lending.loan.request",
                    "lending.offer.publish")),
            Map.entry("marketplace-media", List.of(
                    "media.asset.delete",
                    "media.review.asset.delete",
                    "media.review.upload.confirm",
                    "media.review.upload.request",
                    "media.thumbnail.process",
                    "media.upload.confirm",
                    "media.upload.request",
                    "media.upload.request.post")),
            Map.entry("marketplace-messaging", List.of("messaging.send")),
            Map.entry("marketplace-notifications", List.of(
                    "notification.delete",
                    "notification.mark.all.read",
                    "notification.mark.read",
                    "notification.preferences.geo.subscribe",
                    "notification.preferences.geo.withdraw",
                    "notification.preferences.topics.update",
                    "notification.preferences.update")),
            Map.entry("marketplace-orders", List.of(
                    "order.admin.bulk-cancel",
                    "order.admin.cancel",
                    "order.admin.confirm",
                    "order.cancel",
                    "order.confirm",
                    "order.delivery.attach",
                    "order.delivery.clear",
                    "order.fulfill",
                    "order.fulfill.delivery",
                    "order.payment-intent",
                    "order.place",
                    "order.return.approve",
                    "order.return.cancel",
                    "order.return.complete",
                    "order.return.receive",
                    "order.return.reject",
                    "order.return.request",
                    "order.seller.cancel",
                    "order.seller.confirm",
                    "order.seller.fulfill")),
            Map.entry("marketplace-payments", List.of(
                    "payment.cancel",
                    "payment.confirm",
                    "payment.delivery-intent.create",
                    "payment.delivery.refund",
                    "payment.fail",
                    "payment.process",
                    "payment.psp.create",
                    "payment.psp.refund",
                    "payment.psp.webhook",
                    "payments.settlement.eligibility")),
            Map.entry("marketplace-platform-infra", List.of("email.send")),
            Map.entry("marketplace-pricing", List.of(
                    "pricing.calculate",
                    "pricing.calculate.window",
                    "pricing.calendar.seasonal.create",
                    "pricing.calendar.seasonal.delete",
                    "pricing.calendar.seasonal.update",
                    "pricing.calendar.weekend.delete",
                    "pricing.calendar.weekend.upsert",
                    "pricing.currency.convert",
                    "pricing.rule.activate",
                    "pricing.rule.create",
                    "pricing.rule.deactivate",
                    "pricing.rule.delete")),
            Map.entry("marketplace-provider", List.of(
                    "provider.business-hours.replace",
                    "provider.claim.profile.seed",
                    "provider.claim.review",
                    "provider.claim.submit",
                    "provider.claim.withdraw",
                    "provider.create",
                    "provider.fulfillment-location.update",
                    "provider.rating.stats",
                    "provider.service-areas.add",
                    "provider.service-areas.remove",
                    "provider.services.add",
                    "provider.services.move",
                    "provider.services.remove",
                    "provider.services.update",
                    "provider.suspend",
                    "provider.team.add",
                    "provider.team.list",
                    "provider.team.revoke",
                    "provider.update",
                    "provider.verification.confirm",
                    "provider.verification.reject",
                    "provider.verification.submit",
                    "provider.verify")),
            Map.entry("marketplace-realestate", List.of("realestate.property.upsert")),
            Map.entry("marketplace-reviews", List.of(
                    "review.create",
                    "review.create.organic",
                    "review.create.reverse",
                    "review.moderate.approve",
                    "review.moderate.hide",
                    "review.moderate.reject",
                    "review.reply",
                    "review.update",
                    "review.vote",
                    "review.vote.remove"))
    );


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
