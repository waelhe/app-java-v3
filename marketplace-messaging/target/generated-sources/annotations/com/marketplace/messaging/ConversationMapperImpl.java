package com.marketplace.messaging;

import java.time.Instant;
import java.util.UUID;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-10-08T00:06:23+0000",
    comments = "version: 1.6.3, compiler: javac, environment: Java 25.0.4.1 (Eclipse Adoptium)"
)
@Component
public class ConversationMapperImpl implements ConversationMapper {

    @Override
    public ConversationResponse toResponse(Conversation conversation) {
        if ( conversation == null ) {
            return null;
        }

        UUID id = null;
        UUID bookingId = null;
        Instant createdAt = null;
        Instant updatedAt = null;

        id = conversation.getId();
        bookingId = conversation.getBookingId();
        createdAt = conversation.getCreatedAt();
        updatedAt = conversation.getUpdatedAt();

        ConversationResponse conversationResponse = new ConversationResponse( id, bookingId, createdAt, updatedAt );

        return conversationResponse;
    }
}
