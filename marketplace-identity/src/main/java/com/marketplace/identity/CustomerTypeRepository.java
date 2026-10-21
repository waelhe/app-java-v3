package com.marketplace.identity;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Window;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * customer-type registry's repository — derived queries per the official
 * Spring Data JPA contract, including the forward-only keyset scrolling
 * window methods the {@code customerTypes} read root executes (the
 * UserRepository keyset precedent: static ordering baked into the method
 * name, {@code id} tiebreaker, soft-deleted rows absent by the entity's
 * own filter).
 */
public interface CustomerTypeRepository extends JpaRepository<CustomerType, UUID>,
        RevisionRepository<CustomerType, UUID, Integer> {

    Optional<CustomerType> findBySlug(String slug);

    Optional<CustomerType> findByDefaultTrue();

    boolean existsBySlug(String slug);

    List<CustomerType> findAllByOrderByCreatedAtAscIdAsc();

    List<CustomerType> findByNameContainingIgnoreCaseOrderByNameAscIdAsc(String name, KeysetScrollPosition position, Limit limit);

    List<CustomerType> findByNameContainingIgnoreCaseOrderByNameDescIdDesc(String name, KeysetScrollPosition position, Limit limit);

    List<CustomerType> findByNameContainingIgnoreCaseOrderBySlugAscIdAsc(String name, KeysetScrollPosition position, Limit limit);

    Window<CustomerType> findAllByOrderByNameAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CustomerType> findAllByOrderByNameDescIdDesc(KeysetScrollPosition position, Limit limit);

    Window<CustomerType> findAllByOrderBySlugAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CustomerType> findAllByOrderBySlugDescIdDesc(KeysetScrollPosition position, Limit limit);

    long count();

    long countByNameContainingIgnoreCase(String name);
}
