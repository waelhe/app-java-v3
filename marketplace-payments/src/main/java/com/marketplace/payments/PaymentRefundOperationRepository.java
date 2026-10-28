package com.marketplace.payments;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The dispute-refund operation journal's persistence (V292).
 *
 * <p><b>Public by contract (CI round 2, measured 2026-10-28):</b> Spring
 * Data repository interfaces MUST be public — the repository proxy is a
 * JDK dynamic proxy, and {@code Proxy.newProxyInstance} refuses a
 * non-public interface ("non-public interface is not defined by the given
 * loader" — the full-integration boot failure on head 67f2249: the whole
 * context died at {@code paymentRefundOperationRepository} creation).
 * The house shape is public on every other repository interface
 * ({@code PaymentRepository}, {@code PaymentIntentRepository}).</p>
 */
public interface PaymentRefundOperationRepository extends JpaRepository<PaymentRefundOperation, String> {
}
