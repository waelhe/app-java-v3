package com.marketplace.disputes;

import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface DisputeMapper {

    DisputeResponse toResponse(Dispute dispute);
}
