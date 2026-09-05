package com.marketplace.payments;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Type-safe configuration for the payments module's outbound channel
 * (constructor binding, primed with an empty {@link DefaultValue} section
 * per the house binding rule — AGENTS.md).
 *
 * <p>When either Stripe credential is blank the channel is inert by design:
 * no channel beans are created (see {@link StripeChannelConfiguredCondition})
 * and {@code processIntent} keeps the existing internal-intent behavior.
 * The same graceful-provider-gate pattern as MAIL / MEDIA_S3 (SYSTEM.md §15
 * debt item 3): the capability is OFF, not broken — no fail-fast, no
 * health contribution. Test keys (sk_test_... / whsec_...) select the
 * official sandbox mode; the provider choice is a deployment decision, not
 * a code decision.
 */
@ConfigurationProperties(prefix = "marketplace.payments")
public record PaymentsProperties(
        @DefaultValue Psp psp
) {

    public record Psp(
            @DefaultValue Stripe stripe
    ) {}

    public record Stripe(
            @DefaultValue("") String apiKey,
            @DefaultValue("") String webhookSecret
    ) {}
}
