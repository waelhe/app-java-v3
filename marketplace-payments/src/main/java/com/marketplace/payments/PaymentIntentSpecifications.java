package com.marketplace.payments;

import com.marketplace.payments.spi.PaymentIntentSearch;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

/**
 * The payment-intent search predicates. Seeded by the R4 deterministic
 * per-booking lookups; widened by the payments plan's Phase-1 unified
 * finance-operator read model — the "investigate any origin by a single
 * search" acceptance journey needs ORIGIN, SUBJECT, CONSUMER and WINDOW
 * predicates over the same {@link PaymentIntent} root the repository
 * already exposes through {@code JpaSpecificationExecutor} (the Spring
 * Data JPA documented specification mechanism — no hand-written SQL to
 * drift from the entity mapping).
 */
public final class PaymentIntentSpecifications {

    private PaymentIntentSpecifications() {}

    public static Specification<PaymentIntent> hasBookingId(UUID bookingId) {
        return (root, query, cb) -> cb.equal(root.get("bookingId"), bookingId);
    }

    public static Specification<PaymentIntent> hasStatus(PaymentIntentStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /** The origin word — the DB CHECK vocabulary ('BOOKING', 'AD', 'ORDER', 'LOAN', ...). */
    public static Specification<PaymentIntent> hasOrigin(String origin) {
        return (root, query, cb) -> cb.equal(root.get("origin"), origin);
    }

    /** The payer's user id — the consumer column every origin carries. */
    public static Specification<PaymentIntent> hasConsumerId(UUID consumerId) {
        return (root, query, cb) -> cb.equal(root.get("consumerId"), consumerId);
    }

    /**
     * The ORIGIN-AGNOSTIC subject probe: the id matches whichever subject
     * column the intent's origin pins. The four columns are mutually
     * exclusive by the pairing CHECKs ({@code ck_payment_intents_origin_pairing}
     * and its siblings), so the disjunction can never match two subjects
     * of one row — it is one search over one subject, whichever it is.
     */
    public static Specification<PaymentIntent> hasSubjectId(UUID subjectId) {
        return (root, query, cb) -> cb.or(
                cb.equal(root.get("bookingId"), subjectId),
                cb.equal(root.get("adCampaignId"), subjectId),
                cb.equal(root.get("orderId"), subjectId),
                cb.equal(root.get("loanId"), subjectId));
    }

    /** The window's exclusive start — {@code createdAt >= from}. */
    public static Specification<PaymentIntent> createdAfter(Instant from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    /** The window's exclusive end — {@code createdAt < to}. */
    public static Specification<PaymentIntent> createdBefore(Instant to) {
        return (root, query, cb) -> cb.lessThan(root.get("createdAt"), to);
    }

    /**
     * The operator's whole search, AND-composed. Null fields are skipped
     * (the contract's own rule: an absent field does not constrain); the
     * neutral search is the unconstrained page (the caller's own
     * decision — the controller treats a no-parameter request as the
     * plain list, never as a zero-match query).
     */
    public static Specification<PaymentIntent> fromSearch(PaymentIntentSearch search) {
        // The house composition seed (the PropertyFilterAdapter pattern):
        // a conjunction, never Specification.where(null).
        Specification<PaymentIntent> spec = (root, query, cb) -> cb.conjunction();
        if (search.origin() != null) {
            spec = spec.and(hasOrigin(search.origin()));
        }
        if (search.status() != null) {
            spec = spec.and(hasStatus(PaymentIntentStatus.valueOf(search.status())));
        }
        if (search.subjectId() != null) {
            spec = spec.and(hasSubjectId(search.subjectId()));
        }
        if (search.consumerId() != null) {
            spec = spec.and(hasConsumerId(search.consumerId()));
        }
        if (search.createdAfter() != null) {
            spec = spec.and(createdAfter(search.createdAfter()));
        }
        if (search.createdBefore() != null) {
            spec = spec.and(createdBefore(search.createdBefore()));
        }
        return spec;
    }
}
