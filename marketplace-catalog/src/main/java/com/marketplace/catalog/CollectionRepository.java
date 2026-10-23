package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * L-slice (the canonical Saleor plan §5-P2): the collection registry's
 * persistence port. {@code @SoftDelete} (BaseEntity) already filters
 * {@code is_deleted = false} from every derived query — the partial
 * unique index and these lookups stay aligned (no TOCTOU window, the V47
 * slug precedent).
 */
public interface CollectionRepository extends JpaRepository<Collection, UUID> {

    /** The write gate's precheck — a LIVE registry row for the slug or empty. */
    Optional<Collection> findBySlug(String slug);

    /** The whole registry, in creation's own stable order (the in-memory scroll's source). */
    List<Collection> findAllByOrderByIdAsc();
}
