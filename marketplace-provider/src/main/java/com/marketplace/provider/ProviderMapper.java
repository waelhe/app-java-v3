package com.marketplace.provider;

import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ProviderMapper {

    ProviderResponse toResponse(ProviderProfile profile);
}
