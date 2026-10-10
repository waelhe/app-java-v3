package com.marketplace.notifications.routing;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): WHY a
 * channel was held back — the decision's own explanation (§11.2 DoD:
 * results are explainable). Each value names the gate that suppressed
 * the delivery, so tests assert the actual answer against the effective
 * preference/state, not a bare boolean.
 */
public enum RoutingSuppression {
    /** The actor IS the recipient — no notification about your own act. */
    SELF_ACTION,
    /** The policy's validity instant has passed (the صلاحية gate). */
    EXPIRED,
    /** The recipient's effective geo scope does not cover the event's scope. */
    GEO_SCOPE,
    /** The recipient's type-level override row disables the channel. */
    TYPE_PREFERENCE,
    /** The recipient's topic-level override row disables the channel. */
    TOPIC_PREFERENCE,
    /** The channel has no configured provider (push — the pending D-10). */
    CHANNEL_NOT_CONFIGURED
}
