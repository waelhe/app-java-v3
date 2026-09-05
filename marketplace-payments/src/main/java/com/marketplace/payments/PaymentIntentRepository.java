package com.marketplace.payments;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID>, JpaSpecificationExecutor<PaymentIntent>, RevisionRepository<PaymentIntent, UUID, Integer> {

    Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentIntent> findByBookingId(UUID bookingId);

    /** Resolves a local intent from the remote PSP intent id (webhook path, V33). */
    Optional<PaymentIntent> findByPspIntentId(String pspIntentId);

    Page<PaymentIntentSummaryView> findAllSummariesBy(Pageable pageable);
}