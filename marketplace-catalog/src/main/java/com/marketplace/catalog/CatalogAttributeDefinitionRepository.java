package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CatalogAttributeDefinitionRepository extends JpaRepository<CatalogAttributeDefinition, UUID> {
    Optional<CatalogAttributeDefinition> findByCode(String code);
    List<CatalogAttributeDefinition> findAllByOrderByCodeAsc();

    /**
     * The 2026-11-06 #808 round review (Minor · Stability &amp;
     * Availability): the position-deriving writers serialize on the
     * attribute's own row (the ProductTypeRepository.findByIdForUpdate
     * precedent — every writer that derives positions from a prior read
     * takes the row lock, so no two writers derive the same position and
     * answer the position-unique index's raw error instead of the
     * classified result).
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select d from CatalogAttributeDefinition d where d.id = :id")
    Optional<CatalogAttributeDefinition> findByIdForUpdate(
            @org.springframework.data.repository.query.Param("id") UUID id);

    /**
     * The 2026-11-06 #808 round review (Major · Data Integrity &amp;
     * Integration): the reference-CREATING reader's share lock — the
     * variant registration's CHOICE leg reads the definition FOR SHARE
     * (the ProductTypeRepository.findByIdForShare precedent, the same
     * pairing the deleteProductType wall documents) so the option-delete
     * guard's FOR-UPDATE read and an in-flight registration serialize:
     * either the registration's share lock holds and the delete's
     * existsByOptionId probe sees its committed reference (the classified
     * INVALID refusal), or the delete committed first and the soft-deleted
     * option answers the registration its own classified 404. An unlocked
     * read left the guard's probe a TOCTOU — zero references at probe
     * time, a committed reference at delete time.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_READ)
    @org.springframework.data.jpa.repository.Query("select d from CatalogAttributeDefinition d where d.id = :id")
    Optional<CatalogAttributeDefinition> findByIdForShare(
            @org.springframework.data.repository.query.Param("id") UUID id);
}
