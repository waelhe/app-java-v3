package com.marketplace.ledger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {
    Page<LedgerEntry> findByProviderId(UUID providerId, Pageable pageable);
    boolean existsBySourceIdAndType(UUID sourceId, LedgerEntryType type);
}
