package com.marketplace.shared.api;

import java.util.List;
import java.util.UUID;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): the
 * community module's ACTIVE-member enumeration for one level-3
 * neighborhood — the reverse direction of {@link CommunityMembershipPort}
 * (that port answers "which neighborhood is this user in"; this one
 * answers "which users are in this neighborhood").
 *
 * <p>The consumer is the notifications module's official-urgent-alert
 * routing leg (the {@code UrgentAlertPublishedEvent} fan-out): the
 * alert's committed facts are the source, the scope and the validity
 * window — the RECIPIENT SET is the scope's own membership, resolved
 * through the data owner (marketplace-community, the V60/V91 membership
 * machinery) exactly as §8.1's port discipline requires: interface in
 * shared-api, the data owner implements it, the consumer injects it —
 * no module boundary crossed in code (the
 * {@code CommunityMembershipPort} house pattern verbatim). The §8.1
 * rule "لا يشتق المستلمون في مستمع الإشعارات إذا كان الحدث يحمل الحقيقة
 * الملتزمة" is untouched: the event carries no per-recipient fact to
 * re-derive — the scope's membership is the fan-out's own input, read
 * from its owner, never inferred from unrelated payloads.
 *
 * <p>The send-volume gate the NotificationType URGENT_ALERT entry
 * documented ("an alert to every member of a large neighborhood is a
 * bulk-send policy question") is answered by the alert's own measured
 * shape: the scope is level-3-enforced at the source (V178 +
 * GeoLookupPort), the fan-out rides the §8.1 routing engine (geo
 * eligibility, per-channel idempotency ledger, channel policies), and
 * the only unconditional leg is the in-app inbox row (the official-alert
 * policy's single documented override).
 */
public interface NeighborhoodMembersPort {

    /**
     * @param locationId the level-3 neighborhood (geo_locations space)
     * @return the ACTIVE members' user ids (users.id space) of that
     *         neighborhood — the same ACTIVE row visibility the
     *         membership 403 gate and {@link CommunityMembershipPort}
     *         answer: Hibernate's {@code @SoftDelete} filter hides left
     *         memberships, an empty neighborhood answers an honest empty
     *         list, never a widened scope
     */
    List<UUID> getActiveMemberIds(UUID locationId);
}
