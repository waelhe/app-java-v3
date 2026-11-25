package com.marketplace.catalog.spi;

import java.util.List;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor attribute-value
 * mutation roots, the agent-2 packet): the owning use case's answer to
 * one attribute-value mutation — the value projection on success (with
 * the owning attribute's own projection for Saleor's dual-field
 * payload), the domain error entries on a validation refusal (the
 * registered "Mutation payload errors" ADAPT divergence: domain
 * outcomes ride the payload's own errors field, never the GraphQL
 * error list).
 *
 * @param attribute the owning attribute's bounded projection (null on
 *                  failure)
 * @param value     the bounded attribute-value projection (null on
 *                  failure)
 * @param errors    the domain error entries (empty on success)
 */
public record AttributeValueMutationResult(AttributeView attribute, AttributeValueView value,
                                           List<AttributeMutationError> errors) {

    /** The success verdict — no domain errors. */
    public boolean success() {
        return errors.isEmpty();
    }

    /** The success factory — both projections (the payload's own fields). */
    public static AttributeValueMutationResult success(AttributeView attribute, AttributeValueView value) {
        return new AttributeValueMutationResult(attribute, value, List.of());
    }

    /** The failure factory — the domain error entries, no projections. */
    public static AttributeValueMutationResult failure(List<AttributeMutationError> errors) {
        return new AttributeValueMutationResult(null, null, List.copyOf(errors));
    }
}
