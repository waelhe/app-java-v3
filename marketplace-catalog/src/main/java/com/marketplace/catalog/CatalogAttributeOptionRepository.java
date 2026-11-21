package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CatalogAttributeOptionRepository extends JpaRepository<CatalogAttributeOption, UUID> {
    List<CatalogAttributeOption> findAllByAttributeDefinitionIdOrderByPositionAsc(UUID attributeDefinitionId);
    Optional<CatalogAttributeOption> findByAttributeDefinitionIdAndCode(UUID attributeDefinitionId, String code);
    boolean existsByAttributeDefinitionIdAndPosition(UUID attributeDefinitionId, int position);
    long countByAttributeDefinitionId(UUID attributeDefinitionId);

    /**
     * The append-position derivation's own lookup — the owner's max+1
     * semantics (the agent-2 packet's attributeValueCreate root): the
     * highest-positioned live option of the attribute, empty when the
     * attribute has none yet (position 1 starts the sequence — the
     * append's max+1 derivation and the reorder's 1-based compaction
     * agree on the first position, the 2026-11-06 #808 round's javadoc
     * correction).
     */
    Optional<CatalogAttributeOption> findFirstByAttributeDefinitionIdOrderByPositionDesc(UUID attributeDefinitionId);

    /**
     * The option's OWN definition-id projection — the scalar read that
     * resolves the owning family WITHOUT loading the entity (the round-13
     * drop/lock/load order: no option row enters the persistence context
     * before its family's FOR-UPDATE lock is held, so the locked load
     * below always answers the authoritative post-lock state).
     */
    @Query("select o.attributeDefinitionId from CatalogAttributeOption o where o.id = :id")
    Optional<UUID> findDefinitionIdById(@Param("id") UUID id);

    /**
     * The option's own FOR-UPDATE twin (the definitions repository's
     * findByIdForUpdate precedent): a locking load that answers the row's
     * ABSENCE as an empty Optional — never an exception.
     *
     * <p>The 2026-11-09 #808 round-26 review (Minor · Stability &amp;
     * Availability): the refresh(entity, PESSIMISTIC_WRITE) re-read is
     * POISONED by the installed Hibernate (7.4.13, the Boot 4.1.2 pin —
     * ExceptionConverterImpl.rollbackIfNecessary over the
     * ObjectNotFoundException→EntityNotFoundException conversion):
     * catching the EntityNotFoundException does NOT keep the transaction
     * usable, the session is marked rollback-only the moment the refresh
     * throws, and the batch's earlier deletions rolled back at commit
     * with UnexpectedRollbackException instead of the honest count. The
     * projection-first order ({@link #findDefinitionIdById}) plus THIS
     * locked load keeps every verdict exception-free: the family lock is
     * taken before any option entity is managed, the locked load reads
     * the authoritative state (a rival writer that committed while this
     * transaction waited is seen), and a vanished row answers
     * {@code Optional.empty()} — the classified NOT_FOUND / the honest
     * skip, with the row lock held to the transaction's end.</p>
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CatalogAttributeOption o where o.id = :id")
    Optional<CatalogAttributeOption> findByIdForUpdate(@Param("id") UUID id);
}
