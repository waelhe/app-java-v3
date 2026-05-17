package com.marketplace.messaging;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConversationMapperTest {

    private final ConversationMapper mapper = Mappers.getMapper(ConversationMapper.class);

    @Test
    void toResponse_mapsAllFields() {
        UUID id = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Conversation conv = new Conversation(id, bookingId, UUID.randomUUID(), UUID.randomUUID());

        ConversationResponse response = mapper.toResponse(conv);

        assertEquals(id, response.id());
        assertEquals(bookingId, response.bookingId());
    }
}
