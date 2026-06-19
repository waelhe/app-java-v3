package com.marketplace.shared.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Repository for {@link StorageFile} entities.
 *
 * <p>Extends {@link JpaRepository} for standard CRUD operations.
 * Inherits {@link org.springframework.data.repository.history.RevisionRepository}
 * via BaseEntity's {@code @Audited} annotation for audit revision queries.
 */
public interface StorageFileRepository extends JpaRepository<StorageFile, UUID> {
}
