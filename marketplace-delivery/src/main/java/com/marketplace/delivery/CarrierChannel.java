package com.marketplace.delivery;

/**
 * The delivery-domain contract's adapter boundary (§3.4: «Use a
 * provider-neutral port and separate adapters … A partner's capabilities
 * differ … and must be declared explicitly»): ONE channel interface every
 * carrier implements — the platform's own fleet today (the ADR-0013 D4
 * launch mode), external courier/carrier partners behind their own
 * adapters when their approvals arrive. No provider-specific API leaks
 * into the core domain contracts: the core sees {@link #code},
 * {@link #capabilities}, and {@link #serviceable} — nothing else.
 */
public interface CarrierChannel {

    /** The carrier's stable code — the {@code delivery_requests.carrier} value. */
    String code();

    /** The carrier's declared capabilities (the explicit contract, §3.4). */
    java.util.Set<CarrierCapability> capabilities();

    /**
     * Whether THIS carrier can execute a leg between the two points —
     * the honest pre-check the quote path and the dispatch queue read.
     * The platform fleet answers from its configured service area.
     */
    boolean serviceable(double pickupLatitude, double pickupLongitude,
                        double dropoffLatitude, double dropoffLongitude);

    /** The declared capabilities (the §3.4 vocabulary — grows with real partners). */
    enum CarrierCapability {
        /** Quotes and executes on-demand legs (the platform fleet's own mode). */
        ON_DEMAND,
        /** Accepts scheduled (future-window) legs. */
        SCHEDULED,
        /** Provides live tracking events. */
        LIVE_TRACKING,
        /** Captures proof of delivery. */
        PROOF_OF_DELIVERY,
        /** Handles returns (return-to-sender legs). */
        RETURNS
    }
}
