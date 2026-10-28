package com.marketplace.payments;

import org.springframework.data.jpa.repository.JpaRepository;

/** The dispute-refund operation journal's persistence (V292). */
interface PaymentRefundOperationRepository extends JpaRepository<PaymentRefundOperation, String> {
}
