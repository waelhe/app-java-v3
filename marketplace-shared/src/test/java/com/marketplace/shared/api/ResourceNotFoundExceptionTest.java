package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResourceNotFoundExceptionTest {

    @Test
    void constructorSetsMessage() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Booking not found");
        assertEquals("Booking not found", ex.getMessage());
    }

    @Test
    void isRuntimeException() {
        assertInstanceOf(RuntimeException.class, new ResourceNotFoundException("test"));
    }
}
