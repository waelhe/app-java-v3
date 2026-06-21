package com.marketplace.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, UUID> {
    List<RecoveryCode> findByUserIdAndUsedFalse(UUID userId);
}
