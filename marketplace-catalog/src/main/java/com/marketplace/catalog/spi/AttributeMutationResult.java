package com.marketplace.catalog.spi;

import java.util.List;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor attribute mutation
 * roots, the agent-2 packet): the owning use case's answer to one
 * attribute mutation — the bounded projection on success, the domain
 * error entries on a validation refusal (the registered "Mutation
 * payload errors" ADAPT divergence: domain outcomes ride the payload's
 * own errors field, never the GraphQL error list).
 *
 * @param attribute the bounded attribute projection (null on failure)
 * @param errors    the domain error entries (empty on success)
 */
public record AttributeMutationResult(AttributeView attribute, List<AttributeMutationError> errors) {

    /** The success verdict — no domain errors. */
    public boolean success() {
        return errors.isEmpty();
    }

    /** The success factory. */
    public static AttributeMutationResult success(AttributeView attribute) {
        return new AttributeMutationResult(attribute, List.of());
    }

    /** The failure factory — the domain error entries, no projection. */
    public static AttributeMutationResult failure(List<AttributeMutationError> errors) {
        return new AttributeMutationResult(null, List.copyOf(errors));
    }
}
