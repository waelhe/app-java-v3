package com.marketplace.catalog;

import java.util.UUID;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

/**
 * B-16 (compliance plan C.8 — the M2 store wave): the seller's single
 * official answer — the second half of «أسئلة/أجوبة المنتج». One LIVE
 * answer per question: the {@code uq_product_answers_question} partial
 * unique (the V70/V157/V160 shape — one LIVE row per key) holds the
 * one-answer invariant at the schema level, and the service's
 * {@code existsByQuestionId} gate answers the honest 409 before any
 * write (defense in depth, the house order).
 *
 * <p><b>Why a separate row and not answer columns on the question:</b>
 * the two halves of the pair are written by two different actors — the
 * member asks, the provider answers. Separate entities give each actor
 * its own honest {@code @Audited} revision trail ({@code created_by} is
 * the asker on one table and the answering seller on the other — C.8's
 * own {@code auditing.html} reference is exactly about this honesty),
 * and the answer's revision (the seller may refine the wording) is an
 * UPDATE on its own row without touching the question's history.
 *
 * <p><b>Shape:</b> {@code questionId} is a plain UUID column with a
 * DB-level FK to {@code product_questions} and no JPA relation (the
 * V116 in-module precedent). {@code providerId} is denormalized from
 * the product's owner at answer time — the audit-honest record of WHO
 * answered, which the answering gate guarantees equals the product's
 * owning provider.
 */
@Entity
@Table(name = "product_answers")
@Audited
public class ProductAnswer extends BaseEntity {

    @Id
    private UUID id;

    /** The answered question — a plain UUID column, never a JPA relation (the V116 shape). */
    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    /** The answering seller — denormalized from the product's owner (the audit-honest record). */
    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    /** The answer's text — the house content-body bound (2000). */
    @Column(name = "body", nullable = false, length = 2000)
    private String body;

    protected ProductAnswer() {
    }

    private ProductAnswer(UUID id, UUID questionId, UUID providerId, String body) {
        this.id = id;
        this.questionId = questionId;
        this.providerId = providerId;
        this.body = body;
    }

    /**
     * Publishes the official answer — the owning seller's M2 write path.
     * The question-existence, ownership and one-answer gates are the
     * service's own (404 / 403 / 409 — the community delete pattern's
     * exact ladder).
     */
    public static ProductAnswer publish(UUID questionId, UUID providerId, String body) {
        return new ProductAnswer(UUID.randomUUID(), questionId, providerId, body);
    }

    public UUID getId() {
        return id;
    }

    public UUID getQuestionId() {
        return questionId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public String getBody() {
        return body;
    }
}
