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

    /**
     * The one-default probe — the row the create/update swaps read before
     * clearing the standing default (the B10 clears-before-set law).
     *
     * <p><b>The derivation's own law (CI-measured on the candidate head,
     * 2026-11-08):</b> Spring Data derives the property from the method
     * name, and the entity's JPA attribute is {@code defaultType} (the
     * field name — @Id sits on the field, so the whole metamodel is
     * field-accessed). The former {@code findByDefaultTrue} named a
     * property that does not exist ("default"), so the FIRST real
     * invocation answered {@code PropertyReferenceException} — masked as
     * INT-001 — a latent derivation bug no unit test could see (the mocks
     * stub the method without deriving it) and no integration test had
     * reached (the default-swap paths only execute when a caller passes
     * {@code isDefault}, which none did until the round-5 first-row fix
     * forced the first type default). The rename names the real attribute:
     * {@code defaultType} + the {@code True} keyword.</p>
     */
    Optional<CustomerType> findByDefaultTypeTrue();

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
