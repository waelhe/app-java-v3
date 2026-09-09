package com.marketplace.identity;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    /**
     * I7 (account-pseudonymization-plan §5-أ step 3): a pseudonymized account
     * renders the neutral label at the response-DTO level — never stored
     * (storage is NULL); the label is the API's honest rendering.
     */
    @Test
    void toResponse_rendersNeutralLabelForPseudonymizedAccounts() {
        UUID id = UUID.randomUUID();
        User user = new User(id, "gone-subject", "g@b.com", "Gone", UserRole.CONSUMER);
        user.applyPseudonymization(
                "anon-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

        UserResponse response = mapper.toResponse(user);

        assertEquals(UserService.FORMER_MEMBER_LABEL, response.displayName());
        assertNull(response.email());
        assertEquals(id, response.id()); // the stable UUID never changes.
    }
}
