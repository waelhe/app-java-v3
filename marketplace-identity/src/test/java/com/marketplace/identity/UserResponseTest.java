package com.marketplace.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserResponseTest {

    @Test
    void fromMapsUserToResponse() {
        var user = User.create("sub-1", "test@test.com", "Test", UserRole.ADMIN);

        var response = UserResponse.from(user);

        assertEquals(user.getId(), response.id());
        assertEquals("test@test.com", response.email());
        assertEquals("Test", response.displayName());
        assertEquals(user.getCreatedAt(), response.createdAt());
        assertEquals(user.getUpdatedAt(), response.updatedAt());
    }

    @Test
    void recordConstructor() {
        var id = java.util.UUID.randomUUID();
        var now = java.time.Instant.now();
        var response = new UserResponse(id, "test@test.com", "Test", now, now);
        assertEquals(id, response.id());
        assertEquals("test@test.com", response.email());
        assertEquals("Test", response.displayName());
        assertEquals(now, response.createdAt());
        assertEquals(now, response.updatedAt());
    }
}
