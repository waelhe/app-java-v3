package com.marketplace.ledger;

import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProviderBalanceRepository extends JpaRepository<ProviderBalance, UUID>, RevisionRepository<ProviderBalance, UUID, Integer> {
}
