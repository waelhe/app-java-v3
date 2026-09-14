package com.marketplace.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * L34 (realestate systems plan §5 — lead capture): the messaging module's
 * type-safe configuration (constructor binding, primed with an empty
 * {@link DefaultValue} section per the house binding rule — AGENTS.md).
 *
 * <p>The leads section carries the G-R6-calibratable values only: the
 * conservative daily cap per sender fingerprint. The plan's gate text:
 * "معايرة أولية بالوثيقة، تعديلها بمعايرة الترافيك" — the default is the
 * initial documented value; real traffic calibrates it through the
 * environment (relaxed binding:
 * {@code MARKETPLACE_MESSAGING_LEADS_DAILY_CAP_PER_SENDER}).
 */
@ConfigurationProperties(prefix = "marketplace.messaging")
public record MessagingProperties(
        @DefaultValue Leads leads
) {

    public record Leads(
            /**
             * G-R6 initial conservative cap: how many leads one sender
             * fingerprint (SHA-256 of the client IP) may submit per rolling
             * 24h window. The global {@code leadCreate} rate limiter bounds
             * the concurrent-check race to this order of magnitude; the
             * count query rides the V52 partial index.
             */
            @DefaultValue("5") long dailyCapPerSender
    ) {}
}
