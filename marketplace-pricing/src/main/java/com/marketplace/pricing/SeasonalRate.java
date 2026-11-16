package com.marketplace.pricing;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.LocalDate;
import java.util.UUID;

/**
 * L26 (feature-expansion roadmap §5): one seasonal range of a listing's
 * price calendar — an absolute price over {@code [fromDate, toDate)} with
 * an EXCLUSIVE end (the same interval convention as the stay window). A
 * covering range's absolute price REPLACES the listing's base price for its
 * days; the weekend multiplier never stacks on top (the precedence rule).
 *
 * <p>Overlap of two ranges for the same listing is rejected with 409 at the
 * service seam; two ranges sharing a boundary (first.toDate == second.fromDate)
 * are legal — open intervals. The entity itself only enforces the positive
 * span; the overlap check needs the sibling rows and lives in
 * {@code ListingPriceCalendarService}.
 */
@Entity
@Table(name = "seasonal_rates")
@Audited
public class SeasonalRate extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    /** Exclusive end — the day it names is NOT part of the range. */
    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    protected SeasonalRate() {
    }

    private SeasonalRate(UUID id, UUID listingId, LocalDate fromDate, LocalDate toDate, long priceCents) {
        this.id = id;
        this.listingId = listingId;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.priceCents = priceCents;
    }

    /**
     * @throws IllegalArgumentException when the span is not a positive
     *                                  {@code [from, to)} half-open interval — the callers map it to
     *                                  the 400 taxonomy
     */
    public static SeasonalRate create(UUID listingId, LocalDate fromDate, LocalDate toDate, long priceCents) {
        if (fromDate == null || toDate == null || !fromDate.isBefore(toDate)) {
            throw new IllegalArgumentException("Seasonal rate must be a positive [from, to) span");
        }
        if (priceCents < 0) {
            throw new IllegalArgumentException("Seasonal price must not be negative");
        }
        return new SeasonalRate(UUID.randomUUID(), listingId, fromDate, toDate, priceCents);
    }

    void change(LocalDate fromDate, LocalDate toDate, long priceCents) {
        if (fromDate == null || toDate == null || !fromDate.isBefore(toDate)) {
            throw new IllegalArgumentException("Seasonal rate must be a positive [from, to) span");
        }
        if (priceCents < 0) {
            throw new IllegalArgumentException("Seasonal price must not be negative");
        }
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.priceCents = priceCents;
    }

    /** Whether the day is inside {@code [fromDate, toDate)} — the exclusive end never covers. */
    boolean covers(LocalDate day) {
        return !day.isBefore(fromDate) && day.isBefore(toDate);
    }

    /**
     * Whether the other range overlaps this one — strict open-interval
     * intersection: {@code this.from < other.to && other.from < this.to}.
     * A shared boundary (this.to == other.from) is NOT an overlap.
     */
    boolean overlaps(SeasonalRate other) {
        return this.fromDate.isBefore(other.toDate) && other.fromDate.isBefore(this.toDate);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getListingId() {
        return listingId;
    }

    public LocalDate getFromDate() {
        return fromDate;
    }

    public LocalDate getToDate() {
        return toDate;
    }

    public long getPriceCents() {
        return priceCents;
    }

    /** Cross-seam 409 for the service layer (kept out of the shared-api import surface). */
    static ConflictException overlapConflict(UUID listingId) {
        return new ConflictException(
                "Seasonal rates for listing " + listingId + " overlap — adjacent ranges sharing a boundary are allowed");
    }
}
