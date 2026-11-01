package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.Attribute;
import com.marketplace.app.graphql.generated.types.AttributeCountableConnection;
import com.marketplace.app.graphql.generated.types.AttributeCountableEdge;
import com.marketplace.app.graphql.generated.types.AttributeFilterInput;
import com.marketplace.app.graphql.generated.types.AttributeSortField;
import com.marketplace.app.graphql.generated.types.AttributeSortingInput;
import com.marketplace.app.graphql.generated.types.AttributeWhereInput;
import com.marketplace.app.graphql.generated.types.OrderDirection;
import com.marketplace.app.graphql.generated.types.PageInfo;
import com.marketplace.catalog.spi.AttributePage;
import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.AttributeSort;
import com.marketplace.catalog.spi.AttributeView;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2): the GraphQL adapter for the
 * Saleor Attributes-domain read roots — {@code attribute} and
 * {@code attributes} — over the catalog owner's use cases through the
 * declared {@code AttributeReadSpi} seam (the declared continuation of
 * the campaign-b2-a2 product-type slice). This class owns ONLY transport
 * mapping: global-ID decode/encode, argument validation, cursor/position
 * mapping and the connection envelope. Authorization lives in the owning
 * use case (the ADMIN-enforced {@code AttributeReadService} — the
 * platform's server-side mapping of Saleor's staff
 * MANAGE_PRODUCTS-class requirement), never in a UI route guard and never
 * duplicated here.
 *
 * <p><b>Bounded first slice — the honest boundaries, each tested:</b>
 * {@code attribute} executes its {@code id} argument through the typed
 * global-ID codec and its {@code slug} argument through the owner's
 * code lookup (the domain's own {@code findByCode} seam — upstream's
 * id-first precedence between the two EXECUTABLE keys);
 * {@code attributes} executes forward-only keyset paging
 * ({@code first}/{@code after}) in the two sorts with owning facts (NAME
 * and SLUG — each deterministic with the {@code id} tiebreaker) and
 * answers explicit classified errors for the declared wire arguments
 * whose owning capability does not exist yet ({@code filter},
 * {@code where}, {@code search}, {@code channel}, the unowned
 * {@code sortBy} fields, and {@code before}/{@code last}, plus the
 * {@code attribute} root's {@code externalReference} and its keyless
 * call shape) — recorded divergences, never silent no-ops and never fake
 * successes.</p>
 */
@DgsComponent
public class AttributeDataFetcher {

    /**
     * The default attribute sort when the caller sends none — the
     * rendered display-name ordering ascending (the product-type slice's
     * own default), deterministic with the {@code id} tiebreaker.
     */
    private static final AttributeSort DEFAULT_SORT =
            AttributeSort.of(AttributeSort.Field.NAME, true);

    private final AttributeReadSpi attributeReadSpi;
    private final AttributeGraphQlMapper mapper;
    private final GraphQlProperties properties;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;
    private final SaleorGlobalIdCodec globalIdCodec;

