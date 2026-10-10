package com.marketplace.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the constructor-binding contract of {@link MarketplaceProperties}: nested record
 * components primed with an empty {@link org.springframework.boot.context.properties.bind.DefaultValue}
 * are always bound to a non-null instance, even when the corresponding keys are absent.
 *
 * <p>This is exactly the regression that failed {@code MarketplaceApplicationTest.contextLoads}
 * in CI: {@code marketplace.security.oauth2} is not defined outside production, yet the
 * {@link com.marketplace.shared.security.OAuth2ClientSecretInitializer} dereferences it.
 *
 * @see <a href="https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.constructor-binding">
 *      Spring Boot — Type-safe Configuration Properties — Constructor binding — @DefaultValue</a>
 */
class MarketplacePropertiesBindingTest {

    @Test
    void bindsAbsentOauth2SectionToNonNullDefaults() {
        Map<String, Object> source = Map.of("marketplace.security.session.max-sessions", "2");

        MarketplaceProperties properties = new Binder(ConfigurationPropertySources
                .from(new MapPropertySource("test", source)))
                .bind("marketplace", Bindable.of(MarketplaceProperties.class))
                .get();

        assertThat(properties.security()).isNotNull();
        MarketplaceProperties.Security.OAuth2 oauth2 = properties.security().oauth2();
        assertThat(oauth2).isNotNull();
        assertThat(oauth2.client()).isNotNull();
        assertThat(oauth2.client().clientId()).isEmpty();
        assertThat(oauth2.client().secret()).isEmpty();
    }

    /**
     * CodeRabbit #241: SecurityConfig dereferences security().jwt().keystore()
     * and security().session().maxSessions() unconditionally, and it consumes
     * cors().allowedOrigins() (SecurityConfig:185) — every dereference-prone
     * section must bind non-null when its keys are absent, exactly like the
     * OAuth2 section above. One bound key (the same session key as the first
     * test) makes the {@code marketplace} prefix exist for the Binder — the
     * realistic production shape, where application.yml always carries some
     * marketplace.* keys while whole sections (jwt / keystore / cors) come
     * only from environment variables or not at all.
     */
    @Test
    void bindsAbsentSecurityJwtKeystoreAndCorsSectionsToNonNullDefaults() {
        Map<String, Object> source = Map.of("marketplace.security.session.max-sessions", "2");

        MarketplaceProperties properties = new Binder(ConfigurationPropertySources
                .from(new MapPropertySource("test", source)))
                .bind("marketplace", Bindable.of(MarketplaceProperties.class))
                .get();

        // cors: entirely absent section — primed, with the default origin.
        assertThat(properties.cors()).as("cors section (absent keys)").isNotNull();
        assertThat(properties.cors().allowedOrigins())
                .as("cors default origin applies when the section is absent")
                .containsExactly("https://marketplace.com");

        // security.jwt + keystore: entirely absent — primed all the way down.
        assertThat(properties.security()).as("security section").isNotNull();
        assertThat(properties.security().jwt()).as("jwt section (absent keys)").isNotNull();
        assertThat(properties.security().jwt().keystore())
                .as("keystore section (absent keys — SecurityConfig dereferences it unconditionally)")
                .isNotNull();
        assertThat(properties.security().jwt().keystore().path()).isEmpty();
        assertThat(properties.security().jwt().keystore().b64()).isEmpty();
        assertThat(properties.security().jwt().audience()).isEqualTo("marketplace-api");

        // session: the one bound key, with the documented default shape.
        assertThat(properties.security().session()).as("session section").isNotNull();
        assertThat(properties.security().session().maxSessions()).isEqualTo(2);
        // A.4 (compliance plan wave A): the overflow half of the concurrency
        // pair is primed with the documented default — false expires the
        // least-recent session (the newest login succeeds); it must bind
        // non-null-and-false when its key is absent, exactly like the bound
        // maximum above, never an NPE in the filter chains' DSL lambda.
        assertThat(properties.security().session().maxSessionsPreventsLogin())
                .as("max-sessions-prevents-login documented default: false (least-recent session expires)")
                .isFalse();

        // I7 (account-pseudonymization-plan §5-أ): the pseudonymization
        // section is primed non-null when absent — UserService and
        // SubjectPseudonymizer dereference security().pseudonymization()
        // unconditionally on every call path (the CodeRabbit #241 lesson
        // applied to the new section), and the key binds blank = the
        // capability is OFF (503 SU-001), never null.
        assertThat(properties.security().pseudonymization())
                .as("pseudonymization section (absent keys — SubjectPseudonymizer dereferences it)")
                .isNotNull();
        assertThat(properties.security().pseudonymization().subjectHmacKey()).isEmpty();
        // I7 §9 (rotation row — the keyring): the retained-keys list is
        // primed empty when the section is absent (the empty @DefaultValue
        // on a collection component — the same non-null guarantee, list
        // shape), so deriveAll's ring iterates nothing, never null.
        assertThat(properties.security().pseudonymization().subjectHmacPreviousKeys())
                .as("previous-keys ring (absent keys — deriveAll dereferences it)")
                .isNotNull()
                .isEmpty();

        // N1 (admin-seed hardening): the admin-seed section is primed
        // non-null when absent — AdminUserInitializer dereferences
        // security().adminSeed().password() unconditionally at boot (the
        // CodeRabbit #241 lesson applied to the new section), and a blank
        // password means the documented development fallback outside prod /
        // fail-fast inside prod — never a NullPointerException.
        assertThat(properties.security().adminSeed())
                .as("admin-seed section (absent keys — AdminUserInitializer dereferences it)")
                .isNotNull();
        assertThat(properties.security().adminSeed().password())
                .as("admin-seed password (absent keys — blank, not null)")
                .isEmpty();
    }

