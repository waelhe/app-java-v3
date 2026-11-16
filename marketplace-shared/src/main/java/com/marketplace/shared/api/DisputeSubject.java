package com.marketplace.shared.api;

/**
 * ADR-0009 (plan D-09 closure — ADR-0004's documented dispute deferral):
 * the dispute's subject kind. The disputes module generalized its subject
 * from booking-only to a two-subject contract — the booking (the V20
 * original) and the loan (the lending workflow's damage dispute).
 *
 * <p>This enum is part of the shared event vocabulary: the events carry
 * their subject so a consumer branches on the fact without re-querying
 * (the payload-carries-the-truth discipline).
 */
public enum DisputeSubject {
    BOOKING,
    LOAN
}
