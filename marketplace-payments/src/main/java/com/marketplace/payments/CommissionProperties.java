package com.marketplace.payments;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

/**
 * ADR-0013 (D2 — the commission freeze): the announced commission rate's
 * type-safe binding — the ONE configuration the intent-creation path reads
 * to freeze the rate onto each new payment intent (Stripe's
 * application-fee model: the fee is fixed at charge creation, never
 * recomputed at settlement).
 *
 * <p><b>The single-rate law (the ADR's «no second rate» rule):</b> the
 * prefix {@code app.commission} is the SAME key the ledger's listener
 * reads through {@code @Value("${app.commission.rate:0.10}")} — one
 * announced rate, two readers, zero drift. This record is the payments
 * module's type-safe view of it (constructor binding primed with a
 * {@code @DefaultValue} per the house AGENTS.md binding rule — a nested
 * record section with absent keys binds to null; a scalar with a default
 * never can).
 *
 * <p><b>Fail-closed binding:</b> the compact constructor rejects null and
 * any value outside [0, 1] — a misconfigured rate fails startup loudly
 * (the D6 prod-keys posture), never silently books a wrong fee. The
 * measured default is 0.10 — the value {@code application.yml} has
 * carried since the ledger's first commission debit.
 *
 * @param rate the announced platform commission rate, in [0, 1]
 *            (0.10 = 10%)
 */
@ConfigurationProperties(prefix = "app.commission")
public record CommissionProperties(
        @DefaultValue("0.10") BigDecimal rate
) {

    public CommissionProperties {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "app.commission.rate must be a decimal fraction in [0, 1] — got: " + rate);
        }
    }
}