    /**
     * N1: the admin-seed password binds from its property key — the shape the
     * {@code ADMIN_SEED_PASSWORD} environment variable delivers through the
     * application.yml placeholder.
     */
    @Test
    void bindsAdminSeedPasswordFromItsPropertyKey() {
        Map<String, Object> source = Map.of(
                "marketplace.security.admin-seed.password", "env-delivered-secret");

        MarketplaceProperties properties = new Binder(ConfigurationPropertySources
                .from(new MapPropertySource("test", source)))
                .bind("marketplace", Bindable.of(MarketplaceProperties.class))
                .get();

        assertThat(properties.security().adminSeed().password())
                .isEqualTo("env-delivered-secret");
    }

    /**
     * A.4: the concurrent-session overflow policy binds from its property
     * key — the shape {@code SESSION_MAX_SESSIONS_PREVENTS_LOGIN} delivers
     * through the application.yml placeholder, riding the same binding
     * channel as {@code SESSION_MAX_SESSIONS} above it.
     */
    @Test
    void bindsConcurrentSessionOverflowPolicyFromItsPropertyKey() {
        Map<String, Object> source = Map.of(
                "marketplace.security.session.max-sessions", "3",
                "marketplace.security.session.max-sessions-prevents-login", "true");

        MarketplaceProperties properties = new Binder(ConfigurationPropertySources
                .from(new MapPropertySource("test", source)))
                .bind("marketplace", Bindable.of(MarketplaceProperties.class))
                .get();

        assertThat(properties.security().session().maxSessions()).isEqualTo(3);
        assertThat(properties.security().session().maxSessionsPreventsLogin())
                .as("the overflow policy binds from its key: true = the new login is rejected")
                .isTrue();
    }

    /**
     * B.1: the api-versioning section is primed non-null with an EMPTY map
     * when its keys are absent — ApiVersioningConfig dereferences
     * apiVersioning().deprecations() unconditionally on every boot (the
     * CodeRabbit #241 lesson applied to the new section), and an empty map
     * is the honest live state: no version is deprecated today, so no
     * deprecation handler is registered at all.
     */
    @Test
    void bindsAbsentApiVersioningSectionToAnEmptyDeprecationsMap() {
        Map<String, Object> source = Map.of("marketplace.security.session.max-sessions", "2");

        MarketplaceProperties properties = new Binder(ConfigurationPropertySources
                .from(new MapPropertySource("test", source)))
                .bind("marketplace", Bindable.of(MarketplaceProperties.class))
                .get();

        assertThat(properties.apiVersioning())
                .as("api-versioning section (absent keys — ApiVersioningConfig dereferences it)")
                .isNotNull();
        assertThat(properties.apiVersioning().deprecations())
                .as("deprecations map (absent keys — empty, never null)")
                .isNotNull()
                .isEmpty();
    }

    /**
     * B.1: a deprecated version binds from the bracket-notation map key —
     * the shape that carries a version string containing a dot
     * ({@code marketplace.api-versioning.deprecations[1.0].*}), exactly the
     * version callers send in {@code X-API-Version}. Dates bind from
     * ISO-8601 strings; the link binds as the migration document URI.
     */
    @Test
    void bindsDeprecatedVersionFromBracketNotationKey() {
        Map<String, Object> source = Map.of(
                "marketplace.security.session.max-sessions", "2",
                "marketplace.api-versioning.deprecations[1.0].deprecation-date", "2026-11-01T00:00:00Z",
                "marketplace.api-versioning.deprecations[1.0].sunset-date", "2027-06-01T00:00:00Z",
                "marketplace.api-versioning.deprecations[1.0].link", "https://developer.marketplace.com/api/migration");

        MarketplaceProperties properties = new Binder(ConfigurationPropertySources
                .from(new MapPropertySource("test", source)))
                .bind("marketplace", Bindable.of(MarketplaceProperties.class))
                .get();

        assertThat(properties.apiVersioning().deprecations())
                .containsKey("1.0");
        MarketplaceProperties.ApiVersioning.Deprecation spec =
                properties.apiVersioning().deprecations().get("1.0");
        assertThat(spec.deprecationDate())
                .isEqualTo(java.time.ZonedDateTime.parse("2026-11-01T00:00:00Z"));
        assertThat(spec.sunsetDate())
                .isEqualTo(java.time.ZonedDateTime.parse("2027-06-01T00:00:00Z"));
        assertThat(spec.link())
                .isEqualTo("https://developer.marketplace.com/api/migration");
    }

    /**
     * I7 §9 (rotation row — resolved option 1, the keyring): the retained
     * previous keys bind from a comma-separated property value into the
     * {@code List<String>} component — the shape the environment channel
     * delivers ({@code PSEUDONYMIZATION_HMAC_PREVIOUS_KEYS} resolving through
     * the application.yml placeholder).
     */
    @Test
    void bindsCommaSeparatedPreviousKeysIntoTheRingList() {
        Map<String, Object> source = Map.of(
                "marketplace.security.pseudonymization.subject-hmac-previous-keys",
                "retired-key-1,retired-key-2,retired-key-3");

        MarketplaceProperties properties = new Binder(ConfigurationPropertySources
                .from(new MapPropertySource("test", source)))
                .bind("marketplace", Bindable.of(MarketplaceProperties.class))
                .get();

        assertThat(properties.security().pseudonymization().subjectHmacPreviousKeys())
                .containsExactly("retired-key-1", "retired-key-2", "retired-key-3");
        // The active key stays absent = blank — the write capability OFF
        // while the retained ring still binds (the honest mixed state).
        assertThat(properties.security().pseudonymization().subjectHmacKey()).isEmpty();
    }
}
