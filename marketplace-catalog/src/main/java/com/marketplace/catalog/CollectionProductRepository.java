package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * L-slice (the canonical Saleor plan §5-P2): the collection membership
 * registry's persistence port. {@code @SoftDelete} (BaseEntity) already
 * filters {@code is_deleted = false} from every derived query — the live
 * pair's partial unique index and these lookups stay aligned.
 */
public interface CollectionProductRepository extends JpaRepository<CollectionProduct, UUID> {

    /** The membership's own ordering read — one collection's live rows in (position, id). */
    List<CollectionProduct> findByCollectionIdOrderByPositionAscIdAsc(UUID collectionId);

    /** The duplicate-add precheck — the live pair or empty. */
    Optional<CollectionProduct> findByCollectionIdAndProductId(UUID collectionId, UUID productId);

    /** The live pairs for the given collection and product ids (the duplicate precheck's batch shape). */
    List<CollectionProduct> findByCollectionIdAndProductIdIn(UUID collectionId, List<UUID> productIds);

    /**
     * B6 (CAT-01 — the 2026-11-03 adversarial review, review-pr-808):
     * the membership position allocation's serialization seam — the SAME
     * advisory transaction lock discipline the media registry established
     * ({@code MediaAssetRepository.lockPostPositionAllocation}, A-17/C.7):
     * the append and the reorder acquire the collection's own lock
     * BEFORE reading the live membership, so concurrent writers
     * serialize at the allocation point and the V298 partial unique
     * index ({@code uq_collection_products_position_live}) stays a
     * backstop, never the user-visible answer. Lock key:
     * {@code hashtextextended(collection_id, 0)} — per collection,
     * transaction-scoped (released at COMMIT/ROLLBACK).
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:collectionId, 0))", nativeQuery = true)
    void lockCollectionPositionAllocation(@Param("collectionId") String collectionId);
}
