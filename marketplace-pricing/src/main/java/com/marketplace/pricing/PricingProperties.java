package com.marketplace.pricing;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Row 11 (CodeRabbit agent findings 2026-10-25 — CWE-834): the
 * day-sliced pricing model's business bound — the maximum number of
 * priced nights a stay window may span. The calendar model expands a
 * window into one {@code effectiveNightCents} computation per UTC date,
 * so an unbounded window (a provider publishes a matching enormous
 * availability slot; the finding's measured shape carries a
 * +200000-year endpoint) turns one compact booking request into tens of
 * millions of iterations, each scanning seasonal rows — computation the
 * request never paid for. The bound is enforced BEFORE any repository
 * read and before the per-date loop, on BOTH surfaces that share the
 * seam (the windowed quote and the booking port), exactly as the
 * finding's recommendation states ("reject intervals exceeding a
 * business-defined maximum before pricing").
 *
 * <p><b>Fail-fast binding (the SearchProperties rule):</b> the bound is
 * {@code @Min(1)} — a non-positive environment value would reject every
 * window at the guard (the "bound satisfied by zero" trap), so the
 * misconfigured deploy fails STARTUP at binding time instead. The
 * official validation recipe applies: «Spring Boot attempts to validate
 * @ConfigurationProperties classes whenever they are annotated with
 * Spring's @Validated annotation» (Spring Boot reference — Type-safe
 * Configuration Properties — Validation). The constraint rides the
 * module's existing {@code spring-boot-starter-validation}.
 *
 * <p><b>Calibration:</b> the conservative default follows the house
 * calibration gates (tune with real traffic through
 * {@code MARKETPLACE_PRICING_MAX_STAY_NIGHTS}). It must stay compatible
 * with the availability module's slot bound
 * ({@code marketplace.availability.max-slot-days}): a booking must
 * exactly match a published slot, so a slot longer than the stay bound
 * could never be booked end-to-end — the two defaults ship equal (365)
 * and deployments must move them together.
 */
@Validated
@ConfigurationProperties(prefix = "marketplace.pricing")
public record PricingProperties(
        /**
         * The maximum priced nights per stay window ({@code nights =
         * checkIn-date .. checkOut-date}, the day-sliced model's own
         * unit; a same-date window prices its single check-in date).
         * Windows beyond the bound answer 400 on both pricing surfaces
         * before any repository read.
         */
        @Min(1) @DefaultValue("365") long maxStayNights
) {

    /**
     * The documented default — kept in sync with the {@code @DefaultValue}
     * literal by the test that pins the binding
     * ({@code PricingPropertiesValidationTest}). The pre-L26 convenience
     * constructors ride it so the flat test seams price with the
     * production bound.
     */
    public static final long DEFAULT_MAX_STAY_NIGHTS = 365L;
}
