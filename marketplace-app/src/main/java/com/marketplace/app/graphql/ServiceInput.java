package com.marketplace.app.graphql;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * B3 (docs/codex-review-fixes-plan.md): free services are allowed — the price
 * rule is "zero or positive cents", the same domain rule every other surface
 * already enforces: the DB check constraint (V2 provider_listings.price_cents
 * >= 0), the REST surface (CreateListingRequest priceCents @NotNull only) and
 * BookingInfo (rejects < 0 only). The former @Positive rejected zero, making
 * GraphQL the only surface that forbade a free listing — a cross-surface
 * contract contradiction. @Min(0) is the money-cents precedent already used
 * by CurrencyExchangeController amountCents.
 *
 * <p>Binding contract (spring-graphql {@code GraphQlArgumentBinder}): input
 * keys are matched to record component names <em>by name</em> — the schema
 * input field must therefore be {@code priceCents: Int!}. The previous
 * {@code price: Float!} schema field could never bind to this component, so
 * the {@code createService} mutation rejected every price (paid and free)
 * since the surface's origin — fixed together with the first end-to-end
 * mutation test (see ServiceGraphQlMutationIntegrationTest).
 */
public record ServiceInput(
    @NotBlank String name,
    String description,
    @NotBlank String category,
    @NotNull @Min(0) Long priceCents
) {}
