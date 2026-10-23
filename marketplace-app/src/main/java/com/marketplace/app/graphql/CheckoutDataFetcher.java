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
import graphql.schema.DataFetchingEnvironment;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The checkout-family transport adapter over the single bounded
 * {@link CheckoutSpi} use case. Every identifier a request may carry — the
 * modern typed-global-ID {@code id}, the deprecated {@code checkoutId}
 * alias, and the deprecated {@code token} UUID alias — resolves to the same
 * native cart UUID and then flows through the SAME use-case call with the
 * caller's {@link Authentication} unchanged: ownership, ADMIN scope and the
 * authentication wall itself stay exactly where the owning use case and the
 * pre-login security contract keep them.
 *
 * <p>D-UUID-001 (decided by A0 2026-10-23, Option A): in the pinned Saleor
 * 3.23.43 contract {@code Checkout.token} is the checkout's UUID primary
 * key, so {@code token} maps to the native cart UUID without any second
 * store, {@code id}/{@code checkoutId} decode through
 * {@link SaleorGlobalIdCodec}, and the identifiers are MUTUALLY EXCLUSIVE —
 * supplying more than one in a single request is rejected even when the
 * values denote the same cart; no supplied identifier is ever silently
 * ignored. The token arrives already coerced by A0's central {@code UUID}
 * scalar once the one ordered shared-SDL change declares the wire alias;
 * until then it is read from the execution environment as {@code null}
 * (DGS startup-validates annotated arguments against the schema, so the
 * not-yet-declared alias cannot be an {@code @InputArgument} on this
 * branch) and this plumbing is exercised directly by the resolver
 * tests.</p>
 *
 * <p>Security gate (A0 follow-up on upstream issue #18878): a UUID is a
 * transport identifier, NEVER an authentication or authorization grant.
 * The token path grants no anonymous, no wrong-owner and no non-ADMIN
 * access the modern {@code id} path does not already grant — the resolver
 * keeps requiring an authenticated principal before any identifier is
 * resolved, and the owning use case's ownership/ADMIN wall decides
 * access.</p>
 */
@DgsComponent
public class CheckoutDataFetcher {

    private static final int MAX_LINE_QUANTITY = 1000;

    /** D-UUID-001: the identifiers are mutually exclusive per request. */
    private static final String ONLY_ONE_CHECKOUT_IDENTIFIER =
            "Only one checkout identifier may be supplied (id, checkoutId, or token)";

    private static final String CHECKOUT_ID_REQUIRED = "A checkout ID is required";

    private final CheckoutSpi checkoutSpi;
    private final OrderCommandSpi orderCommandSpi;
    private final CheckoutGraphQlMapper mapper;
    private final SaleorGlobalIdCodec globalIdCodec;

    public CheckoutDataFetcher(CheckoutSpi checkoutSpi,
                               OrderCommandSpi orderCommandSpi,
                               CheckoutGraphQlMapper mapper,
                               SaleorGlobalIdCodec globalIdCodec) {
        this.checkoutSpi = checkoutSpi;
        this.orderCommandSpi = orderCommandSpi;
        this.mapper = mapper;
        this.globalIdCodec = globalIdCodec;
    }

    @DgsQuery(field = "checkout")
    public Map<String, Object> checkout(@InputArgument String id,
                                        DataFetchingEnvironment environment) {
        Authentication authentication = currentAuthentication();
        UUID token = checkoutToken(environment);
        if ((id == null || id.isBlank()) && token == null) {
            return null;
        }
        UUID cartId = resolveCheckoutIdentifier(id, null, token);
        final CheckoutView view;
        try {
            view = checkoutSpi.findCheckout(cartId, authentication);
        } catch (ResourceNotFoundException exception) {
            return null;
        }
        return mapper.toGraphQlCheckout(view);
    }

    @DgsMutation(field = "checkoutLinesAdd")
    public Map<String, Object> checkoutLinesAdd(@InputArgument String id,
                                                @InputArgument String checkoutId,
                                                DataFetchingEnvironment environment,
                                                @InputArgument List<Map<String, Object>> lines) {
        Authentication authentication = currentAuthentication();
        UUID token = checkoutToken(environment);
        final UUID cartId;
        try {
            cartId = requireCheckoutIdentifier(id, checkoutId, token);
        } catch (IllegalArgumentException exception) {
            return payload(null, List.of(error("id", exception.getMessage(), "INVALID")), true);
        }

        List<CheckoutSpi.LineAdd> requested = new ArrayList<>();
        for (Map<String, Object> line : safe(lines)) {
            Integer quantity = integer(line, "quantity");
            String variantId = string(line, "variantId");
            if (quantity == null || quantity < 1) {
                return payload(null, List.of(error("lines", "A positive quantity is required", "ZERO_QUANTITY")), true);
            }
            if (quantity > MAX_LINE_QUANTITY) {
                return payload(null, List.of(error("lines", "Quantity exceeds the supported maximum", "INVALID")), true);
            }
            if (variantId == null || variantId.isBlank()) {
                return payload(null, List.of(error("lines", "A product variant is required", "REQUIRED")), true);
            }
            try {
                requested.add(new CheckoutSpi.LineAdd(
                        globalIdCodec.decode(variantId, ProductVariantGraphQlMapper.PRODUCT_VARIANT_TYPE),
                        quantity));
            } catch (IllegalArgumentException exception) {
                return payload(null, List.of(error("lines", "Invalid product variant global ID", "INVALID")), true);
            }
        }

        final CheckoutView updated;
        try {
            updated = checkoutSpi.addLines(cartId, requested, authentication);
        } catch (ResourceNotFoundException exception) {
            return payload(null, List.of(error("id", exception.getMessage(), "NOT_FOUND")), true);
        } catch (BadRequestException exception) {
            return payload(null, List.of(error("lines", exception.getMessage(), "INVALID")), true);
        } catch (ConflictException exception) {
            return payload(null, List.of(error("lines", exception.getMessage(), "PRODUCT_UNAVAILABLE_FOR_PURCHASE")), true);
        }
        return payload(updated, List.of(), true);
    }

    @DgsMutation(field = "checkoutLinesUpdate")
    public Map<String, Object> checkoutLinesUpdate(@InputArgument String id,
                                                   @InputArgument String checkoutId,
                                                   DataFetchingEnvironment environment,
                                                   @InputArgument List<Map<String, Object>> lines) {
        Authentication authentication = currentAuthentication();
        UUID token = checkoutToken(environment);
        final UUID cartId;
        try {
            cartId = requireCheckoutIdentifier(id, checkoutId, token);
        } catch (IllegalArgumentException exception) {
            return payload(null, List.of(error("id", exception.getMessage(), "INVALID")), true);
        }

        List<CheckoutSpi.LineUpdate> requested = new ArrayList<>();
        for (Map<String, Object> line : safe(lines)) {
            Integer quantity = integer(line, "quantity");
            if (quantity == null) {
                return payload(null, List.of(error("lines", "A quantity is required", "REQUIRED")), true);
            }
            if (quantity == 0) {
                return payload(null, List.of(error("lines", "Use checkoutLinesDelete to remove a line", "ZERO_QUANTITY")), true);
            }
            if (quantity < 0 || quantity > MAX_LINE_QUANTITY) {
                return payload(null, List.of(error("lines", "Quantity is outside the supported range", "INVALID")), true);
            }

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

        final CheckoutView updated;
        try {
            updated = checkoutSpi.updateLines(cartId, requested, authentication);
        } catch (ResourceNotFoundException exception) {
            return payload(null, List.of(error("lines", exception.getMessage(), "NOT_FOUND")), true);
        } catch (BadRequestException exception) {
            return payload(null, List.of(error("lines", exception.getMessage(), "INVALID")), true);
        } catch (ConflictException exception) {
            return payload(null, List.of(error("lines", exception.getMessage(), "INVALID")), true);
        }
        return payload(updated, List.of(), true);
    }

    @DgsMutation(field = "checkoutLinesDelete")
    public Map<String, Object> checkoutLinesDelete(@InputArgument String id,
                                                   DataFetchingEnvironment environment,
                                                   @InputArgument List<String> linesIds) {
        Authentication authentication = currentAuthentication();
        UUID token = checkoutToken(environment);
        final UUID cartId;
        try {
            cartId = requireCheckoutIdentifier(id, null, token);
        } catch (IllegalArgumentException exception) {
            return payload(null, List.of(error("id", exception.getMessage(), "INVALID")), false);
        }

        List<UUID> requested = new ArrayList<>();
        for (String lineId : safe(linesIds)) {
            try {
                requested.add(globalIdCodec.decode(lineId, CheckoutGraphQlMapper.CHECKOUT_LINE_TYPE));
            } catch (IllegalArgumentException exception) {
                return payload(null, List.of(error("linesIds", "Invalid checkout line global ID", "INVALID")), false);
            }
        }

        final CheckoutView updated;
        try {
            updated = checkoutSpi.deleteLines(cartId, requested, authentication);
        } catch (ResourceNotFoundException exception) {
            return payload(null, List.of(error("id", exception.getMessage(), "NOT_FOUND")), false);
        } catch (BadRequestException exception) {
            return payload(null, List.of(error("linesIds", exception.getMessage(), "INVALID")), false);
        } catch (ConflictException exception) {
            return payload(null, List.of(error("linesIds", exception.getMessage(), "INVALID")), false);
        }
        return payload(updated, List.of(), false);
    }

    @DgsMutation(field = "checkoutLineDelete")
    public Map<String, Object> checkoutLineDelete(@InputArgument String id,
                                                  @InputArgument String checkoutId,
                                                  DataFetchingEnvironment environment,
                                                  @InputArgument String lineId) {
        Authentication authentication = currentAuthentication();
        UUID token = checkoutToken(environment);
        final UUID cartId;
        final UUID decodedLineId;
        try {
            cartId = requireCheckoutIdentifier(id, checkoutId, token);
            decodedLineId = globalIdCodec.decode(lineId, CheckoutGraphQlMapper.CHECKOUT_LINE_TYPE);
        } catch (IllegalArgumentException exception) {
            return payload(null, List.of(error("lineId", exception.getMessage(), "INVALID")), true);
        }

        final CheckoutView updated;
        try {
            updated = checkoutSpi.deleteLines(cartId, List.of(decodedLineId), authentication);
        } catch (ResourceNotFoundException exception) {
            return payload(null, List.of(error("lineId", exception.getMessage(), "NOT_FOUND")), true);
        } catch (BadRequestException | ConflictException exception) {
            return payload(null, List.of(error("lineId", exception.getMessage(), "INVALID")), true);
        }
        return payload(updated, List.of(), true);
    }

    @DgsMutation(field = "checkoutDelete")
    public Map<String, Object> checkoutDelete(@InputArgument String id) {
        orderCommandSpi.requireAdmin();
        final UUID cartId;
        try {
            cartId = globalIdCodec.decode(id, CheckoutGraphQlMapper.CHECKOUT_TYPE);
        } catch (IllegalArgumentException exception) {
            return deletePayload(List.of(error("id", "Invalid checkout global ID", "INVALID")));
        }
        try {
            checkoutSpi.deleteAsAdmin(cartId);
            return deletePayload(List.of());
        } catch (ResourceNotFoundException exception) {
            return deletePayload(List.of(error("id", exception.getMessage(), "NOT_FOUND")));
        }
    }

    /**
     * Reads the deprecated {@code token} alias from the execution
     * environment. DGS validates every {@code @InputArgument} against the
     * schema at startup, so an argument that is deliberately NOT declared
     * in this branch's SDL yet (A0's one ordered shared-SDL change declares
     * {@code token: UUID} together with the central {@code scalar UUID} at
     * assembly) must be read from the environment instead: the call answers
     * {@code null} while the alias is undeclared and the centrally coerced
     * native checkout UUID the moment it lands — zero resolver changes at
     * assembly.
     */
    private static UUID checkoutToken(DataFetchingEnvironment environment) {
        return environment.getArgument("token");
    }

    /**
     * Resolves the ONE checkout identifier a request may carry to the native
     * cart UUID (D-UUID-001 Option A): {@code id} and the deprecated
     * {@code checkoutId} decode through the typed global-ID codec, the
     * deprecated {@code token} IS the native checkout UUID (the pinned
     * Saleor 3.23.43 primary key — centrally coerced by the shared UUID
     * scalar, never decoded here). More than one supplied identifier is
     * rejected even when the values denote the same cart; a supplied
     * identifier is never silently ignored.
     *
     * @return the native cart UUID, or {@code null} when nothing was supplied
     * @throws IllegalArgumentException when identifiers conflict or a typed
     *                                  global ID is malformed/mistyped
     */
    private UUID resolveCheckoutIdentifier(String id, String checkoutId, UUID token) {
        boolean hasId = id != null && !id.isBlank();
        boolean hasLegacyCheckoutId = checkoutId != null && !checkoutId.isBlank();
        boolean hasToken = token != null;
        int supplied = (hasId ? 1 : 0) + (hasLegacyCheckoutId ? 1 : 0) + (hasToken ? 1 : 0);
        if (supplied > 1) {
            throw new IllegalArgumentException(ONLY_ONE_CHECKOUT_IDENTIFIER);
        }
        if (hasId) {
            return globalIdCodec.decode(id, CheckoutGraphQlMapper.CHECKOUT_TYPE);
        }
        if (hasLegacyCheckoutId) {
            return globalIdCodec.decode(checkoutId, CheckoutGraphQlMapper.CHECKOUT_TYPE);
        }
        return token;
    }

    /** The mutation form of {@link #resolveCheckoutIdentifier}: an identifier is required. */
    private UUID requireCheckoutIdentifier(String id, String checkoutId, UUID token) {
        UUID cartId = resolveCheckoutIdentifier(id, checkoutId, token);
        if (cartId == null) {
            throw new IllegalArgumentException(CHECKOUT_ID_REQUIRED);
        }
        return cartId;
    }

    private Map<String, Object> payload(CheckoutView view,
                                        List<Map<String, Object>> errors,
                                        boolean deprecatedAlias) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkout", view == null ? null : mapper.toGraphQlCheckout(view));
        if (deprecatedAlias) {
            result.put("checkoutErrors", errors);
        }
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
        result.put("field", field);
        result.put("message", message);
        result.put("code", code);
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

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException(
                    "Authentication is required for checkout operations");
        }
        return authentication;
    }
}
