package com.marketplace.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

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
}
