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
    public static final String ORDERS = API_V1 + "/orders";
    /**
     * A-11 (compliance plan wave C: C.1): the buyer's cart — a /me-family
     * surface (identity from the authentication itself, the same family
     * shape as favorites and saved-searches).
     */
    public static final String CART = API_V1 + "/me/cart";
    public static final String SEARCH = API_V1 + "/search";
    /**
     * §5.1 (plan #536): the unified search entry — ONE door by domain and
     * geography over the measured per-domain sources; the listings facets
     * stay on {@link #SEARCH} itself.
     */
    public static final String SEARCH_UNIFIED = SEARCH + "/unified";
    public static final String ADMIN = API_V1 + "/admin";
    /**
     * A-18 (compliance plan C.12): the public platform-release path — the
     * client's boot-time read (the §7/2 first-screen moment), a permitAll
     * GET family.
     */
    public static final String RELEASES = API_V1 + "/releases";
    /**
     * ADR-0001 (plan §Phase 1): the verification-credential family — the
     * self-service pair (submit + mine); the admin queue rides the
     * {@link #ADMIN} prefix with the {@link #VERIFICATION_CREDENTIALS_SUFFIX}.
     */
    public static final String VERIFICATION_CREDENTIALS = API_V1 + "/verification-credentials";
    /** The admin half of the verification-credential family (after {@link #ADMIN}). */
    public static final String VERIFICATION_CREDENTIALS_SUFFIX = "/verification-credentials";

    private ApiConstants() {
    }
}
