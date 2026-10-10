package com.marketplace.notifications.routing;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): the
 * أولوية dimension of the routing contract — how the delivery ranks
 * against the user's attention, independent of every other dimension.
 *
 * <p>{@code URGENT} is reserved for the official-alert family (the
 * delegated urgent alert, CMP-46/JT-10): its routing contract is the
 * validity-and-geo policy of the official-alert rules (§8.1: "توجيه
 * التنبيهات الرسمية بسياسة الصلاحية والجغرافيا لا بتفضيل التفاعل") —
 * the severity the DELEGATED SOURCE declared, never a popularity signal
 * (the CMP-46 rule: the level is rendered text, never a ranking weight).
 */
public enum NotificationPriority {
    NORMAL,
    URGENT
}
