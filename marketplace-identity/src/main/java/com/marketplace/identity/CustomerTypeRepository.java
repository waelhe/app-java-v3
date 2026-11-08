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

    /**
     * The 2026-11-08 #810 round-5 review (Minor · Functional Correctness):
     * the attribute-modification serialization point — the locked load of
     * the type row the three attribute paths (assign/unassign/reorder)
     * take BEFORE reading the pairs. Two administrators assigning
     * different attributes concurrently used to compute the SAME
     * nextPosition from two unlocked reads (the positions then tie and
     * the UUID tiebreaker decides the order nobody asked for). The row
     * lock serializes the read-position-mark window (the official Spring
     * Data JPA @Lock derived-query contract — the UserRepository
     * findByIdForUpdate / CategoryRepository precedent verbatim).
     */
    @org.springframework.data.jpa.repository.Lock(
            jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
            "select t from CustomerType t where t.id = :id")
    Optional<CustomerType> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") UUID id);

    Optional<CustomerType> findByDefaultTrue();

    boolean existsBySlug(String slug);

    List<CustomerType> findAllByOrderByCreatedAtAscIdAsc();

    /**
     * The search legs (the CodeRabbit pagination round): the same real
     * keyset window form the unfiltered sorts carry — Window results with
     * their own hasNext, KeysetScrollPosition cursors the fetcher's
     * forward gate consumes. The sort vocabulary covers both fields, both
     * directions (the descending slug method completes it).
     */
    Window<CustomerType> findByNameContainingIgnoreCaseOrderByNameAscIdAsc(String name, KeysetScrollPosition position, Limit limit);

    Window<CustomerType> findByNameContainingIgnoreCaseOrderByNameDescIdDesc(String name, KeysetScrollPosition position, Limit limit);

    Window<CustomerType> findByNameContainingIgnoreCaseOrderBySlugAscIdAsc(String name, KeysetScrollPosition position, Limit limit);

    Window<CustomerType> findByNameContainingIgnoreCaseOrderBySlugDescIdDesc(String name, KeysetScrollPosition position, Limit limit);

    Window<CustomerType> findAllByOrderByNameAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CustomerType> findAllByOrderByNameDescIdDesc(KeysetScrollPosition position, Limit limit);

    Window<CustomerType> findAllByOrderBySlugAscIdAsc(KeysetScrollPosition position, Limit limit);

    Window<CustomerType> findAllByOrderBySlugDescIdDesc(KeysetScrollPosition position, Limit limit);

    long count();

    long countByNameContainingIgnoreCase(String name);
}
