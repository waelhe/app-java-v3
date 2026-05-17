package com.marketplace.messaging;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MessageMapperTest {

    private final MessageMapper mapper = Mappers.getMapper(MessageMapper.class);

    @Test
    void toResponse_mapsAllFields() {
        UUID id = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        Message msg = new Message(id, conversationId, UUID.randomUUID(), "Hello");

        MessageResponse response = mapper.toResponse(msg);

        assertEquals(id, response.id());
        assertEquals(conversationId, response.conversationId());
        assertEquals("Hello", response.content());
        assertFalse(response.read());
    }
}
