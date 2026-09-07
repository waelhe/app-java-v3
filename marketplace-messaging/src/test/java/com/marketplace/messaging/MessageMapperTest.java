package com.marketplace.messaging;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MessageMapperTest {

    private final MessageMapper mapper = Mappers.getMapper(MessageMapper.class);

    /**
     * B1: MapStruct maps every exposed field of {@link Message} onto
     * {@link MessageResponse} — including the newly exposed
     * {@code senderId} (same-name implicit mapping) so consuming clients
     * can identify the author of each message.
     */
    @Test
    void toResponse_mapsAllFields() {
        UUID id = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        Message msg = new Message(id, conversationId, senderId, "Hello");

        MessageResponse response = mapper.toResponse(msg);

        assertEquals(id, response.id());
        assertEquals(conversationId, response.conversationId());
        assertEquals(senderId, response.senderId());
        assertEquals("Hello", response.content());
        assertFalse(response.read());
    }
}
