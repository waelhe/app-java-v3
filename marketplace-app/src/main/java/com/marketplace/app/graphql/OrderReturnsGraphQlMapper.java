package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.OrderReturn;
import com.marketplace.app.graphql.generated.types.OrderReturnLine;
import com.marketplace.app.graphql.generated.types.OrderReturnPage;
import com.marketplace.app.graphql.generated.types.OrderReturnStatus;
import com.marketplace.app.graphql.generated.types.OrderReturnType;
import com.marketplace.orders.spi.OrderReturnsPort;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;
import java.util.UUID;

/** Maps the existing RMA domain projections to the schema-first GraphQL contract. */
@Mapper(componentModel = "spring")
public interface OrderReturnsGraphQlMapper {

    @Mapping(target = "id", expression = "java(id(view.id()))")
    @Mapping(target = "orderId", expression = "java(id(view.orderId()))")
    @Mapping(target = "type", expression = "java(toType(view.type()))")
    @Mapping(target = "status", expression = "java(toStatus(view.status()))")
    @Mapping(target = "refundAmountMinor", expression = "java(minor(view.refundAmountMinor()))")
    @Mapping(target = "replacementOrderId", expression = "java(id(view.replacementOrderId()))")
    @Mapping(target = "requestedAt", expression = "java(instant(view.requestedAt()))")
    @Mapping(target = "approvedAt", expression = "java(instant(view.approvedAt()))")
    @Mapping(target = "receivedAt", expression = "java(instant(view.receivedAt()))")
    @Mapping(target = "completedAt", expression = "java(instant(view.completedAt()))")
    OrderReturn toResponse(OrderReturnsPort.OrderReturnView view);

    @Mapping(target = "id", expression = "java(id(view.id()))")
    @Mapping(target = "orderItemId", expression = "java(id(view.orderItemId()))")
    @Mapping(target = "productId", expression = "java(id(view.productId()))")
    @Mapping(target = "variantId", expression = "java(id(view.variantId()))")
    OrderReturnLine toResponse(OrderReturnsPort.OrderReturnLineView view);

    @Mapping(target = "totalElements", expression = "java(Long.toString(view.totalElements()))")
    OrderReturnPage toResponse(OrderReturnsPort.ReturnPageView view);

    default String id(UUID value) {
        return value == null ? null : value.toString();
    }

    default String minor(Long value) {
        return value == null ? null : Long.toString(value);
    }

    default String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    default OrderReturnType toType(String value) {
        return value == null ? null : OrderReturnType.valueOf(value);
    }

    default OrderReturnStatus toStatus(String value) {
        return value == null ? null : OrderReturnStatus.valueOf(value);
    }
}
