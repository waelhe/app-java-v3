package com.marketplace.community;

/**
 * L49 (the Nextdoor-2026 completeness wave — gap #4, the events layer):
 * a neighborhood event's registration model — the design's own three
 * states («مفتوح للجميع» / «مقاعد محدودة» / «حجز طاولات»), the
 * vocabulary the frontend contract {@code EVENT_REGISTRATIONS} carries
 * verbatim.
 *
 * <p>The registration and the capacity are ONE integrity rule, not two
 * (the V83 {@code chk_neighborhood_events_registration_capacity}
 * backstop): {@code OPEN} means no capacity to count — the whole
 * neighborhood may come; {@code LIMITED_SEATS} and
 * {@code TABLE_RESERVATION} mean a strictly positive capacity the RSVP
 * gate counts seats against ({@code TABLE_RESERVATION} counts tables,
 * one per family — the seat arithmetic is the same one-per-member
 * RSVP either way). The service validates the pair BEFORE any write
 * (the friendly 400); the DB CHECK is the backstop.
 */
public enum EventRegistration {
    OPEN,
    LIMITED_SEATS,
    TABLE_RESERVATION
}
