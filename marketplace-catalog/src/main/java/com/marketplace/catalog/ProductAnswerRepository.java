package com.marketplace.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * B-16 (compliance plan C.8 — the M2 store wave): the answer side of
 * «أسئلة/أجوبة المنتج». Both reads are DERIVED query methods (C.8's
 * {@code query-methods-details.html} reference — «اشتقاق
 * الاستعلامات»); the soft-delete filter rides {@code @SoftDelete}, so
 * a soft-deleted answer neither resolves for its question nor blocks
 * the one-LIVE-answer invariant (the partial unique's own semantics,
 * the V70/V157/V160 shape).
 */
public interface ProductAnswerRepository extends JpaRepository<ProductAnswer, UUID> {

    /** The one-answer gate — derived existence on the live set (the 409 gate's first half). */
    boolean existsByQuestionId(UUID questionId);

    /** The answer's resolution for one question — the derived singleton read. */
    Optional<ProductAnswer> findByQuestionId(UUID questionId);

    /**
     * The answer-assembly bulk read: the feed's page of questions
     * resolves its answers in ONE query — the derived {@code In} form
     * (query-methods-details' own table), the classic N+1 avoidance.
     */
    List<ProductAnswer> findByQuestionIdIn(Collection<UUID> questionIds);
}
