package com.marketplace.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UserTest {

    @Test
    void createGeneratesId() {
        var user = User.create("sub-1", "test@test.com", "Test", UserRole.CONSUMER);
        assertNotNull(user.getId());
        assertEquals("sub-1", user.getSubject());
        assertEquals("test@test.com", user.getEmail());
        assertEquals("Test", user.getDisplayName());
        assertEquals(UserRole.CONSUMER, user.getRole());
    }

    @Test
    void updateProfileChangesEmailAndDisplayName() {
        var user = User.create("sub-1", "old@test.com", "Old", UserRole.PROVIDER);
        user.updateProfile("new@test.com", "New");
        assertEquals("new@test.com", user.getEmail());
        assertEquals("New", user.getDisplayName());
    }

    @Test
    void changeRoleUpdatesRole() {
        var user = User.create("sub-1", "test@test.com", "Test", UserRole.CONSUMER);
        user.changeRole(UserRole.ADMIN);
        assertEquals(UserRole.ADMIN, user.getRole());
    }

    @Test
    void allArgsConstructorSetsFields() {
        var id = java.util.UUID.randomUUID();
        var user = new User(id, "sub-1", "test@test.com", "Test", UserRole.PROVIDER);
        assertEquals(id, user.getId());
        assertEquals("sub-1", user.getSubject());
        assertEquals("test@test.com", user.getEmail());
        assertEquals("Test", user.getDisplayName());
        assertEquals(UserRole.PROVIDER, user.getRole());
    }
}