    public AttributeDataFetcher(AttributeReadSpi attributeReadSpi,
                                AttributeGraphQlMapper mapper,
                                GraphQlProperties properties,
                                EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                                SaleorGlobalIdCodec globalIdCodec) {
        this.attributeReadSpi = attributeReadSpi;
        this.mapper = mapper;
        this.properties = properties;
        this.cursorStrategy = cursorStrategy;
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Saleor {@code attributes(...): AttributeCountableConnection} — the
     * typed attribute reference collection as a forward-only keyset scroll
     * (the staffUsers/permissionGroups/productTypes keyset precedent:
     * cursors identify a row's sort key, never an absolute offset) inside
     * Saleor's exact countable-connection envelope, including
     * {@code totalCount}.
     *
     * <p>The declared-but-unexecuted arguments answer explicit classified
     * validation errors (see the class documentation): the boundary is
     * tested, recorded in the divergence registry, and never a silent
     * no-op — {@code filter} and {@code where} (no owning query
     * capability), {@code search} (no owning search capability),
     * {@code channel} (attributes are channel-independent reference data
     * on this platform), the unowned sort fields, and the backward
     * pagination. An empty or non-positive {@code first} answers the empty
     * page (the staffUsers field's own house contract), so
     * {@code totalCount} answers the honest not-computed null — never a
     * fabricated zero total.</p>
     */
    @DgsQuery(field = DgsConstants.QUERY.Attributes)
    @RateLimiter(name = "graphqlCatalogAttributeQuery")
    public AttributeCountableConnection attributes(
            @InputArgument Integer first,
            @InputArgument String after,
            @InputArgument String before,
            @InputArgument Integer last,
            @InputArgument AttributeSortingInput sortBy,
            @InputArgument AttributeFilterInput filter,
            @InputArgument AttributeWhereInput where,
            @InputArgument String search,
            @InputArgument String channel) {
        if (filter != null) {
            throw new IllegalArgumentException(
                    "attributes filter is not implemented on this platform yet");
        }
        if (where != null) {
            throw new IllegalArgumentException(
                    "attributes where filter is not implemented on this platform yet");
        }
        if (search != null) {
            throw new IllegalArgumentException(
                    "attributes search is not implemented on this platform yet");
        }
        if (channel != null) {
            throw new IllegalArgumentException(
                    "attributes channel filtering is not implemented on this platform yet");
        }
        if (before != null || last != null) {
            throw new IllegalArgumentException(
                    "attributes supports forward-only pagination (first/after) on this platform");
        }

        AttributeSort sort = toOwnerSort(sortBy);
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) {
            return AttributeCountableConnection.newBuilder()
                    .edges(List.of())
                    .pageInfo(PageInfo.newBuilder()
                            .hasNextPage(false)
                            .hasPreviousPage(false)
                            .build())
                    .build();
        }
        int pageSize = Math.min(requested, properties.pagination().maxPageSize());
        KeysetScrollPosition position = keysetPosition(after, sort.property());
        AttributePage page = attributeReadSpi.scrollAttributes(
                currentAuthentication(), sort, position, Limit.of(pageSize));
        return toConnection(page, position);
    }

    /**
     * Saleor {@code attribute(id: ID, slug: String, externalReference:
     * String): Attribute} — the staff lookup, ADMIN-enforced in the catalog
     * use case. The {@code id} argument executes through the typed
     * global-ID codec: an unknown but well-formed lookup answers null (the
     * safe absence); a malformed or mistyped global ID answers the codec's
     * classified validation error — never a silent null and never a raw
     * UUID fallback. The {@code slug} argument executes through the
     * owner's code lookup (the domain's own {@code findByCode} seam):
     * an unknown but well-formed slug likewise answers null. When both
     * executable keys are sent, {@code id} wins — upstream's own
     * documented precedence. The {@code externalReference} argument has no
     * owning capability on this platform and answers the explicit
     * classified error whenever it is sent; a call with no key at all
     * answers the classified validation error (a keyless lookup is a
     * client error here, never a fabricated absence — the recorded
     * divergence from upstream's null answer).
     */
    @DgsQuery(field = DgsConstants.QUERY.Attribute)
    @RateLimiter(name = "graphqlCatalogAttributeQuery")
    public Attribute attribute(@InputArgument String id,
                               @InputArgument String slug,
                               @InputArgument String externalReference) {
        Authentication authentication = currentAuthentication();
        if (externalReference != null) {
            throw new IllegalArgumentException(
                    "attribute externalReference lookup is not implemented on this platform yet");
        }
        if (id == null && slug == null) {
            throw new IllegalArgumentException(
                    "attribute requires one of id or slug on this platform");
        }
        if (id != null) {
            UUID nativeId = globalIdCodec.decode(id, AttributeGraphQlMapper.ATTRIBUTE_TYPE);
            return attributeReadSpi.findAttributeById(nativeId, authentication)
                    .map(mapper::toGraphQlAttribute)
                    .orElse(null);
        }
        return attributeReadSpi.findAttributeBySlug(slug, authentication)
                .map(mapper::toGraphQlAttribute)
                .orElse(null);
    }

    /**
     * The sort mapping — Saleor's {@code AttributeSortingInput} onto the
     * owner's executed keyset sort. NAME (the rendered display name) and
     * SLUG (the row's immutable code) have owning facts and execute;
     * VALUE_REQUIRED, IS_VARIANT_ONLY, VISIBLE_IN_STOREFRONT and the four
     * deprecated no-effect flags have no owning column on this platform
     * and answer the explicit not-supported error — the staffUsers
     * boundary's own classified-error contract, never a silent fallback to
     * another sort.
     */
    private static AttributeSort toOwnerSort(AttributeSortingInput sortBy) {
        if (sortBy == null) {
            return DEFAULT_SORT;
        }
        boolean ascending = sortBy.getDirection() == null || OrderDirection.ASC.equals(sortBy.getDirection());
        AttributeSortField field = sortBy.getField() == null
                ? AttributeSortField.NAME
                : sortBy.getField();
        return switch (field) {
            case NAME -> AttributeSort.of(AttributeSort.Field.NAME, ascending);
            case SLUG -> AttributeSort.of(AttributeSort.Field.SLUG, ascending);
            case VALUE_REQUIRED, IS_VARIANT_ONLY, VISIBLE_IN_STOREFRONT,
                 FILTERABLE_IN_STOREFRONT, FILTERABLE_IN_DASHBOARD,
                 STOREFRONT_SEARCH_POSITION, AVAILABLE_IN_GRID -> throw new IllegalArgumentException(
                    "attributes sort field " + field + " is not implemented on this platform yet");
        };
    }

    /**
     * The ONE shared cursor gate (the house KeysetCursorGate): the key set
     * is the executed sort's own (property, id) pair with the property's
     * declared value type, so a client-crafted cursor with well-named but
     * unparseable values — or a cursor emitted by the OTHER sort — answers
     * the same VAL-001 taxonomy.
     */
    private KeysetScrollPosition keysetPosition(String after, String sortProperty) {
        return KeysetCursorGate.forwardKeyset(cursorStrategy, after, sortProperty,
                KeysetCursorGate::textValue, "attributes with the same sortBy");
    }

    /**
     * The connection envelope — the declared (not runtime-generated)
     * Saleor shape, built from the Window's own per-element scroll
     * positions (the exact positions the framework's connection adaptation
     * emits for its generated connections — one cursor strategy
     * (toCursor/fromCursor), one cursor semantics across the whole
     * surface). {@code hasPreviousPage} is the forward-only honest fact: a
     * scroll that started after a known element has elements before it.
     * {@code totalCount} is the collection count (GraphQL Int's signed
     * 32-bit ceiling documented, hence the clamp).
     */
    private AttributeCountableConnection toConnection(AttributePage page, KeysetScrollPosition position) {
        Window<AttributeView> window = page.window();
        List<AttributeView> content = window.getContent();
        List<AttributeCountableEdge> edges = new ArrayList<>(content.size());
        for (int i = 0; i < content.size(); i++) {
            edges.add(AttributeCountableEdge.newBuilder()
                    .node(mapper.toGraphQlAttribute(content.get(i)))
                    .cursor(cursorStrategy.toCursor(window.positionAt(i)))
                    .build());
        }
        String startCursor = edges.isEmpty() ? null : edges.get(0).getCursor();
        String endCursor = edges.isEmpty() ? null : edges.get(edges.size() - 1).getCursor();
        PageInfo pageInfo = PageInfo.newBuilder()
                .hasNextPage(window.hasNext())
                .hasPreviousPage(!position.isInitial())
                .startCursor(startCursor)
                .endCursor(endCursor)
                .build();
        return AttributeCountableConnection.newBuilder()
                .edges(edges)
                .pageInfo(pageInfo)
                .totalCount((int) Math.min(page.total(), Integer.MAX_VALUE))
                .build();
    }

    /**
     * The authenticated caller — the house pattern (the /graphql chain
     * guarantees authentication; the guard keeps the fetcher honest if a
     * future public journey reopens the route policy).
     */
    private Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException(
                    "Authentication is required for attribute administration");
        }
        return authentication;
    }
}
