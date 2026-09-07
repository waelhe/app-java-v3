package com.marketplace.disputes;

/**
 * L24 (feature-expansion roadmap §5): the resolve decision's outcome.
 *
 * <p>{@code REFUND_CONSUMER} — the consumer wins: the resolve invokes the
 * existing refund path for the booking's payment (full refund — the
 * decision's shape) and records the movement on the dispute;
 * {@code RELEASE_PROVIDER} — the provider wins: the money stays;
 * {@code NO_ACTION} — resolved without a financial movement.
 */
public enum DisputeResolution {
    REFUND_CONSUMER,
    RELEASE_PROVIDER,
    NO_ACTION
}
