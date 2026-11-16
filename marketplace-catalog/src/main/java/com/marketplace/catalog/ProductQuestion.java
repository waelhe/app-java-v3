package com.marketplace.catalog;

import java.util.UUID;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

/**
 * B-16 (compliance plan C.8 — the M2 store wave, «جذر المتجر ٢/٢»): the
 * buyer's question on a store product — the first half of the Q&amp;A pair
 * the plan words as «أسئلة/أجوبة المنتج».
 *
 * <p><b>Shape (the A-17 root's own discipline, verbatim):</b>
 * {@code productId} is a plain UUID column with a DB-level FK to
 * {@code products} but NO JPA relation — the store's garden keeps its
 * cross-entity references as columns (the V116 {@code storeCategoryCode}
 * shape; the V32/V48/V52/V54/V60/V61/V64 cross-module discipline applies
 * a fortiori inside the module, where the M1 root itself set the
 * precedent). {@code askerId} is the asking member — a plain UUID column,
 * never a relation. The entity is {@code @Audited} per the AGENTS.md rule
 * (C.8's own reference list carries {@code auditing.html}: the question's
 * full revision trail — who asked, who re-asked, when — IS the audit
 * surface the wave's contract demands).
 *
 * <p><b>Lifecycle:</b> questions are born answered-less; the seller's
 * single official answer ({@link ProductAnswer}) completes the pair. The
 * public read surfaces gate through the product first — a soft-deleted
 * product's questions are absent exactly as the product itself is (the
 * community feed's own posture: a hidden post's comments are absent
 * exactly as the post itself is).
 */
@Entity
@Table(name = "product_questions")
@Audited
public class ProductQuestion extends BaseEntity {

    @Id
    private UUID id;

    /** The questioned product — a plain UUID column, never a JPA relation (the V116 shape). */
    @Column(name = "product_id", nullable = false)
    private UUID productId;

    /** The asking member — a plain UUID column, never a relation (the V32 discipline). */
    @Column(name = "asker_id", nullable = false)
    private UUID askerId;

    /** The question's text — the house content-body bound (2000, the post/comment bound). */
    @Column(name = "body", nullable = false, length = 2000)
    private String body;

    protected ProductQuestion() {
    }

    private ProductQuestion(UUID id, UUID productId, UUID askerId, String body) {
        this.id = id;
        this.productId = productId;
        this.askerId = askerId;
        this.body = body;
    }

    /**
     * Asks one question — the member's M2 write path. The product's
     * existence-and-liveness gate is the service's own (the honest 404
     * on an unknown product, never a silent accept — the L31 discipline).
     */
    public static ProductQuestion ask(UUID productId, UUID askerId, String body) {
        return new ProductQuestion(UUID.randomUUID(), productId, askerId, body);
    }

    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getAskerId() {
        return askerId;
    }

    public String getBody() {
        return body;
    }
}
