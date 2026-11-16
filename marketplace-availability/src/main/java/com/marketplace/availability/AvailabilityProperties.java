package com.marketplace.availability;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Row 11 (CodeRabbit agent findings 2026-10-25 — CWE-834): the
 * availability module's compatible publication bound — the maximum
 * number of UTC days an open slot may span. The finding's attack chain
 * starts at {@code AvailabilityService.createSlot} persisting an
 * owned open slot WITHOUT limiting its duration; a matching enormous
 * booking window then rides the pricing module's per-date loop. The
 * pricing seam carries its own bound
 * ({@code marketplace.pricing.max-stay-nights} — the recommendation's
 * "before pricing" half); THIS bound is the recommendation's second
 * half verbatim: "apply compatible limits when publishing availability
 * slots". A booking must EXACTLY match a published slot
 * ({@code hasExactAvailableSlot}), so the two bounds ship equal (365)
 * and a deployment must move them together — a slot longer than the
 * stay bound could never be booked end-to-end.
 *
 * <p><b>Fail-fast binding (the SearchProperties rule):</b> the bound is
 * {@code @Min(1)} — a non-positive value would reject every slot at
 * publication, so the misconfigured deploy fails STARTUP at binding
 * time. The official validation recipe applies: «Spring Boot attempts
 * to validate @ConfigurationProperties classes whenever they are
 * annotated with Spring's @Validated annotation» (Spring Boot reference
 * — Type-safe Configuration Properties — Validation). The constraint
 * rides the module's existing {@code spring-boot-starter-validation}.
 *
 * <p><b>Scope (measured against the finding):</b> open slots only. A
 * weekly rule generates single-date slots by construction (bounded),
 * and a time-off window is a block-out consulted by indexed existence
 * probes — no per-date computation ever rides it, so it carries no
 * amplification to bound.
 */
@Validated
@ConfigurationProperties(prefix = "marketplace.availability")
public record AvailabilityProperties(
        /**
         * The maximum UTC-day span of one published open slot
         * ({@code startsAt-date .. endsAt-date}; a same-date window spans
         * zero days and stays valid — the intra-day service shape). Longer
         * windows answer 400 before any write.
         */
        @Min(1) @DefaultValue("365") long maxSlotDays
) {

    /**
     * The documented default — kept in sync with the {@code @DefaultValue}
     * literal by the test that pins the binding
     * ({@code AvailabilityPropertiesValidationTest}), and by design with
     * {@code PricingProperties.DEFAULT_MAX_STAY_NIGHTS} (the compatibility
     * contract above).
     */
    public static final long DEFAULT_MAX_SLOT_DAYS = 365L;
}
