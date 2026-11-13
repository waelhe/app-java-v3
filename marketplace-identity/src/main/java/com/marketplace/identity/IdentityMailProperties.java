package com.marketplace.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * A-04 (official-compliance plan §6, wave A — A.1/A.2): the identity
 * module's own mail-link and token-lifetime contract.
 *
 * <p><b>Why identity's own properties record and not a MarketplaceProperties
 * extension:</b> the house {@code MarketplaceProperties} lives in
 * platform-infra's {@code shared.config} — a NamedInterface identity's
 * module descriptor does not expose (identity carries shared-api,
 * shared-security, shared-jpa only, the A-02 gate's own measurement), and
 * widening that exposure for three numbers would blur the boundary the
 * architecture gate just froze. The official Boot shape is either way the
 * same one AGENTS.md pins (type-safe {@code @ConfigurationProperties},
 * constructor binding); this record follows the flat-component form with a
 * {@code @DefaultValue} on every component, so an absent key can never bind
 * a null into a dereferenced path (the house rule MarketplaceProperties'
 * own javadoc states for nested sections — applied per component here).</p>
 *
 * <p><b>Duration binding:</b> Boot's simple duration format ("30m", "24h") —
 * the official {@code @DefaultValue} conversion for {@link Duration}
 * properties (Boot reference, features › external-config › duration
 * conversion).</p>
 */
@ConfigurationProperties(prefix = "marketplace.identity")
public record IdentityMailProperties(
        /**
         * The CLIENT application's public origin — the base every account
         * mail deep link is built on ({@code /reset-password?token=...},
         * {@code /verify-email?token=...}). The default is the development
         * client origin (the measured {@code marketplace.cors.allowed-
         * origins} dev value); production binds its own public origin. The
         * routes are the API's declared deep-link contract for the client
         * to implement.
         */
        @DefaultValue("http://localhost:3000") String mailBaseUrl,

        /**
         * A.1 — the password-reset token's lifetime. 30 minutes is the
         * dormant template's own printed contract ("This link will expire
         * in {@code 30} minutes" — the template renders the derived
         * {@code expirationMinutes} from this TTL), and sits inside the
         * OWASP "appropriate period" for a credential the user is actively
         * waiting on.
         */
        @DefaultValue("30m") Duration passwordResetTtl,

        /**
         * A.2 — the email-verification token's lifetime. A day is the
         * generous end of the OWASP "appropriate period" for a link a
         * user may click from a phone hours later; expired links are
         * re-mintable through the anonymous resend surface.
         */
        @DefaultValue("24h") Duration emailVerificationTtl,

        /**
         * The per-account re-issue floor both token purposes share (the
         * OWASP flood wall: "Implement protections against excessive
         * automated submissions such as rate-limiting on a per-account
         * basis... flooding the user's intake system"). A re-request for
         * the same account+purpose inside this window re-issues nothing
         * and mails nothing. Configurable so the journey ITs can exercise
         * the re-request/resend cadence without sleeping past a minute.
         */
        @DefaultValue("60s") Duration minIssueInterval
) {
    // The W2 binding-time fail-fast wall (review round 2, 2026-11-11):
    // @ConfigurationProperties binding passes unresolved placeholders
    // through literally, so the deployable profiles' no-default
    // ${MARKETPLACE_IDENTITY_MAIL_BASE_URL} reaches THIS constructor
    // unchanged when the variable is absent — reject it here, at binding,
    // with the operational message (the MailChannelProperties precedent).
    public IdentityMailProperties {
        if (mailBaseUrl == null || mailBaseUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "marketplace.identity.mail-base-url is required: the client origin every "
                            + "account mail deep link is built on — set "
                            + "MARKETPLACE_IDENTITY_MAIL_BASE_URL='https://<public-client-origin>' "
                            + "in the deployment environment (the .railway/railway.ts declaration "
                            + "carries the variable's seat).");
        }
        if (mailBaseUrl.contains("${")) {
            throw new IllegalArgumentException(
                    "marketplace.identity.mail-base-url '" + mailBaseUrl + "' is an UNRESOLVED "
                            + "configuration placeholder: Spring Boot 4.1.2's @ConfigurationProperties "
                            + "binding passes unresolved placeholders through literally instead of "
                            + "failing, so this record rejects it — set "
                            + "MARKETPLACE_IDENTITY_MAIL_BASE_URL='https://<public-client-origin>' "
                            + "in the deployment environment before the next deploy.");
        }
        URI origin;
        try {
            origin = URI.create(mailBaseUrl);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "marketplace.identity.mail-base-url '" + mailBaseUrl + "' is not a valid URI "
                            + "(java.net.URI): " + ex.getMessage(), ex);
        }
        // Review round 3 (2026-11-11, Minor): an origin carrying a query or a
        // fragment would mint malformed deep links (the record concatenates
        // "/reset-password?token=..." onto this base — a second '?' or a '#'
        // breaks the link) — reject both, same operational message shape.
        // Review round 4 (2026-11-13, Minor ×2): URI schemes are
        // case-insensitive (java.net.URI — "HTTPS://app.example.com" is a
        // valid https origin), so the scheme comparison is
        // case-insensitive; and an origin carrying a non-root path would
        // silently rewrite the declared deep-link routes
        // ("https://app.example.com/account" + "/reset-password" mints
        // "/account/reset-password", not the contract's route) — every path
        // except the root is rejected, and a lone trailing "/" is normalized
        // away so concatenation can never mint a double slash.
        // Review round 5 (2026-11-13, Minor): java.net.URI accepts ports
        // beyond the TCP range verbatim (measured: URI.create("https://host:70000")
        // parses with getPort()=70000 — the parser validates digits, never
        // the 0-65535 range), so the wall itself rejects out-of-range ports;
        // an undefined port (-1) and valid ports stay accepted.
        String rawPath = origin.getRawPath();
        if (origin.getScheme() == null || origin.getHost() == null
                || !(("http".equalsIgnoreCase(origin.getScheme())
                        || "https".equalsIgnoreCase(origin.getScheme())))
                || origin.getPort() > 65535
                || origin.getRawQuery() != null
                || origin.getRawFragment() != null
                || (rawPath != null && !rawPath.isBlank() && !"/".equals(rawPath))) {
            throw new IllegalArgumentException(
                    "marketplace.identity.mail-base-url '" + mailBaseUrl + "' must be an absolute "
                            + "http(s) client origin (scheme + host, no path, no query or fragment, "
                            + "port within 0-65535) — e.g. "
                            + "MARKETPLACE_IDENTITY_MAIL_BASE_URL='https://app.example.com'.");
        }
        if ("/".equals(rawPath)) {
            mailBaseUrl = mailBaseUrl.substring(0, mailBaseUrl.length() - 1);
        }
    }
}
