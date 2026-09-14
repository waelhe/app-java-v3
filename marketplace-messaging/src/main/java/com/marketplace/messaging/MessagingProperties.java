package com.marketplace.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * L34 (realestate systems plan §5 — lead capture): the messaging module's
 * type-safe configuration (constructor binding, primed with an empty
 * {@link DefaultValue} section per the house binding rule — AGENTS.md).
 *
 * <p>The leads section carries the G-R6-calibratable values (the
 * conservative daily cap per sender fingerprint — "معايرة أولية
 * بالوثيقة، تعديلها بمعايرة الترافيك"; real traffic calibrates it through
 * the environment: {@code
 * MARKETPLACE_MESSAGING_LEADS_DAILY_CAP_PER_SENDER}) and the IP
 * fingerprint HMAC key (the CodeRabbit round-1 adoption: a keyed
 * HmacSHA256 instead of bare SHA-256 — the IPv4 space is enumerable, so
 * an unkeyed digest is re-identifiable by anyone who reads the table;
 * the keyed digest is pseudonymization, and the key never leaves the
 * server. The key must be set in the prod profile — MessagingConfig
 * fails startup otherwise, the JwkSourceProdHardening pattern).
 */
@ConfigurationProperties(prefix = "marketplace.messaging")
public record MessagingProperties(
        @DefaultValue Leads leads
) {

    public record Leads(
            /**
             * G-R6 initial conservative cap: how many leads one sender
             * fingerprint (the keyed HMAC of the client IP) may submit per
             * rolling 24h window. The count query rides the V52 partial
             * index; the advisory transaction lock serializes concurrent
             * submissions from one fingerprint.
             */
            @DefaultValue("5") long dailyCapPerSender,
            /**
             * The HmacSHA256 key for the sender IP fingerprint (CodeRabbit
             * round-1 adoption, CWE-759): hex-encoded or raw bytes both
             * work — the value is used verbatim as the key material. Empty
             * in the test profile (an empty HMAC key is still a valid,
             * deterministic key); REQUIRED in prod (MessagingConfig fails
             * startup when blank under the prod profile).
             */
            @DefaultValue("") String ipHashKey
    ) {}
}
