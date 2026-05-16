package com.marketplace.provider;

import static org.junit.jupiter.api.Assertions.*;

class ProviderRequestTest {

    @org.junit.jupiter.api.Test
    void shouldCreate() {
        ProviderRequest req = new ProviderRequest("Name", "Bio");
        assertEquals("Name", req.displayName());
        assertEquals("Bio", req.bio());
    }
}
