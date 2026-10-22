package com.marketplace.app.graphql;

import com.marketplace.orders.spi.CheckoutSpi;
import com.marketplace.orders.spi.CheckoutView;
import com.marketplace.orders.spi.OrderCommandSpi;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@DgsComponent
public class CheckoutDataFetcher {
    private static final int MAX_LINE_QUANTITY = 1000;
    private final CheckoutSpi checkoutSpi;
    private final OrderCommandSpi orderCommandSpi;
    private final CheckoutGraphQlMapper mapper;
    private final SaleorGlobalIdCodec globalIdCodec;

    public CheckoutDataFetcher(CheckoutSpi checkoutSpi, OrderCommandSpi orderCommandSpi,
                               CheckoutGraphQlMapper mapper, SaleorGlobalIdCodec globalIdCodec) {
        this.checkoutSpi = checkoutSpi;
        this.orderCommandSpi = orderCommandSpi;
        this.mapper = mapper;
        this.globalIdCodec = globalIdCodec;
    }

    @DgsQuery(field = "checkout")
    public Map<String, Object> checkout(@InputArgument String id) {
        Authentication authentication = currentAuthentication();
        if (id == null || id.isBlank()) return null;
        UUID cartId = globalIdCodec.decode(id, CheckoutGraphQlMapper.CHECKOUT_TYPE);
        try {
            return mapper.toGraphQlCheckout(checkoutSpi.findCheckout(cartId, authentication));
        } catch (ResourceNotFoundException exception) {
            return null;
        }
    }

    @DgsMutation(field = "checkoutLinesAdd")
    public Map<String, Object> checkoutLinesAdd(@InputArgument String id, @InputArgument String checkoutId,
                                                 @InputArgument List<Map<String, Object>> lines) {
        Authentication authentication = currentAuthentication();
        UUID cartId;
        try { cartId = resolveCheckoutId(id, checkoutId); }
        catch (IllegalArgumentException exception) {
            return payload(null, List.of(error("id", exception.getMessage(), "INVALID")), true);
        }
        List<CheckoutSpi.LineAdd> requested = new ArrayList<>();
        for (Map<String, Object> line : safe(lines)) {
            Integer quantity = integer(line, "quantity");
            String variantId = string(line, "variantId");
            if (quantity == null || quantity < 1)
                return payload(null, List.of(error("lines", "A positive quantity is required", "ZERO_QUANTITY")), true);
            if (quantity > MAX_LINE_QUANTITY)
                return payload(null, List.of(error("lines", "Quantity exceeds the supported maximum", "INVALID")), true);
            if (variantId == null || variantId.isBlank())
                return payload(null, List.of(error("lines", "A product variant is required", "REQUIRED")), true);
            try {
                requested.add(new CheckoutSpi.LineAdd(
                    globalIdCodec.decode(variantId, ProductVariantGraphQlMapper.PRODUCT_VARIANT_TYPE), quantity));
            } catch (IllegalArgumentException exception) {
                return payload(null, List.of(error("lines", "Invalid product variant global ID", "INVALID")), true);
            }
        }
        try { return payload(checkoutSpi.addLines(cartId, requested, authentication), List.of(), true); }
        catch (ResourceNotFoundException exception) {
            return payload(null, List.of(error("id", exception.getMessage(), "NOT_FOUND")), true);
        } catch (BadRequestException exception) {
            return payload(null, List.of(error("lines", exception.getMessage(), "INVALID")), true);
        } catch (ConflictException exception) {
            return payload(null, List.of(error("lines", exception.getMessage(), "PRODUCT_UNAVAILABLE_FOR_PURCHASE")), true);
        }
    }

    @DgsMutation(field = "checkoutLinesUpdate")
    public Map<String, Object> checkoutLinesUpdate(@InputArgument String id, @InputArgument String checkoutId,
                                                    @InputArgument List<Map<String, Object>> lines) {
        Authentication authentication = currentAuthentication();
        UUID cartId;
        try { cartId = resolveCheckoutId(id, checkoutId); }
        catch (IllegalArgumentException exception) {
            return payload(null, List.of(error("id", exception.getMessage(), "INVALID")), true);
        }
        List<CheckoutSpi.LineUpdate> requested = new ArrayList<>();
        for (Map<String, Object> line : safe(lines)) {
            Integer quantity = integer(line, "quantity");
            if (quantity == null)
                return payload(null, List.of(error("lines", "A quantity is required", "REQUIRED")), true);
            if (quantity == 0)
                return payload(null, List.of(error("lines", "Use checkoutLinesDelete to remove a line", "ZERO_QUANTITY")), true);
            if (quantity < 0 || quantity > MAX_LINE_QUANTITY)
                return payload(null, List.of(error("lines", "Quantity is outside the supported range", "INVALID")), true);
            String lineId = string(line, "lineId");
            String variantId = string(line, "variantId");
            try {
                requested.add(new CheckoutSpi.LineUpdate(
                    lineId == null ? null : globalIdCodec.decode(lineId, CheckoutGraphQlMapper.CHECKOUT_LINE_TYPE),
                    variantId == null ? null : globalIdCodec.decode(variantId, ProductVariantGraphQlMapper.PRODUCT_VARIANT_TYPE),
                    quantity));
            } catch (IllegalArgumentException exception) {
                return payload(null, List.of(error("lines", "Invalid checkout line or product variant global ID", "INVALID")), true);
            }
        }
        try { return payload(checkoutSpi.updateLines(cartId, requested, authentication), List.of(), true); }
        catch (ResourceNotFoundException exception) {
            return payload(null, List.of(error("lines", exception.getMessage(), "NOT_FOUND")), true);
        } catch (BadRequestException exception) {
            return payload(null, List.of(error("lines", exception.getMessage(), "INVALID")), true);
        } catch (ConflictException exception) {
            return payload(null, List.of(error("lines", exception.getMessage(), "INVALID")), true);
        }
    }

