package com.marketplace.shared.api;

/**
 * Centralised API path constants for versioning and module prefixes.
 */
public final class ApiConstants {

    public static final String API_V1 = "/api/v1";

    public static final String IDENTITY = API_V1 + "/users";
    /**
     * S1/B1 (platform-readiness audit §6 gate B1 — the registration and
     * password-lifecycle surface): the AUTH prefix — account self-service
     * operations that precede any authenticated session (register today;
     * the password lifecycle items of the gate when they open).
     */
    public static final String AUTH = API_V1 + "/auth";
    public static final String CATALOG = API_V1 + "/listings";
    public static final String BOOKING = API_V1 + "/bookings";
    public static final String PRICING = API_V1 + "/pricing";
    public static final String PAYMENTS = API_V1 + "/payments";
    public static final String REVIEWS = API_V1 + "/reviews";
    public static final String MESSAGING = API_V1 + "/messages";
    public static final String SEARCH = API_V1 + "/search";
    public static final String ADMIN = API_V1 + "/admin";

    private ApiConstants() {
    }
}