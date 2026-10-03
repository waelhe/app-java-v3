package com.marketplace.community;

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
 * L50 (the Nextdoor-2026 completeness wave — gap #5, the market board):
 * one member's offered possession in exactly one neighborhood — the
 * market board's unit (the plan's own §7 gate «سوق الحي والحراج»
 * opened by the owner's Nextdoor-2026 directive).
 *
 * <p><b>The domain shape (all gap-analysis decisions, all measured):</b>
 * <ul>
 *   <li>{@code authorId} and {@code locationId} are plain UUID columns
 *       with NO JPA relation across module boundaries (the
 *       V32/V48/V60/V61/V83 discipline) — the author resolves through
 *       the identity seams, the location through {@code GeoLookupPort}
 *       (D-N2: level-3 of the ONE administrative hierarchy,
 *       level-checked at publish through the same L41 gate).</li>
 *   <li>{@code category} is the board's one filter axis; the V90 CHECK
 *       pins the SQL membership guard (D-N7). The vocabulary is the
 *       product's own five chips ({@link MarketCategory}).</li>
 *   <li>{@code title} is bounded at 200 characters — the house
 *       {@code provider_listings.title} limit (V2's own documented
 *       bound). The market card carries no separate body: the title IS
 *       the item's whole authored text (the design's own card shape,
 *       measured — its long descriptive titles ride exactly this
 *       field).</li>
 *   <li>{@code condition} is the card's own chip — the product's
 *       two-value vocabulary ({@link MarketCondition}).</li>
 *   <li>{@code priceCents} + {@code priceCurrency} are ONE rule with
 *       {@code category} (the events' registration/capacity shape): a
 *       FREE item carries NO price at all («مجاني ⇔ بلا سعر»), the
 *       four sale categories carry a strictly positive integer-cents
 *       amount in ISO 4217 (the V2 money shape). The service's
 *       friendly 400 comes first; the V90 CHECK is the backstop.</li>
 *   <li>{@code locationLabel} is the product's own display label —
 *       the pickup spot inside the neighborhood as the seller wrote it
 *       (the design's form field, bounded like titles).</li>
 *   <li>{@code status} is the card's two-state vocabulary
 *       ({@link MarketItemStatus}): the column rides from day one so
 *       the read contract is complete (the V25/V32 lesson); a
 *       mark-sold write is a documented product decision, never a
 *       silent one.</li>
 * </ul>
 *
 * <p>Every BaseEntity column present from day one (V25/V32 lesson);
 * the Envers mirror rides V90 (the V24 convention). The author's own
 * delete is the house soft delete — the row stays (b-5's retention),
 * the reads stop returning it. The purge seam (b-3) empties the
 * authored text when the account closes.
 */
@Entity
@Table(name = "neighborhood_market_items")
@Audited
public class NeighborhoodMarketItem extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    /** The geo tree node — level 3 (neighborhood) only, gated by the service. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private MarketCategory category;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_condition", nullable = false, length = 20)
    private MarketCondition condition;

    /**
     * The price in integer cents — null for a FREE gift (the ONE
     * pricing rule: «مجاني ⇔ بلا سعر», the V90 CHECK's law).
     */
    @Column(name = "price_cents")
    private Integer priceCents;

    /** The ISO 4217 code — present exactly when {@code priceCents} is. */
    @Column(name = "price_currency", length = 3)
    private String priceCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MarketItemStatus status;

    @Column(name = "location_label", nullable = false, length = 200)
    private String locationLabel;

    protected NeighborhoodMarketItem() {
    }

    private NeighborhoodMarketItem(UUID id, UUID authorId, UUID locationId) {
        this.id = id;
        this.authorId = authorId;
        this.locationId = locationId;
    }

    /**
     * The publish factory: a fresh ACTIVE item. The membership, level
     * and pricing gates live in the service — before any write; this
     * factory is the honest insert shape. No clock parameter:
     * {@code createdAt} is the auditing listener's own stamp
     * (BaseEntity's @CreatedDate). A fresh item is never SOLD — the
     * two-state vocabulary's own floor.
     */
    public static NeighborhoodMarketItem marketItem(UUID authorId, UUID locationId,
                                                    MarketCategory category, String title,
                                                    MarketCondition condition,
                                                    Integer priceCents, String priceCurrency,
                                                    String locationLabel) {
        NeighborhoodMarketItem item = new NeighborhoodMarketItem(UUID.randomUUID(), authorId, locationId);
        item.category = category;
        item.title = title;
        item.condition = condition;
        item.priceCents = priceCents;
        item.priceCurrency = priceCurrency;
        item.status = MarketItemStatus.ACTIVE;
        item.locationLabel = locationLabel;
        return item;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getAuthorId() { return authorId; }
    public UUID getLocationId() { return locationId; }
    public MarketCategory getCategory() { return category; }
    public String getTitle() { return title; }
    public MarketCondition getCondition() { return condition; }
    public Integer getPriceCents() { return priceCents; }
    public String getPriceCurrency() { return priceCurrency; }
    public MarketItemStatus getStatus() { return status; }
    public String getLocationLabel() { return locationLabel; }
}