    @DgsMutation(field = "checkoutLinesDelete")
    public Map<String, Object> checkoutLinesDelete(@InputArgument String id, @InputArgument List<String> linesIds) {
        Authentication authentication = currentAuthentication();
        UUID cartId;
        try { cartId = globalIdCodec.decode(id, CheckoutGraphQlMapper.CHECKOUT_TYPE); }
        catch (IllegalArgumentException exception) {
            return payload(null, List.of(error("id", "Invalid checkout global ID", "INVALID")), false);
        }
        List<UUID> requested = new ArrayList<>();
        for (String lineId : safe(linesIds)) {
            try { requested.add(globalIdCodec.decode(lineId, CheckoutGraphQlMapper.CHECKOUT_LINE_TYPE)); }
            catch (IllegalArgumentException exception) {
                return payload(null, List.of(error("linesIds", "Invalid checkout line global ID", "INVALID")), false);
            }
        }
        try { return payload(checkoutSpi.deleteLines(cartId, requested, authentication), List.of(), false); }
        catch (ResourceNotFoundException exception) {
            return payload(null, List.of(error("id", exception.getMessage(), "NOT_FOUND")), false);
        } catch (BadRequestException exception) {
            return payload(null, List.of(error("linesIds", exception.getMessage(), "INVALID")), false);
        } catch (ConflictException exception) {
            return payload(null, List.of(error("linesIds", exception.getMessage(), "INVALID")), false);
        }
    }

    @DgsMutation(field = "checkoutLineDelete")
    public Map<String, Object> checkoutLineDelete(@InputArgument String id, @InputArgument String checkoutId,
                                                   @InputArgument String lineId) {
        Authentication authentication = currentAuthentication();
        UUID cartId;
        UUID decodedLineId;
        try {
            cartId = resolveCheckoutId(id, checkoutId);
            decodedLineId = globalIdCodec.decode(lineId, CheckoutGraphQlMapper.CHECKOUT_LINE_TYPE);
        } catch (IllegalArgumentException exception) {
            return payload(null, List.of(error("lineId", "Invalid checkout or line global ID", "INVALID")), true);
        }
        try { return payload(checkoutSpi.deleteLines(cartId, List.of(decodedLineId), authentication), List.of(), true); }
        catch (ResourceNotFoundException exception) {
            return payload(null, List.of(error("lineId", exception.getMessage(), "NOT_FOUND")), true);
        } catch (BadRequestException | ConflictException exception) {
            return payload(null, List.of(error("lineId", exception.getMessage(), "INVALID")), true);
        }
    }

    @DgsMutation(field = "checkoutDelete")
    public Map<String, Object> checkoutDelete(@InputArgument String id) {
        orderCommandSpi.requireAdmin();
        UUID cartId;
        try { cartId = globalIdCodec.decode(id, CheckoutGraphQlMapper.CHECKOUT_TYPE); }
        catch (IllegalArgumentException exception) {
            return deletePayload(List.of(error("id", "Invalid checkout global ID", "INVALID")));
        }
        try { checkoutSpi.deleteAsAdmin(cartId); return deletePayload(List.of()); }
        catch (ResourceNotFoundException exception) {
            return deletePayload(List.of(error("id", exception.getMessage(), "NOT_FOUND")));
        }
    }

    private UUID resolveCheckoutId(String id, String checkoutId) {
        UUID primary = id == null || id.isBlank() ? null : globalIdCodec.decode(id, CheckoutGraphQlMapper.CHECKOUT_TYPE);
        UUID deprecated = checkoutId == null || checkoutId.isBlank() ? null : globalIdCodec.decode(checkoutId, CheckoutGraphQlMapper.CHECKOUT_TYPE);
        if (primary == null && deprecated == null) throw new IllegalArgumentException("A checkout ID is required");
        if (primary != null && deprecated != null && !primary.equals(deprecated))
            throw new IllegalArgumentException("Conflicting checkout IDs");
        return primary != null ? primary : deprecated;
    }

    private Map<String, Object> payload(CheckoutView view, List<Map<String, Object>> errors, boolean deprecatedAlias) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkout", view == null ? null : mapper.toGraphQlCheckout(view));
        if (deprecatedAlias) result.put("checkoutErrors", errors);
        result.put("errors", errors);
        return result;
    }

    private static Map<String, Object> deletePayload(List<Map<String, Object>> errors) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("errors", errors);
        return result;
    }

    private static Map<String, Object> error(String field, String message, String code) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("field", field); result.put("message", message); result.put("code", code);
        return result;
    }
    private static String string(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? null : value.toString();
    }
    private static Integer integer(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }
    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
    private Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated())
            throw new AuthenticationCredentialsNotFoundException("Authentication is required for checkout operations");
        return authentication;
    }
}
