package com.marketplace.notifications.routing;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): the
 * قسم/مجال dimension of the routing contract — the platform section the
 * event belongs to. One of the contract's SEPARATE dimensions (§8.1:
 * "هوية الحدث وأبعاد التوجيه وتنفيذ القناة مفاهيم منفصلة" — never a
 * giant enum per section×topic×geo×channel combination): the section
 * names the platform domain, the {@link NotificationTopic} names the
 * user-facing subject family, the geo scope and the channel policies
 * stay their own dimensions.
 *
 * <p>The vocabulary is small and closed on purpose — a new section is a
 * one-point enum addition (the L34 house pattern), never a rebuild.
 */
public enum RoutingSection {
    BOOKING,
    ORDER,
    PAYMENT,
    MESSAGING,
    COMMUNITY,
    NEIGHBORHOOD,
    SEARCH,
    TRUST,
    DISPUTE,
    OFFICIAL
}
