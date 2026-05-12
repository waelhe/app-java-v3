package com.marketplace.disputes;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface DisputeMessageRepository extends JpaRepository<DisputeMessage, UUID> {
    Page<DisputeMessage> findByDisputeId(UUID disputeId, Pageable pageable);
}
