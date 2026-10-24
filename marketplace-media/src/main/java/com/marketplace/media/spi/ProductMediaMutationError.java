package com.marketplace.media.spi;

/**
 * N-slice: one domain-level validation outcome of the product-media
 * family's write use cases — the registered "Mutation payload errors"
 * ADAPT shape (field/message/code riding the payload's own errors
 * member, never a thrown exception).
 *
 * @param field   the argument member the outcome addresses (null when
 *                the outcome is not field-shaped)
 * @param code    the classified code (the bounded vocabulary)
 * @param message the human-readable explanation
 */
public record ProductMediaMutationError(String field, ProductMediaMutationErrorCode code,
                                        String message) {
}
