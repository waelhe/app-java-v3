package com.marketplace.identity;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * JT-20 (the discovery waves — AC-20-05): one member's follow of one
 * followable source (a USER or a GROUP) — the generalized row behind the
 * FOLLOWED_SOURCES rail's relationship leg, the {@code ProviderFollow}
 * model verbatim (the same BaseEntity, the same Hibernate soft delete,
 * the same two-layer uniqueness).
 *
 * <p><b>Module ownership:</b> the follow is MEMBER data (the follower is
 * a users row and the management surface is the member's own /me page),
 * so the table lives in marketplace-identity — the reasoning that put
 * {@code provider_follows} here (V93) verbatim.
 *
 * <p><b>Cross-module references (the V32/V52/V54/V93 discipline):</b>
 * both columns are plain UUIDs — no JPA relationship and no FK across
 * module borders. {@code userId} is the follower (the users.id space);
 * {@code followableId} sits in the TYPE'S OWN id space (USER → users.id,
 * GROUP → neighborhood_groups.id) — the write path checks existence
 * (the module's own UserRepository for USER, the shared
 * {@code GroupLookupPort} seam for GROUP) so the row is born pointing at
 * a live source.
 *
 * <p><b>Uniqueness (the house two-layer form):</b> the service's
 * idempotent read first (a live replay answers the standing row — the
 * generalized surface's own contract), V177's partial unique index
 * {@code uq_follows_one_live ON (user_id, followable_type, followable_id)
 * WHERE is_deleted = FALSE} as the concurrent-insert backstop. The soft
 * delete (the house withdraw form, b-5) frees the triple — an unfollow
 * then a re-follow is legal by construction, and the re-follow inserts a
 * FRESH row (the withdrawn one stays, the member's own record).
 *
 * <p>Getters stay package-private — the {@code ProviderFollow} shape
 * verbatim: the only readers (the composed view and the
 * {@code FollowedSourcesPort} union body) live in this same package; the
 * module's spi seam
 * ({@code com.marketplace.identity.spi.FollowedSourcesAdapter}) delegates
 * to the service and never touches the entity (the HTTP/spi boundaries
 * speak the view records only — the entity never crosses them).
 */
@Entity
@Table(name = "follows")
@Audited
public class Follow extends BaseEntity {

    @Id
    private UUID id;

    /** The follower — a users.id (the /me seam resolves it; no JPA relation). */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The closed source vocabulary (V177's CHECK is the SQL-side twin). */
    @Enumerated(EnumType.STRING)
    @Column(name = "followable_type", nullable = false, length = 20)
    private FollowableType followableType;

    /** The followed source — the type's own id space (see the class javadoc). */
    @Column(name = "followable_id", nullable = false)
    private UUID followableId;

    protected Follow() {
        // JPA
    }

    /**
     * The factory gate: a follow without either party (or without a type)
     * cannot exist. The self-follow rule (a member never follows himself)
     * and the source-existence rule are the SERVICE's gates — they need
     * the resolved pair, not the raw arguments (the
     * {@code ProviderFollow} factory's own reasoning verbatim).
     */
    static Follow create(UUID id, UUID userId, FollowableType followableType, UUID followableId) {
        if (userId == null || followableType == null || followableId == null) {
            throw new IllegalArgumentException("userId, followableType and followableId are required");
        }
        Follow follow = new Follow();
        follow.id = id;
        follow.userId = userId;
        follow.followableType = followableType;
        follow.followableId = followableId;
        return follow;
    }

    UUID getUserId() { return userId; }

    FollowableType getFollowableType() { return followableType; }

    UUID getFollowableId() { return followableId; }

    @Override
    public UUID getId() { return id; }
}
