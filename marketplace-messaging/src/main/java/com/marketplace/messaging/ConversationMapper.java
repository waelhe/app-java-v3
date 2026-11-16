package com.marketplace.messaging;

import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ConversationMapper {

    ConversationResponse toResponse(Conversation conversation);
}
