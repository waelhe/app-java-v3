package com.marketplace.search;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * Stage 10 (plan D-13, ADR-0006): one labeled eval case — a query paired
 * with a listing's graded relevance (0 irrelevant → 3 perfect), the
 * local asset the learned-ordering gate is measured against. The
 * {@code tags} column carries the case's own labeling axes (relevance/
 * recency/scope/diversity/safety — the plan's labeling vocabulary) as
 * free text, never a mega-enum. Audited per the AGENTS.md rule (the V24
 * mirror in V176).
 */
@Entity
@Table(name = "ordering_eval_cases")
@Audited
public class OrderingEvalCase extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "query", nullable = false, length = 200)
    private String query;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    /** The graded relevance: 0 irrelevant, 1 marginal, 2 useful, 3 perfect. */
    @Column(name = "relevance", nullable = false)
    private int relevance;

    @Column(name = "tags", nullable = false, length = 200)
    private String tags = "";

    protected OrderingEvalCase() {
        // JPA
    }

    private OrderingEvalCase(UUID id, String query, UUID listingId, int relevance, String tags) {
        if (relevance < 0 || relevance > 3) {
            throw new IllegalArgumentException("Relevance is the graded 0-3 scale");
        }
        this.id = id;
        this.query = query;
        this.listingId = listingId;
        this.relevance = relevance;
        this.tags = tags;
    }

    public static OrderingEvalCase label(String query, UUID listingId, int relevance, String tags) {
        return new OrderingEvalCase(UUID.randomUUID(), query, listingId, relevance, tags);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getQuery() {
        return query;
    }

    public UUID getListingId() {
        return listingId;
    }

    public int getRelevance() {
        return relevance;
    }

    public String getTags() {
        return tags;
    }
}
