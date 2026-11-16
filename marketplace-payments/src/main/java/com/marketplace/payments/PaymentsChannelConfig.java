package com.marketplace.payments;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link PaymentsProperties}, the ADR-0013 commission-rate
 * binding ({@link CommissionProperties} — the freeze the intent-creation
 * path reads), and — only when both Stripe credentials are bound — the
 * single {@link StripePspChannel} bean. Spring owns the bean lifecycle;
 * the channel itself is stateless (per-call {@code RequestOptions}
 * carries the key material, no global mutable {@code Stripe.apiKey}
 * assignment).
 */
@Configuration
@EnableConfigurationProperties({PaymentsProperties.class, CommissionProperties.class})
class PaymentsChannelConfig {

    @Bean
    @Conditional(StripeChannelConfiguredCondition.class)
    PspChannel stripePspChannel(PaymentsProperties properties) {
        return new StripePspChannel(
                properties.psp().stripe().apiKey(),
                properties.psp().stripe().webhookSecret()
        );
    }
}
