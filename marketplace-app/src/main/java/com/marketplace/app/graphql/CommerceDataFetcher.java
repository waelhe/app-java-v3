package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.AddToCartInput;
import com.marketplace.app.graphql.generated.types.CartLine;
import com.marketplace.app.graphql.generated.types.CheckoutFulfillmentInput;
import com.marketplace.app.graphql.generated.types.CheckoutInput;
import com.marketplace.app.graphql.generated.types.CommerceCheckoutPreview;
import com.marketplace.app.graphql.generated.types.CommerceOrder;
import com.marketplace.app.graphql.generated.types.OrderPaymentIntent;
import com.marketplace.app.graphql.generated.types.PaymentIntentClient;
import com.marketplace.orders.spi.OrderCommercePort;
import com.marketplace.payments.spi.PaymentIntentClientPort;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

/**
 * Schema-first GraphQL transport for the buyer's cart/checkout/order/payment
 * journey. All writes delegate to their owning modules through shared ports;
 * this layer never computes prices, reserves stock, or settles money.
 */
@DgsComponent
public class CommerceDataFetcher {

    private final OrderCommercePort commerce;
    private final PaymentIntentClientPort payments;
    private final CommerceGraphQlMapper mapper;
    private final GraphQlProperties properties;

    public CommerceDataFetcher(OrderCommercePort commerce,
                               PaymentIntentClientPort payments,
                               CommerceGraphQlMapper mapper,
                               GraphQlProperties properties) {
        this.commerce = commerce;
        this.payments = payments;
        this.mapper = mapper;
        this.properties = properties;
    }

    @DgsQuery(field = DgsConstants.QUERY.MyCart)
    @RateLimiter(name = "graphqlStoreProductQuery")
    public List<CartLine> myCart() {
        return commerce.getCart(currentAuthentication()).stream().map(mapper::toResponse).toList();
    }

    /** Bounded buyer order history; not a copy of every order in the database. */
    @DgsQuery(field = DgsConstants.QUERY.MyOrders)
    @RateLimiter(name = "graphqlStoreProductQuery")
    public List<CommerceOrder> myOrders(@InputArgument Integer first) {
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) return List.of();
        int bounded = Math.min(requested, Math.min(50, properties.pagination().maxPageSize()));
        return commerce.listMyOrders(bounded, currentAuthentication()).stream()
                .map(mapper::toResponse).toList();
    }

    @DgsQuery(field = DgsConstants.QUERY.CommerceOrder)
    @RateLimiter(name = "graphqlStoreProductQuery")
    public CommerceOrder commerceOrder(@InputArgument UUID id) {
        return mapper.toResponse(commerce.getOrder(id, currentAuthentication()));
    }

    @DgsQuery(field = DgsConstants.QUERY.PaymentIntent)
    @RateLimiter(name = "graphqlStoreProductQuery")
    public PaymentIntentClient paymentIntent(@InputArgument UUID id) {
        return mapper.toResponse(payments.get(id, currentAuthentication()));
    }

    @DgsMutation(field = DgsConstants.MUTATION.AddToCart)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public CartLine addToCart(@InputArgument AddToCartInput input) {
        if (input == null || input.getProductId() == null || input.getProductId().isBlank()
                || input.getQuantity() < 1 || input.getQuantity() > 1000) {
            throw new IllegalArgumentException("productId is required and quantity must be between 1 and 1000");
        }
        return mapper.toResponse(commerce.addToCart(parseId(input.getProductId(), "productId"),
                parseOptionalId(input.getVariantId(), "variantId"),
                input.getQuantity(), currentAuthentication()));
    }

    @DgsMutation(field = DgsConstants.MUTATION.RemoveFromCart)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public Boolean removeFromCart(@InputArgument UUID itemId) {
        if (itemId == null) throw new IllegalArgumentException("itemId is required");
        commerce.removeFromCart(itemId, currentAuthentication());
        return Boolean.TRUE;
    }

    @DgsQuery(field = DgsConstants.QUERY.CheckoutPreview)
    @RateLimiter(name = "graphqlStoreProductQuery")
    public List<CommerceCheckoutPreview> checkoutPreview(@InputArgument CheckoutInput input) {
        return commerce.previewCheckout(toCommand(input), currentAuthentication()).stream()
                .map(mapper::toResponse).toList();
    }

    @DgsMutation(field = DgsConstants.MUTATION.PlaceOrder)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public CommerceOrder placeOrder(@InputArgument CheckoutInput input) {
        return mapper.toResponse(commerce.placeOrder(toCommand(input), currentAuthentication()));
    }

    @DgsMutation(field = DgsConstants.MUTATION.CancelCommerceOrder)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public CommerceOrder cancelCommerceOrder(@InputArgument UUID id, @InputArgument String reason) {
        if (id == null || reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("id and a reason between 1 and 500 characters are required");
        }
        return mapper.toResponse(commerce.cancelOrder(id, reason, currentAuthentication()));
    }

    @DgsMutation(field = DgsConstants.MUTATION.RequestOrderPaymentIntent)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public OrderPaymentIntent requestOrderPaymentIntent(@InputArgument UUID orderId) {
        if (orderId == null) throw new IllegalArgumentException("orderId is required");
        return mapper.toResponse(commerce.requestOrderPaymentIntent(orderId, currentAuthentication()));
    }

    @DgsMutation(field = DgsConstants.MUTATION.ProcessPaymentIntent)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public PaymentIntentClient processPaymentIntent(@InputArgument UUID id) {
        if (id == null) throw new IllegalArgumentException("id is required");
        return mapper.toResponse(payments.process(id, currentAuthentication()));
    }

    @DgsMutation(field = DgsConstants.MUTATION.CancelPaymentIntent)
    @RateLimiter(name = "graphqlStoreProductMutation")
    public PaymentIntentClient cancelPaymentIntent(@InputArgument UUID id) {
        if (id == null) throw new IllegalArgumentException("id is required");
        return mapper.toResponse(payments.cancel(id, currentAuthentication()));
    }

    private static UUID parseId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID", exception);
        }
    }

    private static UUID parseOptionalId(String value, String field) {
        return value == null || value.isBlank() ? null : parseId(value, field);
    }

    private static OrderCommercePort.CheckoutCommand toCommand(CheckoutInput input) {
        if (input == null) return null;
        CheckoutFulfillmentInput fulfillment = input.getFulfillment();
        return new OrderCommercePort.CheckoutCommand(input.getVoucherCode(),
                fulfillment == null || fulfillment.getMethod() == null ? null : fulfillment.getMethod().name(),
                fulfillment == null ? null : fulfillment.getDestinationAddress(),
                fulfillment == null ? null : fulfillment.getDestinationLatitude(),
                fulfillment == null ? null : fulfillment.getDestinationLongitude());
    }

    private static Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("Authenticated session is required");
        }
        return authentication;
    }
}
