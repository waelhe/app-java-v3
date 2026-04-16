package com.marketplace.shared.api;

/**
 * Centralised API path constants for versioning and module prefixes.
 */
public final class ApiConstants {

    public static final String API_V1 = "/api/v1";

    public static final String IDENTITY = API_V1 + "/users";
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