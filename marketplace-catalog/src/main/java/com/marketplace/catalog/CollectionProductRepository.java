package com.marketplace.catalog;

import org.springframework.data.domain.Pageable;
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

    /**
     * B8 (CAT-03 — the 2026-11-03 adversarial review, review-pr-808): the
     * member-products scroll's bounded SQL pages. The scroll previously
     * loaded the WHOLE live membership and resolved every member product
     * with its own {@code findById} (N+1) before sorting and paginating in
     * memory — one statement per member and an unbounded heap load per
     * collection page. These declared queries ride Spring Data JPA's
     * documented Pageable-on-@Query path (setFirstResult/setMaxResults —
     * the page is cut in SQL, never in memory) over the membership's own
     * (position, product id) ordering key, with the member-product
     * publication law joined into the SAME statement: the non-ADMIN
     * ({@code staff = false}) scroll carries the ACTIVE products alone,
     * so a run of draft members can never shrink a public caller's page.
     * Both entities are HQL references, so {@code @SoftDelete} keeps
     * filtering the deleted rows out of the join. Each row answers
     * {@code [Integer position, Product]} — the position rides the
     * keyset cursor, the product renders the bounded projection.
     */
    @Query("""
            SELECT cp.position, p FROM CollectionProduct cp, Product p
            WHERE cp.collectionId = :collectionId AND cp.productId = p.id
              AND (:staff = TRUE OR p.status = com.marketplace.catalog.StoreProductStatus.ACTIVE)
            ORDER BY cp.position ASC, p.id ASC
            """)
    List<Object[]> findFirstMembershipProductRows(@Param("collectionId") UUID collectionId,
                                                  @Param("staff") boolean staff,
                                                  Pageable pageable);

    /** The cursor-continuation page — the SAME joined law above the (position, id) keyset cursor. */
    @Query("""
            SELECT cp.position, p FROM CollectionProduct cp, Product p
            WHERE cp.collectionId = :collectionId AND cp.productId = p.id
              AND (:staff = TRUE OR p.status = com.marketplace.catalog.StoreProductStatus.ACTIVE)
              AND (cp.position > :afterPosition OR (cp.position = :afterPosition AND p.id > :afterId))
            ORDER BY cp.position ASC, p.id ASC
            """)
    List<Object[]> findNextMembershipProductRows(@Param("collectionId") UUID collectionId,
                                                 @Param("staff") boolean staff,
                                                 @Param("afterPosition") int afterPosition,
                                                 @Param("afterId") UUID afterId,
                                                 Pageable pageable);

    /** The scroll's honest totalCount — the SAME visibility, ONE count statement. */
    @Query("""
            SELECT count(cp) FROM CollectionProduct cp, Product p
            WHERE cp.collectionId = :collectionId AND cp.productId = p.id
              AND (:staff = TRUE OR p.status = com.marketplace.catalog.StoreProductStatus.ACTIVE)
            """)
    long countVisibleMembershipProducts(@Param("collectionId") UUID collectionId,
                                        @Param("staff") boolean staff);
}
