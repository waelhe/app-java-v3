package com.marketplace.reviews;

/**
 * I8 (internal free plan §6, roadmap §7): the review direction — the two-way
 * review. {@link #CONSUMER_TO_PROVIDER} is the historical direction every
 * pre-I8 review carries (V45's default); {@link #PROVIDER_TO_CONSUMER} is
 * the reverse direction: the booking's provider rates its consumer.
 */
public enum ReviewDirection {

    /** The consumer who booked reviews the provider (the original contract). */
    CONSUMER_TO_PROVIDER,

    /** The provider reviews the booking's consumer (I8 — the reverse). */
    PROVIDER_TO_CONSUMER
}
