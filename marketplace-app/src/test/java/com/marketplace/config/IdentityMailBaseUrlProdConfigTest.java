package com.marketplace.config;

import com.marketplace.identity.IdentityMailProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gate test for finding W2 of the 2026-11-10 adversarial behavioral review
 * ({@code docs/governance/adversarial-behavioral-review-2026-11-10.md}):
 * {@code IdentityMailProperties}' own javadoc claimed "production binds its
 * own public origin", yet no profile document ever pinned
 * {@code marketplace.identity.mail-base-url} — the record's
 * {@code @DefaultValue("http://localhost:3000")} therefore flowed into every
 * deployed composition, and password-reset / email-verification deep links
 * pointed at localhost.
 *
 * <p><b>Closed by the documented fail-fast contract for client-facing
 * production origins — round 2 (the CodeRabbit Major finding,
 * 2026-11-11):</b> the deployable profiles bind
 * {@code ${MARKETPLACE_IDENTITY_MAIL_BASE_URL}} with no default (the exact
 * pattern {@code AUTH_SERVER_ISSUER}, {@code CORS_ALLOWED_ORIGINS} and
 * {@code MAIL_FROM} apply in {@code application-prod.yml}), AND the
 * record's compact constructor is the binding-time wall that makes the
 * failure REAL: Spring Boot 4.1.2's
 * {@code PropertySourcesPlaceholdersResolver} passes an unresolved
 * placeholder through LITERALLY (verified against the framework bytecode:
 * the helper is built with {@code ignoreUnresolvablePlaceholders=true}),
 * so without the constructor's rejection an unset variable would bind the
 * garbage string {@code ${MARKETPLACE_IDENTITY_MAIL_BASE_URL}} into
 * {@code mailBaseUrl} instead of failing startup. Staging carries the same
 * no-default binding at parity (it submits real mail through the real
 * production path — the staging mail contract in
 * {@code application-staging.yml}); the deployment-target side of the
 * contract is enforced by {@code PlaceholderTargetMatrixGuardTest}
 * (D10/G2: the no-default name must be declared in
 * {@code .railway/railway.ts}) — the {@code MailChannelProperties}
 * precedent is the house shape this follows (INV-2 explicit settings,
 * D6 production keys fail fast).</p>
 *
 * <p>Unit level (no Spring context), mirroring
 * {@code ForwardHeadersProdConfigTest}: the yml documents are pinned as
 * text, while the BINDING itself — the official {@code Binder} +
 * {@code PropertySourcesPlaceholdersResolver} over the real composition
 * documents — proves the startup failure the finding demands.</p>
 */
class IdentityMailBaseUrlProdConfigTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void prodProfileBindsIdentityMailBaseUrlWithoutDefaultToFailFast() throws Exception {
        // W2: the localhost:3000 development default must never leak into a
        // production composition — the binding carries no default so the
        // value is the placeholder itself, and the record's constructor
        // (the binding-time wall below) rejects it when the variable is absent.
        assertThat(property("application-prod.yml", "marketplace.identity.mail-base-url"))
                .isEqualTo("${MARKETPLACE_IDENTITY_MAIL_BASE_URL}");
    }

    @Test
    void stagingProfileBindsIdentityMailBaseUrlWithoutDefaultToFailFast() throws Exception {
        // Staging parity: the sandbox mails real account links through the
        // real production path, so its link origin is exactly as mandatory
        // as production's.
        assertThat(property("application-staging.yml", "marketplace.identity.mail-base-url"))
                .isEqualTo("${MARKETPLACE_IDENTITY_MAIL_BASE_URL}");
    }

    @Test
    void nonDeployableProfilesLeaveTheKeyUnsetSoTheDevDefaultFlowsFromTheRecord() throws Exception {
        // Trust boundary: localhost:3000 is the development client origin only.
        // It must flow solely from IdentityMailProperties' @DefaultValue — no
        // non-deployable profile document may pin the client link origin.
        for (String yml : new String[] { "application.yml", "application-dev.yml", "application-test.yml" }) {
            assertThat(property(yml, "marketplace.identity.mail-base-url"))
                    .as("%s must not pin the client link origin", yml)
                    .isNull();
        }
    }

    /**
     * W2 review round 2 (CodeRabbit Major, 2026-11-11) — the REAL binding:
     * the official {@link Binder} over the actual production composition
     * documents with {@code MARKETPLACE_IDENTITY_MAIL_BASE_URL} ABSENT
     * everywhere (exactly a deploy that forgot the variable). Boot's
     * resolver passes the unresolved placeholder through literally, and the
     * record's compact constructor must fail the bind with the operational
     * message naming the variable — the startup failure the finding demands,
     * proven against the same mechanism production boots through.
     */
    @Test
    void prodCompositionWithoutTheVariableFailsBindingWithTheOperationalMessage() throws Exception {
        List<PropertySource<?>> composition = new ArrayList<>();
        composition.addAll(loader.load("application.yml", new ClassPathResource("application.yml")));
        composition.addAll(loader.load("application-prod.yml", new ClassPathResource("application-prod.yml")));

        Binder binder = new Binder(configurationSources(composition),
                new PropertySourcesPlaceholdersResolver(composition));

        assertThatThrownBy(() -> binder.bind("marketplace.identity",
                Bindable.of(IdentityMailProperties.class)))
                .isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasStackTraceContaining("UNRESOLVED")
                .hasStackTraceContaining("MARKETPLACE_IDENTITY_MAIL_BASE_URL");
    }

    /**
     * The happy path of the same wall: with the variable present in the
     * deployment environment, the placeholder RESOLVES and the record
     * binds the configured public origin — the fail-fast is specifically
     * about the absent variable, never about a legitimate deployment.
     */
    @Test
    void prodCompositionWithTheVariableBindsTheConfiguredPublicOrigin() throws Exception {
        List<PropertySource<?>> composition = new ArrayList<>();
        composition.addAll(loader.load("application.yml", new ClassPathResource("application.yml")));
        composition.addAll(loader.load("application-prod.yml", new ClassPathResource("application-prod.yml")));
        composition.add(new MapPropertySource("testDeploymentEnvironment",
                Map.of("MARKETPLACE_IDENTITY_MAIL_BASE_URL", "https://client.example.com")));

        Binder binder = new Binder(configurationSources(composition),
                new PropertySourcesPlaceholdersResolver(composition));

        var bound = binder.bind("marketplace.identity", Bindable.of(IdentityMailProperties.class));
        assertThat(bound.isBound()).isTrue();
        assertThat(bound.get().mailBaseUrl()).isEqualTo("https://client.example.com");
    }

    /**
     * The wall rejects the literal placeholder even WITHOUT any profile
     * document: the record constructor alone (the MailChannelProperties
     * shape) is the guarantee — this is the exact regression pin for the
     * resolver's literal-passthrough behavior.
     */
    @Test
    void theRecordItselfRejectsTheUnresolvedPlaceholderLiteral() {
        assertThatThrownBy(() -> new IdentityMailProperties(
                "${MARKETPLACE_IDENTITY_MAIL_BASE_URL}",
                java.time.Duration.ofMinutes(30),
                java.time.Duration.ofHours(24),
                java.time.Duration.ofSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNRESOLVED")
                .hasMessageContaining("MARKETPLACE_IDENTITY_MAIL_BASE_URL");
    }

    /**
     * Review round 3's own leg: an origin carrying a query or a fragment is
     * NOT a client origin — the deep links minted on it would be malformed
     * (a second '?' or a '#' breaks the token's link) — the wall rejects both.
     */
    @Test
    void theRecordRejectsQueryAndFragmentCarryingOrigins() {
        for (String invalid : new String[] { "https://app.example.com?next=/x", "https://app.example.com#section" }) {
            assertThatThrownBy(() -> new IdentityMailProperties(
                    invalid,
                    java.time.Duration.ofMinutes(30),
                    java.time.Duration.ofHours(24),
                    java.time.Duration.ofSeconds(60)))
                    .as("origin '%s' must be rejected", invalid)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no query or fragment");
        }
    }

    /**
     * Review round 4 (2026-11-13, Minor): URI schemes are case-insensitive
     * (java.net.URI) — "HTTPS://app.example.com" is a legitimate https
     * client origin and must bind through the wall, not fail it. Review
     * round 6 (2026-11-13): the stored origin carries the canonical
     * lowercase scheme form (RFC 3986 §3.1: "Although schemes are
     * case-insensitive, the canonical form is lowercase").
     */
    @Test
    void theRecordAcceptsAnUppercaseSchemeOriginAndCanonicalizesIt() {
        var properties = new IdentityMailProperties(
                "HTTPS://app.example.com",
                java.time.Duration.ofMinutes(30),
                java.time.Duration.ofHours(24),
                java.time.Duration.ofSeconds(60));
        assertThat(properties.mailBaseUrl()).isEqualTo("https://app.example.com");
    }

    /**
     * Review round 6 (2026-11-13): a public client origin never carries
     * userinfo (measured: URI.create("https://user@app.example.com") parses
     * with getUserInfo()="user" — credentials have no place in a mail deep
     * link's base) — the wall rejects userinfo-carrying origins.
     */
    @Test
    void theRecordRejectsUserinfoCarryingOrigins() {
        for (String invalid : new String[] { "https://user@app.example.com", "https://user:secret@app.example.com" }) {
            assertThatThrownBy(() -> new IdentityMailProperties(
                    invalid,
                    java.time.Duration.ofMinutes(30),
                    java.time.Duration.ofHours(24),
                    java.time.Duration.ofSeconds(60)))
                    .as("origin '%s' must be rejected", invalid)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no userinfo");
        }
    }

    /**
     * Review round 4 (2026-11-13, Minor): an origin carrying a non-root path
     * silently rewrites the declared deep-link routes
     * ("https://app.example.com/account" + "/reset-password" mints
     * "/account/reset-password", not the contract's route) — the wall
     * rejects every non-root path with the same operational shape.
     */
    @Test
    void theRecordRejectsNonRootPathOrigins() {
        for (String invalid : new String[] { "https://app.example.com/account", "https://app.example.com/app/" }) {
            assertThatThrownBy(() -> new IdentityMailProperties(
                    invalid,
                    java.time.Duration.ofMinutes(30),
                    java.time.Duration.ofHours(24),
                    java.time.Duration.ofSeconds(60)))
                    .as("origin '%s' must be rejected", invalid)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no path");
        }
    }

    /**
     * Review round 4 (2026-11-13, Minor): a lone trailing root slash is the
     * SAME origin — the wall normalizes it away so the deep-link
     * concatenation can never mint a double slash
     * ("https://app.example.com/" + "/reset-password?token=...").
     */
    @Test
    void theRecordStripsALoneTrailingRootSlash() {
        var properties = new IdentityMailProperties(
                "https://app.example.com/",
                java.time.Duration.ofMinutes(30),
                java.time.Duration.ofHours(24),
                java.time.Duration.ofSeconds(60));
        assertThat(properties.mailBaseUrl()).isEqualTo("https://app.example.com");
    }

    /**
     * Review round 5 (2026-11-13, Minor): java.net.URI accepts ports beyond
     * the TCP range verbatim (measured: getPort()=70000 parses fine — the
     * parser validates digits, never the 0-65535 range), so the wall itself
     * rejects out-of-range ports; an undefined port and valid ports —
     * including the 65535 boundary — stay accepted.
     */
    @Test
    void theRecordRejectsOutOfRangePortsAndAcceptsTheBoundary() {
        assertThatThrownBy(() -> new IdentityMailProperties(
                "https://app.example.com:70000",
                java.time.Duration.ofMinutes(30),
                java.time.Duration.ofHours(24),
                java.time.Duration.ofSeconds(60)))
                .as("origin port 70000 must be rejected")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port within 0-65535");
        var boundary = new IdentityMailProperties(
                "https://app.example.com:65535",
                java.time.Duration.ofMinutes(30),
                java.time.Duration.ofHours(24),
                java.time.Duration.ofSeconds(60));
        assertThat(boundary.mailBaseUrl()).isEqualTo("https://app.example.com:65535");
    }

    private String property(String yml, String key) throws java.io.IOException {
        List<PropertySource<?>> sources = loader.load(yml, new ClassPathResource(yml));
        assertThat(sources).as("%s must load", yml).isNotEmpty();
        for (PropertySource<?> source : sources) {
            Object value = source.getProperty(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /**
     * The composition's yml sources flattened into the official
     * {@link MapConfigurationPropertySource} binder input — the same
     * ConfigurationPropertySource shape Boot binds @ConfigurationProperties
     * from in a booted application.
     */
    private Iterable<ConfigurationPropertySource> configurationSources(List<PropertySource<?>> composition) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (PropertySource<?> source : composition) {
            if (source instanceof MapPropertySource mapSource) {
                for (Map.Entry<String, Object> entry : mapSource.getSource().entrySet()) {
                    // The yml loader serves OriginTrackedValue wrappers; the
                    // binder's placeholder resolution only applies to plain
                    // String values — unwrap to the value a booted
                    // environment's adapter hands the binder.
                    Object value = entry.getValue();
                    if (value instanceof org.springframework.boot.origin.OriginTrackedValue tracked) {
                        value = tracked.getValue();
                    }
                    merged.put(entry.getKey(), value);
                }
            }
        }
        return List.of(new MapConfigurationPropertySource(merged));
    }
}
