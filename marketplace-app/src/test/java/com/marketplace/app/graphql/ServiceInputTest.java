package com.marketplace.app.graphql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServiceInputTest {

    @Test
    void shouldCreate() {
        ServiceInput input = new ServiceInput("name", "desc", "cat", 1000L);
        assertEquals("name", input.name());
        assertEquals("desc", input.description());
        assertEquals("cat", input.category());
        assertEquals(1000L, input.priceCents());
    }
}
