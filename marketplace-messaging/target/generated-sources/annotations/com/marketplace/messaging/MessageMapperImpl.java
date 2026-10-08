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
public class MessageMapperImpl implements MessageMapper {

    @Override
    public MessageResponse toResponse(Message message) {
        if ( message == null ) {
            return null;
        }

        UUID id = null;
        UUID conversationId = null;
        UUID senderId = null;
        String content = null;
        boolean read = false;
        Instant createdAt = null;
        Instant updatedAt = null;

        id = message.getId();
        conversationId = message.getConversationId();
        senderId = message.getSenderId();
        content = message.getContent();
        read = message.isRead();
        createdAt = message.getCreatedAt();
        updatedAt = message.getUpdatedAt();

        MessageResponse messageResponse = new MessageResponse( id, conversationId, senderId, content, read, createdAt, updatedAt );

        return messageResponse;
    }
}
