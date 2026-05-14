package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

class ApiConstantsTest {

    @Test
    void constantsAreCorrect() {
        assertEquals("/api/v1", ApiConstants.API_V1);
        assertEquals("/api/v1/users", ApiConstants.IDENTITY);
        assertEquals("/api/v1/listings", ApiConstants.CATALOG);
        assertEquals("/api/v1/bookings", ApiConstants.BOOKING);
        assertEquals("/api/v1/pricing", ApiConstants.PRICING);
        assertEquals("/api/v1/payments", ApiConstants.PAYMENTS);
        assertEquals("/api/v1/reviews", ApiConstants.REVIEWS);
        assertEquals("/api/v1/messages", ApiConstants.MESSAGING);
        assertEquals("/api/v1/search", ApiConstants.SEARCH);
        assertEquals("/api/v1/admin", ApiConstants.ADMIN);
    }

    @Test
    void constructorIsPrivate() throws Exception {
        var c = ApiConstants.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(c.getModifiers()));
    }
}
