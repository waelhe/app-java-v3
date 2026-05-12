package com.marketplace.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProviderBalanceRepository extends JpaRepository<ProviderBalance, UUID> {
}
