package com.marketplace.shared.email;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CodeRabbit round 2 on this branch (Major — Stability & Availability, the
 * disputed round-1 fix): the provider value's legality must be validated
 * INDEPENDENTLY of the two channel auto-configurations' conditions.
 *
 * <p><b>The measured defect the round-1 fix could not close:</b> the
 * validation lived in {@link MailChannelProperties}'s compact constructor,
 * but that record is registered only through
 * {@code @EnableConfigurationProperties} INSIDE the two channel
 * auto-configurations — {@link EmailServiceAutoConfiguration} (provider
 * {@code smtp}, matchIfMissing) and {@code ResendEmailSenderAutoConfiguration}
 * (provider {@code resend}). A typo'd value ({@code resned}) matches
 * NEITHER condition, so the record never binds, its constructor never
 * runs, and the context boots with NO mail channel at all: the
 * {@code EmailNotificationService} seam answers {@code Optional.empty()}
 * and password-reset events complete as delivered with no email and no
 * retry — the silent channel-less boot the review measured.</p>
 *
 * <p><b>This record is the unconditional half:</b> it binds the provider
 * value alone and is registered by
 * {@link MailProviderValidationAutoConfiguration} with NO channel
 * condition, so every context that carries the auto-configuration
 * surface validates the value at STARTUP — a misconfigured provider
 * reads as configuration (the D6 fail-fast law), never as a silently
 * missing channel. The {@code from} identity validation stays in
 * {@link MailChannelProperties}, deliberately conditional on an ACTIVE
 * channel: contexts that legitimately run no mail (a test slice with no
 * {@code JavaMailSender}, a deployment with no {@code spring.mail.host})
 * must keep booting without a sender.</p>
 */
@ConfigurationProperties(prefix = "marketplace.mail")
public record MailProviderProperties(String provider) {

    /**
     * The two legal values are exactly the two transports this platform
     * ships: {@code smtp} (the default — Boot's own convention when the
     * property is absent) and {@code resend} (the HTTPS transport). The
     * operational message is {@link MailChannelProperties}'s own verbatim
     * — the two records answer the same misconfiguration.
     */
    public MailProviderProperties {
        if (provider == null || provider.isBlank()) {
            // absent = the SMTP default (matchIfMissing on the SMTP
            // auto-configuration) — normalized here so the value check
            // below reads one shape
            provider = "smtp";
        }
        if (!"smtp".equals(provider) && !"resend".equals(provider)) {
            throw new IllegalArgumentException(
                    "marketplace.mail.provider '" + provider + "' is not one of the two transports "
                            + "this platform ships (smtp, resend) — a typo'd provider would leave "
                            + "every mail-carrying event silently undelivered (no channel bean "
                            + "matches, the notifications seam answers empty). Fix the value "
                            + "(MARKETPLACE_MAIL_PROVIDER) or drop it to run the SMTP default.");
        }
    }
}
