package com.marketplace.messaging;

import org.mapstruct.Mapper;

@org.springframework.modulith.NamedInterface("messaging-api")
@Mapper(componentModel = "spring")
public interface MessageMapper {

    MessageResponse toResponse(Message message);
}
