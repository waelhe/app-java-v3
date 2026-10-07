package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B.2 (compliance plan §6 wave B) — the declared unit gate "فحص profile":
 * the staging SANDBOX profile is pinned here as a production-parity profile.
 *
 * <p>Official basis (Boot reference › Features › Profiles, archived live at
 * {@code scripts/a09-docs/boot-profiles.html}): profile-specific documents
 * override the base document — so the sandbox's correctness is a property of
 * {@code application-staging.yml} alone, measurable without booting anything.
 *
 * <p>The design principle under test: <b>a deployment that passes the sandbox
 * passes it behaving the way production behaves</b> — never a dev-shaped
 * approximation. Every runtime-posture key equals the production value
 * (forward headers, graceful shutdown, Redis cache, Flyway clean disabled,
 * devtools off, Modulith staleness monitor, secrets-scrubbing pattern), and
 * every fail-fast channel keeps the production no-default contract
 * (D6/INV-2: an unset variable fails at placeholder resolution, telling the
 * provisioner exactly what is missing — never a silent dev fallback).
 *
 * <p>What legitimately differs from production: the observability identity
 * ({@code deployment.environment: staging} — the sandbox must be identifiable
 * in telemetry, never labeled "production") and the trace sampling default
 * (every trace sampled — the sandbox's purpose is measuring journeys).
 */
class StagingProfileConfigTest {

    private final YamlPropertySourceLoader loader = new YamlPropertySourceLoader();

    @Test
    void stagingCarriesTheProductionRuntimePosture() throws Exception {
        // The trusted TLS-terminating proxy topology (the same contract
        // ForwardHeadersProdConfigTest pins for prod): without FRAMEWORK in
        // the sandbox, every host-sensitive surface measures dev behavior.
        assertThat(property("application-staging.yml", "server.forward-headers-strategy"))
                .isEqualTo("FRAMEWORK");
        assertThat(property("application-staging.yml", "server.tomcat.redirect-context-root"))
                .isEqualTo("false");
        assertThat(property("application-staging.yml", "server.shutdown"))
                .isEqualTo("graceful");
        assertThat(property("application-staging.yml", "spring.flyway.clean-disabled"))
                .isEqualTo("true");
        assertThat(property("application-staging.yml", "spring.devtools.restart.enabled"))
                .isEqualTo("false");
        assertThat(property("application-staging.yml", "spring.devtools.livereload.enabled"))
                .isEqualTo("false");
        assertThat(property("application-staging.yml", "spring.jpa.show-sql"))
                .isEqualTo("false");
        assertThat(property("application-staging.yml", "spring.cache.type"))
                .isEqualTo("redis");
        assertThat(property("application-staging.yml", "spring.graphql.graphiql.enabled"))
                .isEqualTo("false");
        // The Modulith staleness monitor must run in the sandbox — the
        // production property names measured and documented in
        // application-prod.yml (published/processing/resubmitted).
        assertThat(property("application-staging.yml", "spring.modulith.events.staleness.published"))
                .isEqualTo("6h");
        assertThat(property("application-staging.yml", "spring.modulith.events.staleness.processing"))
                .isEqualTo("1h");
        assertThat(property("application-staging.yml", "spring.modulith.events.staleness.resubmitted"))
                .isEqualTo("1h");
        assertThat(property("application-staging.yml", "spring.modulith.events.completion-mode"))
                .isEqualTo("archive");
        // Mail submission is the real production path (auth + TLS) into the
        // sandbox mailbox — never silently dropped.
        assertThat(property("application-staging.yml", "spring.mail.properties.mail.smtp.auth"))
                .isEqualTo("true");
        assertThat(property("application-staging.yml", "spring.mail.properties.mail.smtp.starttls.enable"))
                .isEqualTo("true");
        // Health posture parity: probes + groups + when-authorized details.
        assertThat(property("application-staging.yml", "management.endpoint.health.probes.enabled"))
                .isEqualTo("true");
        assertThat(property("application-staging.yml", "management.endpoint.health.show-details"))
                .isEqualTo("when-authorized");
        assertThat(property("application-staging.yml", "management.endpoint.health.group.readiness.include"))
                .isEqualTo("db,redis,diskSpace");
    }

    @Test
    void stagingKeepsTheProductionFailFastChannelsWithoutDefaults() throws Exception {
        // D6/INV-2 parity: sandbox resources are provisioned, not defaulted —
        // each of these fails at placeholder resolution when its environment
        // variable is unset, exactly like production.
        assertThat(property("application-staging.yml", "spring.datasource.url"))
                .isEqualTo("${DB_URL}");
        assertThat(property("application-staging.yml", "spring.datasource.username"))
                .isEqualTo("${DB_USERNAME}");
        assertThat(property("application-staging.yml", "spring.datasource.password"))
                .isEqualTo("${DB_PASSWORD}");
        assertThat(property("application-staging.yml", "spring.security.oauth2.authorizationserver.issuer"))
                .isEqualTo("${AUTH_SERVER_ISSUER}");
        assertThat(property("application-staging.yml", "marketplace.cors.allowed-origins"))
                .isEqualTo("${CORS_ALLOWED_ORIGINS}");
        assertThat(property("application-staging.yml", "marketplace.security.jwt.keystore.password"))
                .isEqualTo("${JWT_KEYSTORE_PASSWORD}");
        assertThat(property("application-staging.yml", "marketplace.security.jwt.keystore.alias"))
                .isEqualTo("${JWT_KEY_ALIAS}");
        assertThat(property("application-staging.yml", "marketplace.security.admin-seed.password"))
                .isEqualTo("${ADMIN_SEED_PASSWORD}");
        assertThat(property("application-staging.yml", "marketplace.security.oauth2.client.client-id"))
                .isEqualTo("${OAUTH_CLIENT_ID}");
        assertThat(property("application-staging.yml", "marketplace.security.oauth2.client.secret"))
                .isEqualTo("${OAUTH_CLIENT_SECRET}");
        assertThat(property("application-staging.yml", "marketplace.security.oauth2.client.redirect-uris"))
                .isEqualTo("${OAUTH_CLIENT_REDIRECT_URIS}");
        assertThat(property("application-staging.yml", "marketplace.security.oauth2.client.post-logout-redirect-uri"))
                .isEqualTo("${OAUTH_POST_LOGOUT_REDIRECT_URI}");
        assertThat(property("application-staging.yml", "marketplace.security.oauth2.public-client.client-id"))
                .isEqualTo("${OAUTH_PUBLIC_CLIENT_ID}");
        assertThat(property("application-staging.yml", "marketplace.security.oauth2.public-client.redirect-uris"))
                .isEqualTo("${OAUTH_PUBLIC_CLIENT_REDIRECT_URIS}");
        // Observability wiring is provisioned consciously in the sandbox too.
        assertThat(property("application-staging.yml", "management.otlp.metrics.export.url"))
                .isEqualTo("${OTEL_METRICS_EXPORT_URL}");
        assertThat(property("application-staging.yml", "management.opentelemetry.tracing.export.otlp.endpoint"))
                .isEqualTo("${OTEL_TRACES_EXPORT_URL}");
    }

    @Test
    void stagingCarriesItsOwnObservableIdentityAndSampling() throws Exception {
        // The environment identity: telemetry from the sandbox is staging —
        // never labeled "production" — and the journey-measuring purpose
        // samples every trace by default.
        assertThat(property("application-staging.yml",
                "management.opentelemetry.resource-attributes.deployment.environment"))
                .isEqualTo("staging");
        assertThat(property("application-staging.yml", "management.tracing.sampling.probability"))
                .isEqualTo("${OTEL_TRACING_SAMPLING:1.0}");
    }

    @Test
    void theSandboxDocumentLeavesDevAndBaseDocumentsUntouched() throws Exception {
        // Trust boundary parity: dev/base keep their own shapes — the sandbox
        // is an additional profile document, not a rewrite of the others.
        assertThat(property("application-dev.yml", "server.forward-headers-strategy")).isNull();
        assertThat(property("application-dev.yml", "spring.flyway.clean-disabled"))
                .isEqualTo("false");
        // Measured base document: the cache type is env-channeled with a
        // redis default there — the sandbox document pins redis explicitly
        // (no CACHE_TYPE escape hatch: the sandbox IS redis-backed).
        assertThat(property("application.yml", "spring.cache.type"))
                .isEqualTo("${CACHE_TYPE:redis}");
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
}
