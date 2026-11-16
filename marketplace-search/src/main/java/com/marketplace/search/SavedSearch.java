package com.marketplace.search;

import com.marketplace.shared.api.SearchCriteria;
import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * L35 (realestate systems plan §5 — saved searches and alerts): a user's
 * stored {@link SearchCriteria} — the exact record the search surface
 * binds, so a saved search's matching semantics are the search's own
 * semantics by construction ("المطابقة بالمواصفات نفسها").
 *
 * <p><b>Storage (the V48/amenities precedent):</b> the criteria travel as
 * JSONB through the official Hibernate JSON mapping
 * ({@code @JdbcTypeCode(SqlTypes.JSON)}) — a structured, queryable column,
 * not a text blob. The record's canonical constructor is the type gate on
 * BOTH ends: an invalid criteria cannot be saved (the service validates
 * at the surface, 400 before any write) and cannot be loaded (the gates
 * are invariants — a future tightening must keep stored rows loadable,
 * the documented evolution constraint on this column).
 *
 * <p><b>Cross-module references (the V32/media_assets, V52/listing_leads
 * discipline):</b> {@code userId} is a plain UUID column in the users.id
 * space — no JPA relationship and no FK across module borders; the /me
 * controller stitch resolves it.
 *
 * <p><b>Purge (the b-3 contract, criterion 7):</b> the criteria's only
 * authored free text is the {@code query} component; the purge adapter
 * removes that JSON key (absence is the record's own criterion-less form
 * for the text dimension — the JSON analog of "nullable text becomes
 * NULL", not the {@code [purged]} tombstone which would keep the saved
 * search literally matching the word "purged").
 */
@Entity
@Table(name = "saved_searches")
@Audited
public class SavedSearch extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The stored criteria — JSONB, round-tripped by Hibernate's JSON mapping. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "criteria", nullable = false, columnDefinition = "jsonb")
    private SearchCriteria criteria;

    /** FALSE = stored for later re-use only; the matcher's scan skips it (criterion 6). */
    @Column(name = "alert_enabled", nullable = false)
    private boolean alertEnabled;

    /** Bookkeeping for the alerting UX — set by the matcher on each new match. */
    @Column(name = "last_matched_at")
    private Instant lastMatchedAt;

    protected SavedSearch() {
        // JPA
    }

    /**
     * The factory gate: a saved search without criteria cannot exist — the
     * criteria record itself is the validated form (its canonical
     * constructor is the type gate; the service already ran it before
     * reaching here).
     */
    static SavedSearch create(UUID id, UUID userId, SearchCriteria criteria, boolean alertEnabled) {
        if (userId == null || criteria == null) {
            throw new IllegalArgumentException("userId and criteria are required");
        }
        SavedSearch saved = new SavedSearch();
        saved.id = id;
        saved.userId = userId;
        saved.criteria = criteria;
        saved.alertEnabled = alertEnabled;
        return saved;
    }

    /** The matcher's bookkeeping write — each new match stamps the search. */
    void markMatched(Instant at) {
        this.lastMatchedAt = at;
    }

    UUID getUserId() { return userId; }

    SearchCriteria getCriteria() { return criteria; }

    boolean isAlertEnabled() { return alertEnabled; }

    Instant getLastMatchedAt() { return lastMatchedAt; }

    @Override
    public UUID getId() { return id; }
}
