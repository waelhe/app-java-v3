package com.marketplace.disputes;

import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<Dispute, UUID>, JpaSpecificationExecutor<Dispute>, RevisionRepository<Dispute, UUID, Integer> {
    List<Dispute> findByBookingId(UUID bookingId);
}
