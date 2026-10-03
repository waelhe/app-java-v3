package com.marketplace.identity;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * W4 (yelp-level plan §5 — the reviewer identity &amp; engagement wave, G21):
 * one member's follow of one provider — the row behind "تابع هذا المزود"
 * and the {@code FOLLOWED_PROVIDER_NEW_LISTING} alert when that provider's
 * listing is announced.
 *
 * <p><b>Module ownership (the plan's §5 W4 allocation — identity + reviews
 * + notifications):</b> the follow is MEMBER data (the follower is a users
 * row and the "my follows" surface is the member's own /me page), so the
 * table lives in marketplace-identity — the same ownership reasoning that
 * put the membership rows in marketplace-community for the neighborhood
 * bridge (the nearest measured twin of this fan-out).
 *
 * <p><b>Cross-module references (the V32/media_assets, V52/listing_leads,
 * V54/saved_searches discipline):</b> both columns are plain UUIDs in the
 * users.id space — no JPA relationship and no FK across module borders.
 * {@code providerUserId} is the followed provider's USER id (the A1
 * measured fact: {@code provider_listings.provider_id references
 * users(id)}), which is exactly the id {@code ListingActivatedEvent}
 * carries — the activation bridge joins on it directly, no profile
 * resolution on the hot path. The write path resolves the client-facing
 * PROFILE id to this user id through {@code ProviderLookupPort} (the W1
 * {@code createOrganic} seam's own resolution), so the public page's key
 * never leaks into storage and the bridge never resolves on read.
 *
 * <p><b>Uniqueness (the house two-layer form):</b> the service's explicit
 * 409 first, V93's partial unique index
 * {@code uq_provider_follows_once ON (user_id, provider_user_id) WHERE
 * is_deleted = FALSE} as the concurrent-insert backstop. The soft delete
 * (the house withdraw form, b-5) frees the pair — an unfollow then a
 * re-follow is legal by construction, exactly like the helpful-vote pair.
 */
@Entity
@Table(name = "provider_follows")
@Audited
public class ProviderFollow extends BaseEntity {

    @Id
    private UUID id;

    /** The follower — a users.id (the /me seam resolves it; no JPA relation). */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * The followed provider's USER id (A1 — the space
     * {@code ListingActivatedEvent.providerId} carries, so the bridge's
     * scan is a direct indexed read).
     */
    @Column(name = "provider_user_id", nullable = false)
    private UUID providerUserId;

    protected ProviderFollow() {
        // JPA
    }

    /**
     * The factory gate: a follow without either party cannot exist. The
     * self-follow rule (a member never follows his own provider profile)
     * is the SERVICE's gate — the {@code createOrganic} "not the provider
     * itself" precedent — because it needs the resolved pair, not the raw
     * arguments.
     */
    static ProviderFollow create(UUID id, UUID userId, UUID providerUserId) {
        if (userId == null || providerUserId == null) {
            throw new IllegalArgumentException("userId and providerUserId are required");
        }
        ProviderFollow follow = new ProviderFollow();
        follow.id = id;
        follow.userId = userId;
        follow.providerUserId = providerUserId;
        return follow;
    }

    UUID getUserId() { return userId; }

    UUID getProviderUserId() { return providerUserId; }

    @Override
    public UUID getId() { return id; }
}
