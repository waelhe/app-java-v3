package com.marketplace.catalog;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * B-16 (compliance plan C.8 — the M2 store wave): the question side of
 * «أسئلة/أجوبة المنتج». The storefront feed below is a DERIVED query
 * method — C.8's first official reference is
 * {@code reference/repositories/query-methods-details.html} («اشتقاق
 * الاستعلامات»), so the read is derived from its method name, not
 * hand-written; the soft-delete filter rides {@code BaseEntity}'s
 * {@code @SoftDelete} (a deleted question is absent from the feed and
 * from the answer gate alike).
 */
public interface ProductQuestionRepository extends JpaRepository<ProductQuestion, UUID> {

    /**
     * The storefront's public Q&amp;A feed — the complete sort key
     * (newest first, {@code createdAt DESC, id DESC}) keeps every page
     * boundary stable (the community feed's own discipline: pagination
     * on the complete key, never on a timestamp alone).
     */
    Page<ProductQuestion> findByProductIdOrderByCreatedAtDescIdDesc(UUID productId, Pageable pageable);
}
