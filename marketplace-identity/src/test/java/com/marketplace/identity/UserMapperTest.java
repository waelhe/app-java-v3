package com.marketplace.identity;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UserMapperTest {

    private final UserMapper mapper = Mappers.getMapper(UserMapper.class);

    @Test
    void toResponse_mapsAllFields() {
        UUID id = UUID.randomUUID();
        User user = new User(id, "sub-1", "a@b.com", "Alice", UserRole.CONSUMER);

        UserResponse response = mapper.toResponse(user);

        assertNotNull(response);
        assertEquals(id, response.id());
        assertEquals("a@b.com", response.email());
        assertEquals("Alice", response.displayName());
    }
}
