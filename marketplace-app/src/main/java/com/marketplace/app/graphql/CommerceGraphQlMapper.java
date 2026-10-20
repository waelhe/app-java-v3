package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.CartLine;
import com.marketplace.app.graphql.generated.types.CheckoutDeliveryOption;
import com.marketplace.app.graphql.generated.types.CommerceCheckoutPreview;
import com.marketplace.app.graphql.generated.types.CommerceFulfillmentMethod;
import com.marketplace.app.graphql.generated.types.CommerceOrder;
import com.marketplace.app.graphql.generated.types.CommerceOrderLine;
import com.marketplace.app.graphql.generated.types.CommerceOrderStatus;
import com.marketplace.app.graphql.generated.types.OrderPaymentIntent;
import com.marketplace.app.graphql.generated.types.PaymentIntentClient;
import com.marketplace.orders.spi.OrderCommercePort;
import com.marketplace.payments.spi.PaymentIntentClientPort;
import com.marketplace.shared.api.PaymentIntentDetails;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.UUID;

/** Maps buyer-commerce port projections to the schema-first DGS wire types. */
@Mapper(componentModel = "spring")
public interface CommerceGraphQlMapper {

    @Mapping(target = "id", expression = "java(id(view.id()))")
    @Mapping(target = "productId", expression = "java(id(view.productId()))")
    @Mapping(target = "variantId", expression = "java(id(view.variantId()))")
    CartLine toResponse(OrderCommercePort.CartLineView view);

    @Mapping(target = "sellerId", expression = "java(id(view.sellerId()))")
    @Mapping(target = "deliveryOption", expression = "java(toDeliveryOption(view.deliveryOption()))")
    CommerceCheckoutPreview toResponse(OrderCommercePort.CheckoutPreviewView view);

    @Mapping(target = "etaSeconds", expression = "java(Long.toString(view.etaSeconds()))")
    CheckoutDeliveryOption toDeliveryOption(OrderCommercePort.DeliveryOptionView view);

    @Mapping(target = "id", expression = "java(id(view.id()))")
    @Mapping(target = "status", expression = "java(toOrderStatus(view.status()))")
    @Mapping(target = "fulfillmentMethod",
            expression = "java(toFulfillmentMethod(view.fulfillmentMethod()))")
    @Mapping(target = "deliveryRequestId", expression = "java(id(view.deliveryRequestId()))")
    @Mapping(target = "placedAt", expression = "java(instant(view.placedAt()))")
    @Mapping(target = "confirmedAt", expression = "java(instant(view.confirmedAt()))")
    @Mapping(target = "fulfilledAt", expression = "java(instant(view.fulfilledAt()))")
    @Mapping(target = "cancelledAt", expression = "java(instant(view.cancelledAt()))")
    CommerceOrder toResponse(OrderCommercePort.OrderView view);

    @Mapping(target = "id", expression = "java(id(view.id()))")
    @Mapping(target = "productId", expression = "java(id(view.productId()))")
    @Mapping(target = "variantId", expression = "java(id(view.variantId()))")
    CommerceOrderLine toResponse(OrderCommercePort.OrderLineView view);

    @Mapping(target = "id", expression = "java(id(details.paymentIntentId()))")
    @Mapping(target = "orderId", expression = "java(id(details.orderId()))")
    @Mapping(target = "amountMinor", expression = "java(Long.toString(details.amountCents()))")
    OrderPaymentIntent toResponse(PaymentIntentDetails details);

    @Mapping(target = "id", expression = "java(id(view.id()))")
    @Mapping(target = "bookingId", expression = "java(id(view.bookingId()))")
    @Mapping(target = "createdAt", expression = "java(instant(view.createdAt()))")
    @Mapping(target = "updatedAt", expression = "java(instant(view.updatedAt()))")
    PaymentIntentClient toResponse(PaymentIntentClientPort.PaymentIntentClientView view);

    default String id(UUID value) {
        return value == null ? null : value.toString();
    }

    default String instant(java.time.Instant value) {
        return value == null ? null : value.toString();
    }

    default CommerceOrderStatus toOrderStatus(String value) {
        return value == null ? null : CommerceOrderStatus.valueOf(value);
    }

    default CommerceFulfillmentMethod toFulfillmentMethod(String value) {
        return value == null ? null : CommerceFulfillmentMethod.valueOf(value);
    }
}
