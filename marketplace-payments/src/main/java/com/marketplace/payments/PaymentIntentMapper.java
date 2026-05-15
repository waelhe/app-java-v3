package com.marketplace.payments;

import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface PaymentIntentMapper {

    PaymentIntentResponse toResponse(PaymentIntent intent);
}
