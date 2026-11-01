package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
