package com.marketplace.payments;

import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment>, RevisionRepository<Payment, UUID, Integer> {

    Optional<Payment> findByPaymentIntentId(UUID paymentIntentId);
}
