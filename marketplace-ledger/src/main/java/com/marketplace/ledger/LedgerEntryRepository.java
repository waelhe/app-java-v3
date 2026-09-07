package com.marketplace.ledger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID>, RevisionRepository<LedgerEntry, UUID, Integer> {
    Optional<LedgerEntry> findBySourceId(UUID sourceId);

    /** Provider statement (L20): newest-first movement page for one provider. */
    Page<LedgerEntry> findByProviderIdOrderByCreatedAtDesc(UUID providerId, Pageable pageable);
}
