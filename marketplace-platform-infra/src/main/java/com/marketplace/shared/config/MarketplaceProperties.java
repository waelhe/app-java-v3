package com.marketplace.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Type-safe configuration properties for the marketplace application.
 *
 * <p>Replaces scattered {@code @Value} annotations with a single
 * configuration properties class, following Spring Boot best practices.
 *
 * <p>Nested sections that must be safe to dereference even when no property key exists
 * are primed with an empty {@link DefaultValue}, so constructor binding always produces a
 * non-null instance: "If you want to always bind a non-null instance of {@code Security},
 * even when properties are missing, you can use an empty {@code @DefaultValue} annotation"
 * (constructor-binding section of the Spring Boot reference).
 *
 * @see <a href="https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.constructor-binding">
 *      Spring Boot — Type-safe Configuration Properties — Constructor binding</a>
 */
@ConfigurationProperties(prefix = "marketplace")
public record MarketplaceProperties(
    // Every nested section that production code dereferences is primed with
    // an empty @DefaultValue (the house rule stated in this file's javadoc):
    // constructor binding binds an absent section to null, and
    // SecurityConfig dereferences security().jwt().keystore() /
    // security().session().maxSessions() unconditionally (CodeRabbit #241
    // flagged exactly this gap — only OAuth2 was primed before).
    @DefaultValue Cors cors,
    @DefaultValue Security security
) {
    public record Cors(
        @DefaultValue("https://marketplace.com") List<String> allowedOrigins
    ) {}

    public record Security(
        @DefaultValue Jwt jwt,
        @DefaultValue Session session,
        @DefaultValue OAuth2 oauth2,
        @DefaultValue Pseudonymization pseudonymization
    ) {
        /**
         * I7 (account-pseudonymization-plan §5-أ / gate b-2(b)): the HMAC
         * secret for account-subject pseudonymization — the "additional
         * information" that GDPR Art. 4(5) requires to be "kept separately"
         * from the pseudonymized data. The channel is environment-only per
         * secrets-policy §1 ({@code PSEUDONYMIZATION_HMAC_KEY}): the value
         * never defaults, never lands in Git, and is never logged.
         *
         * <p>Optional until bound, exactly like the PSP/MAIL gates: blank =
         * the administrative pseudonymize surface answers 503 SU-001 (the
         * capability is OFF, not broken) and the re-registration probe in
         * {@code syncFromOidc} stays inert — no tombstone can exist while the
         * key never existed. Rotation consequence (documented, plan §9): a
         * different key derives different replacement subjects, so existing
         * tombstones stop matching the probe.
         *
         * <p><b>The keyring (plan §9 rotation row — resolved option 1):</b>
         * {@link #subjectHmacPreviousKeys()} retains the retired keys so the
         * re-registration probe verifies derivations under <em>every</em> key
         * the system ever wrote tombstones with — the official
         * overlap-window rotation shape (secrets-policy §3 "dual key /
         * overlap window"; the same retention pattern Spring Security's JWKS
         * rotation applies: one active signing key, retained keys still
         * verifying). The write path ({@code derive}) uses the active key
         * only; the probe path ({@code deriveAll}) covers the whole ring.
         * A key must never be dropped from the ring while tombstones derived
         * under it can still exist in {@code users.subject}.</p>
         *
         * <p><b>Binding shape (measured against Boot 4.1.1):</b> this record
         * deliberately declares the implicit canonical constructor ONLY —
         * implicit constructor binding deduces from a
         * single-declared-constructor shape, and an extra (even delegating)
         * constructor leaves the section unresolvable while the
         * component-level {@code @DefaultValue} primes propagate to the
         * implicit constructor's parameters (an explicit canonical
         * constructor would NOT receive them). The empty
         * {@code @DefaultValue} on the list component binds an empty
         * collection, comma-separated values split into elements, and an
         * empty-string value (the yml placeholder default when the env var
         * is unset) binds empty — all three pinned by
         * {@code MarketplacePropertiesBindingTest}.
         */
        public record Pseudonymization(
            @DefaultValue("") String subjectHmacKey,
            @DefaultValue List<String> subjectHmacPreviousKeys
        ) {}

        public record Jwt(
            @DefaultValue KeyStore keystore,
            @DefaultValue("marketplace-api") String audience
        ) {
            /**
             * Two source channels for the persistent JKS signing keystore, resolved
             * with this precedence by {@code SecurityConfig#jwkSource} (its javadoc
             * carries the full contract):
             * <ul>
             *   <li>{@code b64} — base64 of the JKS bytes, bound from
             *       {@code JWT_KEYSTORE_B64}: the application-level channel for
             *       platforms that deliver secrets as write-only environment
             *       variables (Railway). Decoded in memory by
             *       {@link java.security.KeyStore#load(java.io.InputStream, char[])} —
             *       no file is ever materialized, keeping the container entrypoint
             *       the pure official recipe.
             *   </li>
             *   <li>{@code path} — {@code file:}/{@code classpath:} location per the
             *       runbook {@code keys/README.md} (development hosts, mounted
             *       files).</li>
             * </ul>
             * Credentials ({@code password}/{@code alias}/{@code keyPassword}) are
             * shared by both channels and required whenever either source is set.
             */
            public record KeyStore(
                @DefaultValue("") String path,
                @DefaultValue("") String b64,
                @DefaultValue("") String password,
                @DefaultValue("") String alias,
                @DefaultValue("") String keyPassword
            ) {}
        }
        public record Session(
            @DefaultValue("2") int maxSessions
        ) {}
        public record OAuth2(
            @DefaultValue Client client,
            @DefaultValue PublicClient publicClient
        ) {
            /**
             * Confidential (BFF) client registration — gate B, pattern (1) of the client
             * hosting strategy plan: server-side client (e.g. Next.js with API routes)
             * authenticating with a secret over {@code client_secret_basic}.
             *
             * <p>{@code redirectUris} is a comma-separated list of the BFF application's
             * callback URLs. When blank, the initializer falls back to the fixed
             * development definition (spec §4.1 constant {@code 127.0.0.1:8080}); in the
             * {@code prod} profile the value is bound from the mandatory
             * {@code OAUTH_CLIENT_REDIRECT_URIS} environment variable and a blank value
             * fails fast — closing the documented gate-B debt (production redirect was
             * previously pinned to the development constant).
             */
            public record Client(
                @DefaultValue("") String clientId,
                @DefaultValue("") String secret,
                @DefaultValue("") String redirectUris
            ) {}

            /**
             * Public (secret-less) client registration — gate B, pattern (3) of the client
             * hosting strategy plan: native/mobile client (e.g. Flutter) authenticating with
             * PKCE only. Classified by secret location, not by technology (plan §3).
             *
             * <p>{@code redirectUris} is a comma-separated list (custom scheme or https app
             * link per RFC 8252). No client secret exists by definition; the initializer
             * derives the full registration from these two values plus fixed official
             * settings, so both are mandatory (fail-fast) in the {@code prod} profile:
             * {@code OAUTH_PUBLIC_CLIENT_ID}/{@code OAUTH_PUBLIC_CLIENT_REDIRECT_URIS}.
             */
            public record PublicClient(
                @DefaultValue("") String clientId,
                @DefaultValue("") String redirectUris
            ) {}
        }
    }
}
