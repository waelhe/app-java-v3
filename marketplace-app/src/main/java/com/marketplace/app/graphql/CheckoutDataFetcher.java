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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 3.23.45 contract {@code Checkout.token} is the checkout's UUID primary
 * key, so {@code token} maps to the native cart UUID without any second
 * store, {@code id}/{@code checkoutId} decode through
 * {@link SaleorGlobalIdCodec}, and the identifiers are MUTUALLY EXCLUSIVE —
 * supplying more than one in a single request is rejected even when the
 * values denote the same cart; no supplied identifier is ever silently
 * ignored — a supplied-but-blank identifier is rejected as a validation
 * failure, never treated as absent (presence and validity are separate
 * concerns). The token arrives already coerced by A0's central {@code UUID}
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

    private static final Logger log =
            LoggerFactory.getLogger(CheckoutDataFetcher.class);

    private static final int MAX_LINE_QUANTITY = 1000;

    /**
     * The wire sentences (the wave-7 review, Minor · Security & Privacy):
     * the classified CODE is the payload's contract anchor; the MESSAGE is
     * a fixed, operator-authored sentence. The owner exceptions' own texts
     * stay internal (logged with the full exception), never reach the
     * client, and no change to them can ever mutate the wire contract.
     */
    private static final String CHECKOUT_NOT_FOUND = "Checkout not found";
    private static final String LINE_NOT_FOUND = "Checkout line not found";
    private static final String LINE_INVALID =
            "The checkout line item is invalid for this operation";
    private static final String LINE_UNAVAILABLE =
            "A product variant is unavailable for purchase";
    private static final String LINE_CONFLICT =
            "The checkout line item is in a conflicting state";

    /** D-UUID-001: the identifiers are mutually exclusive per request. */
    private static final String ONLY_ONE_CHECKOUT_IDENTIFIER =
            "Only one checkout identifier may be supplied (id, checkoutId, or token)";

    private static final String CHECKOUT_ID_REQUIRED = "A checkout ID is required";

    /**
     * D-UUID-001: a SUPPLIED identifier is never silently ignored — a blank
     * value is a validation failure, never an absent argument.
     */
    private static final String BLANK_CHECKOUT_IDENTIFIER =
            "A supplied checkout identifier must not be blank";

    private final CheckoutSpi checkoutSpi;
    private final OrderCommandSpi orderCommandSpi;
    private final CheckoutGraphQlMapper mapper;
    private final SaleorGlobalIdCodec globalIdCodec;
    private final GraphQlProperties properties;

    public CheckoutDataFetcher(CheckoutSpi checkoutSpi,
                               OrderCommandSpi orderCommandSpi,
                               CheckoutGraphQlMapper mapper,
                               SaleorGlobalIdCodec globalIdCodec,
                               GraphQlProperties properties) {
        this.checkoutSpi = checkoutSpi;
        this.orderCommandSpi = orderCommandSpi;
        this.mapper = mapper;
        this.globalIdCodec = globalIdCodec;
        this.properties = properties;
    }

    @DgsQuery(field = "checkout")
    public Map<String, Object> checkout(@InputArgument String id,
                                        DataFetchingEnvironment environment) {
        Authentication authentication = currentAuthentication();
        UUID token = checkoutToken(environment);
        if (id == null && token == null) {
            return null;
        }
        UUID cartId = resolveCheckoutIdentifier(id, null, token);
        final CheckoutView view;
        try {
            view = checkoutSpi.findCheckout(cartId, authentication);
        } catch (ResourceNotFoundException exception) {
            return null;
        }
        return renderedCheckout(view, authentication);
    }

    @DgsMutation(field = "checkoutLinesAdd")
    public Map<String, Object> checkoutLinesAdd(@InputArgument String id,
                                                @InputArgument String checkoutId,
                                                DataFetchingEnvironment environment,
                                                @InputArgument List<Map<String, Object>> lines) {
        // The 2026-11-04 #806 round (Minor · Functional Correctness): the
        // empty list is a client error, not a success-shaped no-op — the
        // same non-empty contract productBulkDelete/productBulkCreate
        // apply (a call that adds nothing answers the classified
        // validation error, never a silent unchanged checkout).
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("checkoutLinesAdd requires a non-empty list of lines");
        }
        // The shard review (Minor · Performance & Scalability, 2026-11-01
        // wave 2): the line lists ride decode loops into the SPI — the
        // G4 unbounded-list bound every bulk root applies (same as
        // orderBulkCancel: the IAE classifies as a validation error).
        properties.requireBoundedList(lines, "checkoutLinesAdd");
        Authentication authentication = currentAuthentication();
        UUID token = checkoutToken(environment);
        final UUID cartId;
        try {
            cartId = requireCheckoutIdentifier(id, checkoutId, token);
        } catch (IllegalArgumentException exception) {
            return payload(null, List.of(error("id", exception.getMessage(), "INVALID")), true, authentication);
        }

        List<CheckoutSpi.LineAdd> requested = new ArrayList<>();
        for (Map<String, Object> line : safe(lines)) {
            Integer quantity = integer(line, "quantity");
            String variantId = string(line, "variantId");
            if (quantity == null || quantity < 1) {
                return payload(null, List.of(error("lines", "A positive quantity is required", "ZERO_QUANTITY")), true, authentication);
            }
            if (quantity > MAX_LINE_QUANTITY) {
                return payload(null, List.of(error("lines", "Quantity exceeds the supported maximum", "INVALID")), true, authentication);
            }
            if (variantId == null || variantId.isBlank()) {
                return payload(null, List.of(error("lines", "A product variant is required", "REQUIRED")), true, authentication);
            }
            try {
                requested.add(new CheckoutSpi.LineAdd(
                        globalIdCodec.decode(variantId, ProductVariantGraphQlMapper.PRODUCT_VARIANT_TYPE),
                        quantity));
            } catch (IllegalArgumentException exception) {
                return payload(null, List.of(error("lines", "Invalid product variant global ID", "INVALID")), true, authentication);
            }
        }

        final CheckoutView updated;
        try {
            updated = checkoutSpi.addLines(cartId, requested, authentication);
        } catch (ResourceNotFoundException exception) {
            log.info("checkoutLinesAdd rejected NOT_FOUND — {}", exception.getMessage(), exception);
            return payload(null, List.of(error("id", CHECKOUT_NOT_FOUND, "NOT_FOUND")), true, authentication);
        } catch (BadRequestException exception) {
            log.info("checkoutLinesAdd rejected INVALID — {}", exception.getMessage(), exception);
            return payload(null, List.of(error("lines", LINE_INVALID, "INVALID")), true, authentication);
        } catch (ConflictException exception) {
            log.info("checkoutLinesAdd rejected PRODUCT_UNAVAILABLE_FOR_PURCHASE — {}", exception.getMessage(), exception);
            return payload(null, List.of(error("lines", LINE_UNAVAILABLE, "PRODUCT_UNAVAILABLE_FOR_PURCHASE")), true, authentication);
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            // ORD-06 (the 2026-11-03 adversarial scan, P3): two concurrent
            // adds of the same variant both pass the merge-path precheck and
            // the second INSERT loses the cart-lines uniqueness race (the
            // V222 partial unique index — the platform's own backstop for
            // the merge model). The raw DataIntegrityViolationException used
            // to escape as an unclassified GraphQL error; the Saleor contract
            // for this root is a PAYLOAD error — CheckoutError under
            // `lines`, the same LINE_CONFLICT sentence the update path
            // answers for a conflicting line state.
            log.info("checkoutLinesAdd lost the concurrent uniqueness race — {}", exception.getMessage(), exception);
            return payload(null, List.of(error("lines", LINE_CONFLICT, "INVALID")), true, authentication);
        }
        return payload(updated, List.of(), true, authentication);
    }

    @DgsMutation(field = "checkoutLinesUpdate")
    public Map<String, Object> checkoutLinesUpdate(@InputArgument String id,
                                                   @InputArgument String checkoutId,
                                                   DataFetchingEnvironment environment,
                                                   @InputArgument List<Map<String, Object>> lines) {
        // The 2026-11-04 #806 round (Minor · Functional Correctness): the
        // same non-empty contract the add root applies.
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("checkoutLinesUpdate requires a non-empty list of lines");
        }
        // The shard review (2026-11-01 wave 2): the same G4 bound the add
        // root applies — the update list decodes per line into the SPI.
        properties.requireBoundedList(lines, "checkoutLinesUpdate");
        Authentication authentication = currentAuthentication();
        UUID token = checkoutToken(environment);
        final UUID cartId;
        try {
            cartId = requireCheckoutIdentifier(id, checkoutId, token);
        } catch (IllegalArgumentException exception) {
            return payload(null, List.of(error("id", exception.getMessage(), "INVALID")), true, authentication);
        }

        List<CheckoutSpi.LineUpdate> requested = new ArrayList<>();
        for (Map<String, Object> line : safe(lines)) {
            Integer quantity = integer(line, "quantity");
            if (quantity == null) {
                return payload(null, List.of(error("lines", "A quantity is required", "REQUIRED")), true, authentication);
            }
            if (quantity == 0) {
                return payload(null, List.of(error("lines", "Use checkoutLinesDelete to remove a line", "ZERO_QUANTITY")), true, authentication);
            }
            if (quantity < 0 || quantity > MAX_LINE_QUANTITY) {
                return payload(null, List.of(error("lines", "Quantity is outside the supported range", "INVALID")), true, authentication);
            }

            String lineId = string(line, "lineId");
            String variantId = string(line, "variantId");
            try {
                requested.add(new CheckoutSpi.LineUpdate(
                        lineId == null ? null : globalIdCodec.decode(lineId, CheckoutGraphQlMapper.CHECKOUT_LINE_TYPE),
                        variantId == null ? null : globalIdCodec.decode(variantId, ProductVariantGraphQlMapper.PRODUCT_VARIANT_TYPE),
                        quantity));
            } catch (IllegalArgumentException exception) {
                return payload(null, List.of(error("lines", "Invalid checkout line or product variant global ID", "INVALID")), true, authentication);
            }
        }

        final CheckoutView updated;
        try {
            updated = checkoutSpi.updateLines(cartId, requested, authentication);
        } catch (ResourceNotFoundException exception) {
            // The same resource classification the delete roots carry (the
            // 12:44:42Z review's fix, completed for this root by the
            // 2026-11-03 adversarial scan): classify by the MISSED RESOURCE
            // — the checkout's absence answers CHECKOUT_NOT_FOUND under
            // `id`; the line's absence answers the pinned Saleor contract
            // (checkout_lines_update.py: get_nodes_or_error(line_ids,
            // "line_id", CheckoutLine) and the stale-line
            // NonExistingCheckoutLines both raise ValidationError under
            // `line_id` with CheckoutErrorCode.GRAPHQL_ERROR — never a
            // checkout-wide NOT_FOUND).
            boolean checkoutMissing = CheckoutGraphQlMapper.CHECKOUT_TYPE
                    .equals(exception.resourceType());
            log.info("checkoutLinesUpdate rejected NOT_FOUND ({}) — {}",
                    exception.resourceType(), exception.getMessage());
            return payload(null, List.of(checkoutMissing
                    ? error("id", CHECKOUT_NOT_FOUND, "NOT_FOUND")
                    : error("lines", LINE_NOT_FOUND, "GRAPHQL_ERROR")), true, authentication);
        } catch (BadRequestException exception) {
            log.info("checkoutLinesUpdate rejected INVALID — {}", exception.getMessage(), exception);
            return payload(null, List.of(error("lines", LINE_INVALID, "INVALID")), true, authentication);
        } catch (ConflictException exception) {
            log.info("checkoutLinesUpdate rejected INVALID (conflict) — {}", exception.getMessage(), exception);
            return payload(null, List.of(error("lines", LINE_CONFLICT, "INVALID")), true, authentication);
        }
        return payload(updated, List.of(), true, authentication);
    }

    @DgsMutation(field = "checkoutLinesDelete")
    public Map<String, Object> checkoutLinesDelete(@InputArgument String id,
                                                   DataFetchingEnvironment environment,
                                                   @InputArgument List<String> linesIds) {
        // The 2026-11-04 #806 round (Minor · Functional Correctness): the
        // same non-empty contract the add/update roots apply.
        if (linesIds == null || linesIds.isEmpty()) {
            throw new IllegalArgumentException("checkoutLinesDelete requires a non-empty list of linesIds");
        }
        // The shard review (2026-11-01 wave 2): the delete list decodes a
        // global ID per entry — the same G4 bound as the add/update roots.
        properties.requireBoundedList(linesIds, "checkoutLinesDelete");
        Authentication authentication = currentAuthentication();
        UUID token = checkoutToken(environment);
        final UUID cartId;
        try {
            cartId = requireCheckoutIdentifier(id, null, token);
        } catch (IllegalArgumentException exception) {
            return payload(null, List.of(error("id", exception.getMessage(), "INVALID")), false, authentication);
        }

        List<UUID> requested = new ArrayList<>();
        for (String lineId : safe(linesIds)) {
            try {
                requested.add(globalIdCodec.decode(lineId, CheckoutGraphQlMapper.CHECKOUT_LINE_TYPE));
            } catch (IllegalArgumentException exception) {
                return payload(null, List.of(error("linesIds", "Invalid checkout line global ID", "INVALID")), false, authentication);
            }
        }

        final CheckoutView updated;
        try {
            updated = checkoutSpi.deleteLines(cartId, requested, authentication);
        } catch (ResourceNotFoundException exception) {
            // The same resource classification the single root carries
            // (the 12:44:42Z review): the checkout's own absence stays
            // CHECKOUT_NOT_FOUND under `id`; a MISSED LINE is the
            // linesIds VALIDATION error the pinned Saleor contract
            // answers (validate_lines: "Provided line_ids aren't part
            // of checkout." under line_id), never a checkout-wide
            // NOT_FOUND.
            boolean checkoutMissing = CheckoutGraphQlMapper.CHECKOUT_TYPE
                    .equals(exception.resourceType());
            log.info("checkoutLinesDelete rejected NOT_FOUND ({}) — {}",
                    exception.resourceType(), exception.getMessage());
            return payload(null, List.of(checkoutMissing
                    ? error("id", CHECKOUT_NOT_FOUND, "NOT_FOUND")
                    : error("linesIds", LINE_INVALID, "INVALID")), false, authentication);
        } catch (BadRequestException exception) {
            log.info("checkoutLinesDelete rejected INVALID — {}", exception.getMessage(), exception);
            return payload(null, List.of(error("linesIds", LINE_INVALID, "INVALID")), false, authentication);
        } catch (ConflictException exception) {
            log.info("checkoutLinesDelete rejected INVALID (conflict) — {}", exception.getMessage(), exception);
            return payload(null, List.of(error("linesIds", LINE_CONFLICT, "INVALID")), false, authentication);
        }
        return payload(updated, List.of(), false, authentication);
    }

    @DgsMutation(field = "checkoutLineDelete")
    public Map<String, Object> checkoutLineDelete(@InputArgument String id,
                                                  @InputArgument String checkoutId,
                                                  DataFetchingEnvironment environment,
                                                  @InputArgument String lineId) {
        Authentication authentication = currentAuthentication();
        UUID token = checkoutToken(environment);
        // The 2026-11-03 #806 review (Minor · Functional Correctness): the
        // single catch reported the IDENTIFIER errors (the id/token
        // conflict, the empty/required identifier) under `lineId` while
        // every sibling root answers them under `id` — the same
        // wrong-field defect fixed for linesUpdate's NOT_FOUND. The two
        // catches are split: the checkout's own identity errors own `id`;
        // the lineId decode failure keeps `lineId` with the constant
        // message shape the sibling paths use (never the codec's raw
        // sentence).
        final UUID cartId;
        try {
            cartId = requireCheckoutIdentifier(id, checkoutId, token);
        } catch (IllegalArgumentException exception) {
            return payload(null, List.of(error("id", exception.getMessage(), "INVALID")), true, authentication);
        }
        final UUID decodedLineId;
        try {
            decodedLineId = globalIdCodec.decode(lineId, CheckoutGraphQlMapper.CHECKOUT_LINE_TYPE);
        } catch (IllegalArgumentException exception) {
            return payload(null, List.of(error("lineId", "Invalid checkout line global ID", "INVALID")), true, authentication);
        }

        final CheckoutView updated;
        try {
            updated = checkoutSpi.deleteLines(cartId, List.of(decodedLineId), authentication);
        } catch (ResourceNotFoundException exception) {
            // The 12:44:42Z #806 review (Minor · Functional Correctness):
            // OrdersService.deleteLines throws the SAME type for both
            // resources — classify by the MISSED RESOURCE (the exception's
            // own resourceType, never its message text): the checkout's
            // absence answers CHECKOUT_NOT_FOUND under `id` (the field
            // every sibling root uses), the line's absence stays
            // LINE_NOT_FOUND under `lineId` — the pinned Saleor contract
            // (get_node_or_error(only_type=CheckoutLine, field="line_id",
            // NOT_FOUND) vs get_checkout's NonExistingCheckout under id).
            boolean checkoutMissing = CheckoutGraphQlMapper.CHECKOUT_TYPE
                    .equals(exception.resourceType());
            log.info("checkoutLineDelete rejected NOT_FOUND ({}) — {}",
                    exception.resourceType(), exception.getMessage());
            return payload(null, List.of(checkoutMissing
                    ? error("id", CHECKOUT_NOT_FOUND, "NOT_FOUND")
                    : error("lineId", LINE_NOT_FOUND, "NOT_FOUND")), true, authentication);
        } catch (BadRequestException | ConflictException exception) {
            log.info("checkoutLineDelete rejected INVALID — {}", exception.getMessage(), exception);
            return payload(null, List.of(error("lineId", LINE_INVALID, "INVALID")), true, authentication);
        }
        return payload(updated, List.of(), true, authentication);
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
            log.info("checkoutDelete rejected NOT_FOUND — {}", exception.getMessage(), exception);
            return deletePayload(List.of(error("id", CHECKOUT_NOT_FOUND, "NOT_FOUND")));
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
     * Saleor 3.23.45 primary key — centrally coerced by the shared UUID
     * scalar, never decoded here).
     *
     * <p>PRESENCE and VALIDITY are separate concerns (the A0 review of
     * 2026-10-23): an argument either carries a value or it does not — a
     * {@code null} argument (absent, or explicit null — exactly how the
     * pinned Saleor resolver helpers treat {@code None}) is NOT supplied,
     * while a SUPPLIED-but-blank value is a validation failure rejected
     * outright: never treated as absent, never allowed to fall through to
     * another alias. More than one supplied identifier is rejected FIRST,
     * even when the values denote the same cart, and a supplied identifier
     * is never silently ignored.</p>
     *
     * @return the native cart UUID, or {@code null} when nothing was supplied
     * @throws IllegalArgumentException when identifiers conflict, a supplied
     *                                  identifier is blank, or a typed global
     *                                  ID is malformed/mistyped
     */
    private UUID resolveCheckoutIdentifier(String id, String checkoutId, UUID token) {
        boolean hasId = id != null;
        boolean hasLegacyCheckoutId = checkoutId != null;
        boolean hasToken = token != null;
        int supplied = (hasId ? 1 : 0) + (hasLegacyCheckoutId ? 1 : 0) + (hasToken ? 1 : 0);
        if (supplied > 1) {
            throw new IllegalArgumentException(ONLY_ONE_CHECKOUT_IDENTIFIER);
        }
        if (hasId) {
            if (id.isBlank()) {
                throw new IllegalArgumentException(BLANK_CHECKOUT_IDENTIFIER);
            }
            return globalIdCodec.decode(id, CheckoutGraphQlMapper.CHECKOUT_TYPE);
        }
        if (hasLegacyCheckoutId) {
            if (checkoutId.isBlank()) {
                throw new IllegalArgumentException(BLANK_CHECKOUT_IDENTIFIER);
            }
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
                                        boolean deprecatedAlias,
                                        Authentication authentication) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkout", view == null ? null : renderedCheckout(view, authentication));
        if (deprecatedAlias) {
            result.put("checkoutErrors", errors);
        }
        result.put("errors", errors);
        return result;
    }

    /**
     * Renders the checkout for its authorized caller and RECONCILES the
     * persisted cart with the catalog for the STALE VARIANT lines only —
     * a line that carries a variant UUID whose variant no longer resolves
     * (the Saleor cascade analog: a deleted variant takes its lines with
     * it) is purged through the SAME owner-authorized use case
     * {@code checkoutLineDelete} rides, so the persisted cart converges
     * with the rendered projection and no stale line id is ever hidden
     * from its owner.
     *
     * <p><b>A product-level line is NEVER purged here (the wave-4 #806
     * review's second-cycle Major · Data Integrity finding).</b> A
     * {@code variantId == null} line is the legacy cart model's
     * legitimate, purchasable state (ADR-0012), not staleness — the
     * mapper reports it for no one. It stays excluded from the Saleor
     * wire render (the pinned reference schema saleor/saleor@3.23.45
     * declares {@code CheckoutLine.variant: ProductVariant!} — never
     * fabricated) and remains fully visible and manageable through the
     * legacy REST cart surface ({@code GET /cart} carries every line
     * with its id), the surface that created it — exactly the orders
     * owner's own projection contract
     * ({@code OrdersService.checkoutView}: "never deleted from the
     * legacy cart … still served by the legacy REST surface").</p>
     *
     * <p>A purge failure against the use case's OWN documented exceptions
     * (the cart gone concurrently, a validation or conflict outcome) is
     * logged at WARN and leaves the read intact — the rendered projection
     * already IS the honest post-cascade answer, and the next read retries
     * the purge (self-healing). Any OTHER failure propagates through the
     * surface's normal error classification.</p>
     */
    private Map<String, Object> renderedCheckout(CheckoutView view,
                                                 Authentication authentication) {
        CheckoutGraphQlMapper.RenderedCheckout rendered = mapper.render(view);
        if (!rendered.staleLineIds().isEmpty()) {
            try {
                checkoutSpi.deleteLines(view.id(), rendered.staleLineIds(), authentication);
            } catch (ResourceNotFoundException | BadRequestException | ConflictException exception) {
                log.warn("Checkout line purge deferred: cart {} lines {} — {}",
                        view.id(), rendered.staleLineIds(), exception.getMessage());
            }
        }
        return rendered.checkout();
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
        // The wave-11 #806 review (Major · Security): AnonymousAuthenticationToken
        // answers isAuthenticated() == true — the type check is the only honest
        // wall, the same guard the account-lifecycle fetcher adopted in wave 3.
        // The checkout family reaches cart-owning and payment-adjacent use
        // cases; an anonymous caller must never pass as an authenticated one
        // (the PreLoginGraphQlGate keeps these roots staff/customer-gated, but
        // the guard must not silently accept an anonymous caller if the
        // gate's allowlist ever widens).
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            throw new AuthenticationCredentialsNotFoundException(
                    "Authentication is required for checkout operations");
        }
        return authentication;
    }
}
