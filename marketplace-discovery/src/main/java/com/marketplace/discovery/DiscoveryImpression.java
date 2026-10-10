package com.marketplace.discovery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Wave D-1 (plan #536 §1.4 / JT-20 — AC-20-03): one row of the
 * {@code discovery_impressions} ledger (V176) — the display bookkeeping
 * that dedups a card's impression across contexts ("two separate contexts
 * sharing one card govern their impression dedup through the announced
 * display contract", the {@code DiscoveryCardView} contract's own words).
 *
 * <p><b>The ledger discipline (the V93 {@code provider_follow_alerts} /
 * V54 {@code saved_search_matches} precedent verbatim):</b> an OPERATIONAL
 * DISPLAY LEDGER, not a domain aggregate — rows are BORN COMPLETE and
 * NEVER MUTATED, so the row IS its own audit trail: no Envers mirror, no
 * {@code @Version} optimistic lock, no soft delete, no {@code BaseEntity}
 * column set (the entity stands on exactly the columns V176 created —
 * the mutable-state machinery would be dead weight on a write-once row).
 * The skip semantics live in the write path
 * ({@link DiscoveryImpressionRepository#insertOnce}): a repeated
 * (user, row, source, day) pair is a returned 0, never a
 * transaction-aborting 23505.</p>
 *
 * <p><b>The columns:</b> {@code userId} sits in the {@code users.id} space
 * and {@code sourceId} in the source record's own id space — plain UUIDs,
 * no FK across module borders (the V32/V52/V54/V93 discipline).
 * {@code rowType} and {@code sourceType} carry the closed vocabularies
 * ({@code DiscoveryRowType} and the card's {@code NEIGHBORHOOD_POST}/
 * {@code NEIGHBORHOOD_EVENT}/{@code JOB}/{@code URGENT_ALERT}/
 * {@code PROVIDER_LISTING}) — enforced at the controller's type gate, the
 * Java enum/constant the single source of truth (the D-N7 division).
 * {@code impressionDay} is the display day the impression dedups by.
 * {@code createdAt} is owned by the database default ({@code now()}) —
 * the bridge never writes it, the column is read-only mapped.</p>
 */
@Entity
@Table(name = "discovery_impressions")
public class DiscoveryImpression {

    @Id
    private UUID id;

    /** The viewer's id (users.id space — plain UUID, no cross-module FK). */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The rail the card was rendered on (the {@code DiscoveryRowType} name). */
    @Column(name = "row_type", nullable = false, length = 40)
    private String rowType;

    /** The source record's type — the closed card vocabulary (see the class javadoc). */
    @Column(name = "source_type", nullable = false, length = 60)
    private String sourceType;

    /** The source record's id in its owner's id space (the V32 discipline). */
    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    /** The display day the (user, row, source, day) skip key dedups by. */
    @Column(name = "impression_day", nullable = false)
    private LocalDate impressionDay;

    /** Database-owned birth timestamp (V176's {@code DEFAULT now()}) — read-only mapped. */
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected DiscoveryImpression() {
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getRowType() { return rowType; }
    public String getSourceType() { return sourceType; }
    public UUID getSourceId() { return sourceId; }
    public LocalDate getImpressionDay() { return impressionDay; }
    public Instant getCreatedAt() { return createdAt; }
}
