package com.marketplace.notifications.routing;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): one user's
 * OPT-IN geographic subscription — the V198
 * {@code notification_geo_subscriptions} row, the optional widening of
 * the routing engine's effective geo scope beyond the user's own
 * membership.
 *
 * <p><b>The documented assumptions (§8.1 requires them documented — here
 * and in the migration header they live):</b>
 * <ul>
 *   <li>OPT-IN ONLY — a row exists because the user created it through
 *       the self-service endpoint; nothing is inferred and no default
 *       row is born.</li>
 *   <li>THE DEFAULT SCOPE IS THE MEMBERSHIP — with no subscription rows,
 *       the engine's effective geo scope is exactly the user's ACTIVE
 *       community-membership neighborhood (the
 *       {@code CommunityMembershipPort} answer), never a widened one
 *       (AC-02-02: geographic expansion is an explicit user act).</li>
 *   <li>WITHDRAWAL IS EXPLICIT AND REVERSIBLE — the withdrawal soft-deletes
 *       the row (the {@code BaseEntity} {@code @SoftDelete} flag); the
 *       V198 partial unique index keys live rows only, so the withdrawn
 *       pair can be re-subscribed later.</li>
 * </ul>
 *
 * <p>{@code location_id} is a plain UUID with no FK across module
 * boundaries (the V32/V48/V54/V178 discipline); the write path validates
 * existence through {@code GeoLookupPort} and gates the level to 3 (the
 * neighborhood scope the whole surface speaks). {@code user_id} is the
 * V40 module-decoupling convention verbatim (no FK to users). Every
 * change leaves an Envers revision; {@code created_at} — the subscription
 * moment — is the audit listener's own stamp.
 */
@Entity
@Table(name = "notification_geo_subscriptions")
@Audited
public class NotificationGeoSubscription extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The geo tree node — level 3 (neighborhood) only, gated by the service. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    protected NotificationGeoSubscription() {
    }

    private NotificationGeoSubscription(UUID id, UUID userId, UUID locationId) {
        this.id = id;
        this.userId = userId;
        this.locationId = locationId;
    }

    /**
     * Creates one opt-in subscription row — the subscriber's own act.
     *
     * @param userId     the subscriber (users.id space)
     * @param locationId the level-3 neighborhood (geo_locations space)
     */
    public static NotificationGeoSubscription subscribe(UUID userId, UUID locationId) {
        return new NotificationGeoSubscription(UUID.randomUUID(), userId, locationId);
    }

    /**
     * The subscription moment (the audit listener's {@code created_at}
     * stamp) — the projection the caller's own list returns.
     */
    public Instant subscribedAt() {
        return getCreatedAt();
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getLocationId() {
        return locationId;
    }
}
