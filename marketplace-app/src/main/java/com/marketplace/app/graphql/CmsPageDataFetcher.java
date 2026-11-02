package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.DgsConstants;
import com.marketplace.app.graphql.generated.types.LanguageCodeEnum;
import com.marketplace.app.graphql.generated.types.Page;
import com.marketplace.app.graphql.generated.types.PageCountableConnection;
import com.marketplace.app.graphql.generated.types.PageCreate;
import com.marketplace.app.graphql.generated.types.PageCreateInput;
import com.marketplace.app.graphql.generated.types.PageDelete;
import com.marketplace.app.graphql.generated.types.PageInput;
import com.marketplace.app.graphql.generated.types.PageSortingInput;
import com.marketplace.app.graphql.generated.types.PageTranslate;
import com.marketplace.app.graphql.generated.types.PageTranslationInput;
import com.marketplace.app.graphql.generated.types.PageType;
import com.marketplace.app.graphql.generated.types.PageTypeCountableConnection;
import com.marketplace.app.graphql.generated.types.PageTypeCreate;
import com.marketplace.app.graphql.generated.types.PageTypeCreateInput;
import com.marketplace.app.graphql.generated.types.PageTypeDelete;
import com.marketplace.app.graphql.generated.types.PageTypeSortingInput;
import com.marketplace.app.graphql.generated.types.PageTypeUpdate;
import com.marketplace.app.graphql.generated.types.PageTypeUpdateInput;
import com.marketplace.app.graphql.generated.types.Attribute;
import com.marketplace.app.graphql.generated.types.PageUpdate;
import com.marketplace.app.graphql.generated.types.PageBulkDelete;
import com.marketplace.app.graphql.generated.types.PageBulkPublish;
import com.marketplace.app.graphql.generated.types.PageAttributeAssign;
import com.marketplace.app.graphql.generated.types.PageAttributeUnassign;
import com.marketplace.app.graphql.generated.types.PageTypeReorderAttributes;
import com.marketplace.app.graphql.generated.types.ReorderInput;
import com.marketplace.console.spi.CmsPageSpi;
import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import graphql.schema.DataFetchingEnvironment;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.graphql.data.pagination.EncodingCursorStrategy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696 — the
 * D-CMS-001 ownership decision): the GraphQL adapter for the CMS
 * page/page-type surfaces — the Saleor {@code page*}/{@code pageType*}/
 * {@code pageAttribute*} mutations and the {@code page(s)}/
 * {@code pageType(s)} read roots, delegating through the declared
 * {@link CmsPageSpi} seam to the console module's owning use case.
 * Transport-only (the frozen §7 contract). The seam moved from the
 * shared-api {@code CmsPagePort} to the console-owned named SPI in the
 * post-merge integration round (the {@code sharedPortsAreFrameworkNeutral}
 * repair — the recorded agent-1 handoff migration path, executed).
 */
@DgsComponent
public class CmsPageDataFetcher {

    static final String PAGE_TYPE_TAG = "Page";
    static final String PAGE_TYPE_TYPE_TAG = "PageType";

    private final CmsPageSpi cmsPageSpi;
    private final com.marketplace.catalog.spi.AttributeReadSpi attributeReadSpi;
    private final AgentOneGraphQlMappers mappers;
    private final SaleorGlobalIdCodec globalIdCodec;
    private final EncodingCursorStrategy<ScrollPosition> cursorStrategy;
    private final GraphQlProperties properties;
    private final CmsPageGraphQlMapper pageMapper;
    private final AttributeGraphQlMapper attributeMapper;

    public CmsPageDataFetcher(CmsPageSpi cmsPageSpi,
                              com.marketplace.catalog.spi.AttributeReadSpi attributeReadSpi,
                              AgentOneGraphQlMappers mappers,
                              SaleorGlobalIdCodec globalIdCodec,
                              EncodingCursorStrategy<ScrollPosition> cursorStrategy,
                              GraphQlProperties properties,
                              CmsPageGraphQlMapper pageMapper,
                              AttributeGraphQlMapper attributeMapper) {
        this.cmsPageSpi = cmsPageSpi;
        this.attributeReadSpi = attributeReadSpi;
        this.mappers = mappers;
        this.globalIdCodec = globalIdCodec;
        this.cursorStrategy = cursorStrategy;
        this.properties = properties;
        this.pageMapper = pageMapper;
        this.attributeMapper = attributeMapper;
    }

    // ---------------------------------------------------------------------
    // Page mutations
    // ---------------------------------------------------------------------

    @DgsMutation(field = DgsConstants.MUTATION.PageCreate)
    @RateLimiter(name = "graphqlCmsMutation")
    public PageCreate pageCreate(@InputArgument PageCreateInput input) {
        if (input == null || input.getTitle() == null || input.getTitle().isBlank()) {
            throw new IllegalArgumentException("A page title is required");
        }
        rejectPublishedAt(input.getPublishedAt());
        return PageCreate.newBuilder()
                .page(toGraphQlPage(cmsPageSpi.createPage(currentAuthentication(),
                        input.getTitle(), input.getSlug(), input.getContent(),
                        input.getIsPublished(), seoTitle(input), seoDescription(input),
                        decodeId(input.getPageType(), PAGE_TYPE_TYPE_TAG,
                                "A page type ID is required"))))
                .errors(mappers.emptyPageErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.PageUpdate)
    @RateLimiter(name = "graphqlCmsMutation")
    public PageUpdate pageUpdate(@InputArgument String id,
                                 @InputArgument PageInput input) {
        requireInput(input);
        rejectPublishedAt(input.getPublishedAt());
        return PageUpdate.newBuilder()
                .page(toGraphQlPage(cmsPageSpi.updatePage(currentAuthentication(),
                        decodeId(id, PAGE_TYPE_TAG, "A page ID is required"),
                        input.getTitle(), input.getSlug(), input.getContent(),
                        input.getIsPublished(), seoTitle(input), seoDescription(input))))
                .errors(mappers.emptyPageErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.PageDelete)
    @RateLimiter(name = "graphqlCmsMutation")
    public PageDelete pageDelete(@InputArgument String id) {
        return PageDelete.newBuilder()
                .page(toGraphQlPage(cmsPageSpi.deletePage(currentAuthentication(),
                        decodeId(id, PAGE_TYPE_TAG, "A page ID is required"))))
                .errors(mappers.emptyPageErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.PageBulkDelete)
    @RateLimiter(name = "graphqlCmsMutation")
    public PageBulkDelete pageBulkDelete(@InputArgument List<String> ids) {
        properties.requireBoundedList(ids, "pageBulkDelete");
        int count = cmsPageSpi.bulkDeletePages(currentAuthentication(),
                decodeIds(ids, PAGE_TYPE_TAG, "A list of page IDs is required"));
        return PageBulkDelete.newBuilder()
                .count(count)
                .errors(mappers.emptyPageErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.PageBulkPublish)
    @RateLimiter(name = "graphqlCmsMutation")
    public PageBulkPublish pageBulkPublish(@InputArgument List<String> ids,
                                           @InputArgument Boolean isPublished) {
        properties.requireBoundedList(ids, "pageBulkPublish");
        if (isPublished == null) {
            throw new IllegalArgumentException("The isPublished flag is required");
        }
        int count = cmsPageSpi.bulkPublishPages(currentAuthentication(),
                decodeIds(ids, PAGE_TYPE_TAG, "A list of page IDs is required"), isPublished);
        return PageBulkPublish.newBuilder()
                .count(count)
                .errors(mappers.emptyPageErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.PageTranslate)
    @RateLimiter(name = "graphqlCmsMutation")
    public PageTranslate pageTranslate(@InputArgument String id,
                                       @InputArgument PageTranslationInput input,
                                       @InputArgument LanguageCodeEnum languageCode) {
        requireInput(input);
        if (languageCode == null) {
            throw new IllegalArgumentException("A language code is required");
        }
        return PageTranslate.newBuilder()
                .page(toGraphQlPage(cmsPageSpi.translatePage(currentAuthentication(),
                        decodeId(id, PAGE_TYPE_TAG, "A page ID is required"),
                        languageCode.name(), input.getSlug(), input.getTitle(),
                        input.getContent(), input.getSeoTitle(), input.getSeoDescription())))
                .errors(mappers.emptyPageErrors())
                .build();
    }

    // ---------------------------------------------------------------------
    // Page type mutations
    // ---------------------------------------------------------------------

    @DgsMutation(field = DgsConstants.MUTATION.PageTypeCreate)
    @RateLimiter(name = "graphqlCmsMutation")
    public PageTypeCreate pageTypeCreate(@InputArgument PageTypeCreateInput input) {
        if (input == null || input.getName() == null || input.getName().isBlank()) {
            throw new IllegalArgumentException("A page type name is required");
        }
        return PageTypeCreate.newBuilder()
                .pageType(toGraphQlPageType(cmsPageSpi.createPageType(currentAuthentication(),
                        input.getName(), input.getSlug(),
                        validatedAttributes(input.getAddAttributes()))))
                .errors(mappers.emptyPageErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.PageTypeUpdate)
    @RateLimiter(name = "graphqlCmsMutation")
    public PageTypeUpdate pageTypeUpdate(@InputArgument String id,
                                         @InputArgument PageTypeUpdateInput input) {
        requireInput(input);
        // Saleor 3.23.45 declares this ID nullable. DeprecatedModelMutation's
        // official get_instance/perform_mutation path creates a fresh model when
        // ID is absent, even for an *Update mutation. Mirror that wire behavior
        // rather than imposing a stricter ID! contract at the resolver boundary.
        if (id == null || id.isBlank()) {
            return PageTypeUpdate.newBuilder()
                    .pageType(toGraphQlPageType(cmsPageSpi.createPageType(currentAuthentication(),
                            input.getName(), input.getSlug(),
                            validatedAttributes(input.getAddAttributes()))))
                    .errors(mappers.emptyPageErrors())
                    .build();
        }
        return PageTypeUpdate.newBuilder()
                .pageType(toGraphQlPageType(cmsPageSpi.updatePageType(currentAuthentication(),
                        decodeId(id, PAGE_TYPE_TYPE_TAG, "A page type ID is required"),
                        input.getName(), input.getSlug(),
                        validatedAttributes(input.getAddAttributes()),
                        validatedAttributes(input.getRemoveAttributes()))))
                .errors(mappers.emptyPageErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.PageTypeDelete)
    @RateLimiter(name = "graphqlCmsMutation")
    public PageTypeDelete pageTypeDelete(@InputArgument String id) {
        return PageTypeDelete.newBuilder()
                .pageType(toGraphQlPageType(cmsPageSpi.deletePageType(currentAuthentication(),
                        decodeId(id, PAGE_TYPE_TYPE_TAG, "A page type ID is required"))))
                .errors(mappers.emptyPageErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.PageTypeBulkDelete)
    @RateLimiter(name = "graphqlCmsMutation")
    public com.marketplace.app.graphql.generated.types.PageTypeBulkDelete pageTypeBulkDelete(
            @InputArgument List<String> ids) {
        properties.requireBoundedList(ids, "pageTypeBulkDelete");
        int count = cmsPageSpi.bulkDeletePageTypes(currentAuthentication(),
                decodeIds(ids, PAGE_TYPE_TYPE_TAG, "A list of page type IDs is required"));
        return com.marketplace.app.graphql.generated.types.PageTypeBulkDelete.newBuilder()
                .count(count)
                .errors(mappers.emptyPageErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.PageAttributeAssign)
    @RateLimiter(name = "graphqlCmsMutation")
    public PageAttributeAssign pageAttributeAssign(@InputArgument List<String> attributeIds,
                                                   @InputArgument String pageTypeId) {
        properties.requireBoundedList(attributeIds, "pageAttributeAssign");
        return PageAttributeAssign.newBuilder()
                .pageType(toGraphQlPageType(cmsPageSpi.assignPageTypeAttributes(
                        currentAuthentication(),
                        decodeId(pageTypeId, PAGE_TYPE_TYPE_TAG, "A page type ID is required"),
                        validatedAttributes(attributeIds))))
                .errors(mappers.emptyPageErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.PageAttributeUnassign)
    @RateLimiter(name = "graphqlCmsMutation")
    public PageAttributeUnassign pageAttributeUnassign(@InputArgument List<String> attributeIds,
                                                       @InputArgument String pageTypeId) {
        properties.requireBoundedList(attributeIds, "pageAttributeUnassign");
        return PageAttributeUnassign.newBuilder()
                .pageType(toGraphQlPageType(cmsPageSpi.unassignPageTypeAttributes(
                        currentAuthentication(),
                        decodeId(pageTypeId, PAGE_TYPE_TYPE_TAG, "A page type ID is required"),
                        validatedAttributes(attributeIds))))
                .errors(mappers.emptyPageErrors())
                .build();
    }

    @DgsMutation(field = DgsConstants.MUTATION.PageTypeReorderAttributes)
    @RateLimiter(name = "graphqlCmsMutation")
    public PageTypeReorderAttributes pageTypeReorderAttributes(
            @InputArgument List<ReorderInput> moves,
            @InputArgument String pageTypeId) {
        properties.requireBoundedList(moves, "pageTypeReorderAttributes");
        if (moves == null || moves.isEmpty()) {
            throw new IllegalArgumentException("A list of reorder moves is required");
        }
        List<UUID> attributeIds = new ArrayList<>(moves.size());
        List<Integer> deltas = new ArrayList<>(moves.size());
        for (ReorderInput move : moves) {
            attributeIds.add(globalIdCodec.decode(move.getId(), "Attribute"));
            deltas.add(move.getSortOrder());
        }
        return PageTypeReorderAttributes.newBuilder()
                .pageType(toGraphQlPageType(cmsPageSpi.reorderPageTypeAttributes(
                        currentAuthentication(),
                        decodeId(pageTypeId, PAGE_TYPE_TYPE_TAG, "A page type ID is required"),
                        attributeIds, deltas)))
                .errors(mappers.emptyPageErrors())
                .build();
    }

    // ---------------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------------

    @DgsQuery(field = DgsConstants.QUERY.Page)
    @RateLimiter(name = "graphqlCmsQuery")
    public Page page(@InputArgument String channel,
                     @InputArgument String id,
                     @InputArgument String slug,
                     @InputArgument LanguageCodeEnum slugLanguageCode) {
        rejectChannel(channel);
        if (slugLanguageCode != null) {
            throw new IllegalArgumentException(
                    "page slugLanguageCode is not implemented on this platform yet");
        }
        if (id != null && !id.isBlank()) {
            return cmsPageSpi.findPageById(currentAuthentication(),
                            decodeId(id, PAGE_TYPE_TAG, "A page ID is required"))
                    .map(this::toGraphQlPage).orElse(null);
        }
        if (slug != null && !slug.isBlank()) {
            return cmsPageSpi.findPageBySlug(currentAuthentication(), slug)
                    .map(this::toGraphQlPage).orElse(null);
        }
        throw new IllegalArgumentException("A page ID or a slug is required");
    }

    @DgsQuery(field = DgsConstants.QUERY.Pages)
    @RateLimiter(name = "graphqlCmsQuery")
    public PageCountableConnection pages(@InputArgument Integer first,
                                         @InputArgument String after,
                                         @InputArgument String before,
                                         @InputArgument Integer last,
                                         @InputArgument String channel,
                                         @InputArgument Object filter,
                                         @InputArgument String search,
                                         @InputArgument PageSortingInput sortBy,
                                         @InputArgument Object where) {
        if (before != null || last != null) {
            throw new IllegalArgumentException(
                    "pages supports forward-only pagination (first/after) on this platform");
        }
        if (filter != null || where != null || search != null) {
            throw new IllegalArgumentException(
                    "pages filter/where/search is not implemented on this platform yet");
        }
        rejectChannel(channel);
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) {
            return PageCountableConnection.newBuilder()
                    .edges(List.of())
                    .pageInfo(pageInfoOf(null, null, false, false))
                    .build();
        }
        String field = sortBy == null ? "SLUG" : sortBy.getField() == null
                ? "SLUG" : sortBy.getField().name();
        boolean ascending = sortBy == null || sortBy.getDirection() == null
                || com.marketplace.app.graphql.generated.types.OrderDirection.ASC
                        .equals(sortBy.getDirection());
        KeysetScrollPosition position = keysetPosition(after, field);
        CmsPageSpi.PageWindow window = cmsPageSpi.scrollPages(currentAuthentication(), field,
                ascending, position,
                Limit.of(Math.min(requested, properties.pagination().maxPageSize())));
        return toConnection(window, PageCountableConnection.newBuilder(), position);
    }

    @DgsQuery(field = DgsConstants.QUERY.PageType)
    @RateLimiter(name = "graphqlCmsQuery")
    public PageType pageType(@InputArgument String id) {
        return cmsPageSpi.findPageTypeById(currentAuthentication(),
                        decodeId(id, PAGE_TYPE_TYPE_TAG, "A page type ID is required"))
                .map(this::toGraphQlPageType).orElse(null);
    }

    @DgsQuery(field = DgsConstants.QUERY.PageTypes)
    @RateLimiter(name = "graphqlCmsQuery")
    public PageTypeCountableConnection pageTypes(@InputArgument Integer first,
                                                 @InputArgument String after,
                                                 @InputArgument String before,
                                                 @InputArgument Integer last,
                                                 @InputArgument Object filter,
                                                 @InputArgument PageTypeSortingInput sortBy) {
        if (before != null || last != null) {
            throw new IllegalArgumentException(
                    "pageTypes supports forward-only pagination (first/after) on this platform");
        }
        if (filter != null) {
            throw new IllegalArgumentException(
                    "pageTypes filter is not implemented on this platform yet");
        }
        int requested = first == null ? properties.pagination().defaultPageSize() : first;
        if (requested <= 0) {
            return PageTypeCountableConnection.newBuilder()
                    .edges(List.of())
                    .pageInfo(pageInfoOf(null, null, false, false))
                    .build();
        }
        String field = sortBy == null ? "NAME" : sortBy.getField() == null
                ? "NAME" : sortBy.getField().name();
        boolean ascending = sortBy == null || sortBy.getDirection() == null
                || com.marketplace.app.graphql.generated.types.OrderDirection.ASC
                        .equals(sortBy.getDirection());
        KeysetScrollPosition typePosition = keysetPosition(after, field);
        CmsPageSpi.PageTypeWindow window = cmsPageSpi.scrollPageTypes(currentAuthentication(),
                field, ascending, typePosition,
                Limit.of(Math.min(requested, properties.pagination().maxPageSize())));
        return toTypeConnection(window, typePosition);
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private Page toGraphQlPage(CmsPageSpi.PageView view) {
        // The wave-3 review's MenuItem.page binding: the mapping moved to
        // the shared CmsPageGraphQlMapper (the package's *GraphQlMapper
        // component precedent) so the navigation fetcher's field resolver
        // renders the SAME page projection — one mapping, every surface.
        return pageMapper.toGraphQlPage(view);
    }

    PageType toGraphQlPageType(CmsPageSpi.PageTypeView view) {
        return PageType.newBuilder()
                .id(globalIdCodec.encode(PAGE_TYPE_TYPE_TAG, view.id()))
                .name(view.name() == null ? "" : view.name())
                .slug(view.slug() == null ? "" : view.slug())
                // The attributes field belongs to pageTypeAttributes below
                // (the DGS field-fetcher model — the projection cost is paid
                // only when the client actually selects the field).
                .attributes(null)
                .build();
    }

    /**
     * The {@code PageType.attributes} read-back — the wave-4 #806 review's
     * Minor · Functional Correctness fix: the base projection leaves the
     * field to THIS resolver, so {@code pageTypeCreate},
     * {@code pageTypeUpdate}, {@code pageAttributeAssign},
     * {@code pageAttributeUnassign} and {@code pageTypeReorderAttributes}
     * answer the SAVED attributes instead of a hard-coded null. The ids
     * ride the owning projection in position order (the SPI's documented
     * contract) and each renders through the catalog module's shared
     * attribute mapper; an attribute deleted concurrently answers the
     * honest absence (the SPI's never-a-fabricated-attribute contract),
     * and a type deleted concurrently answers null.
     *
     * <p>This is a FIELD-level completion resolver — the caller reads from
     * the REQUEST-SCOPED context (the {@code menuItemParent} precedent this
     * same review measured: the ThreadLocal does not reach the engine's
     * completion threads); an absent caller answers the honest null floor.</p>
     */
    @DgsData(parentType = DgsConstants.PAGETYPE.TYPE_NAME,
             field = DgsConstants.PAGETYPE.Attributes)
    public List<Attribute> pageTypeAttributes(DataFetchingEnvironment environment) {
        PageType source = environment.getSource();
        if (source == null || source.getId() == null) {
            return null;
        }
        Authentication caller = MarketplaceGraphQlContext.callerOf(environment);
        if (caller == null || !caller.isAuthenticated()) {
            return null;
        }
        return cmsPageSpi.findPageTypeById(caller,
                        decodeId(source.getId(), PAGE_TYPE_TYPE_TAG, "A page type ID is required"))
                .map(CmsPageSpi.PageTypeView::attributeIds)
                .map(ids -> {
                    List<Attribute> attributes = new ArrayList<>(ids.size());
                    for (UUID attributeId : ids) {
                        attributeReadSpi.findAttributeById(attributeId, caller)
                                .map(attributeMapper::toGraphQlAttribute)
                                .ifPresent(attributes::add);
                    }
                    return List.copyOf(attributes);
                })
                .orElse(null);
    }

    private PageCountableConnection toConnection(CmsPageSpi.PageWindow window,
                                                 PageCountableConnection.Builder builder,
                                                 KeysetScrollPosition position) {
        List<com.marketplace.app.graphql.generated.types.PageCountableEdge> edges =
                new ArrayList<>(window.window().getContent().size());
        for (int i = 0; i < window.window().getContent().size(); i++) {
            edges.add(com.marketplace.app.graphql.generated.types.PageCountableEdge.newBuilder()
                    .node(toGraphQlPage(window.window().getContent().get(i)))
                    .cursor(cursorStrategy.toCursor(window.window().positionAt(i)))
                    .build());
        }
        // The shard review (Minor · Functional Correctness, 2026-11-01
        // wave 2): the Relay envelope — cursors from the first/last edge,
        // hasPreviousPage from the REQUESTED scroll position (the
        // PermissionGroupDataFetcher.toConnection pattern).
        String start = edges.isEmpty() ? null : edges.get(0).getCursor();
        String end = edges.isEmpty() ? null : edges.get(edges.size() - 1).getCursor();
        return builder.edges(edges)
                .pageInfo(pageInfoOf(start, end, window.window().hasNext(),
                        !position.isInitial()))
                .totalCount((int) Math.min(window.total(), Integer.MAX_VALUE))
                .build();
    }

    private PageTypeCountableConnection toTypeConnection(CmsPageSpi.PageTypeWindow window,
                                                          KeysetScrollPosition position) {
        List<com.marketplace.app.graphql.generated.types.PageTypeCountableEdge> edges =
                new ArrayList<>(window.window().getContent().size());
        for (int i = 0; i < window.window().getContent().size(); i++) {
            edges.add(com.marketplace.app.graphql.generated.types.PageTypeCountableEdge.newBuilder()
                    .node(toGraphQlPageType(window.window().getContent().get(i)))
                    .cursor(cursorStrategy.toCursor(window.window().positionAt(i)))
                    .build());
        }
        String start = edges.isEmpty() ? null : edges.get(0).getCursor();
        String end = edges.isEmpty() ? null : edges.get(edges.size() - 1).getCursor();
        return PageTypeCountableConnection.newBuilder()
                .edges(edges)
                .pageInfo(pageInfoOf(start, end, window.window().hasNext(),
                        !position.isInitial()))
                .totalCount((int) Math.min(window.total(), Integer.MAX_VALUE))
                .build();
    }

    /**
     * The connection envelope's page info — the PermissionGroupDataFetcher
     * toConnection pattern (the shard review 2026-11-01 wave 2): the Relay
     * cursors from the first/last edge and hasPreviousPage from the
     * REQUESTED scroll position.
     */
    private static com.marketplace.app.graphql.generated.types.PageInfo pageInfoOf(
            String startCursor, String endCursor, boolean hasNext, boolean hasPrevious) {
        return com.marketplace.app.graphql.generated.types.PageInfo.newBuilder()
                .hasNextPage(hasNext)
                .hasPreviousPage(hasPrevious)
                .startCursor(startCursor)
                .endCursor(endCursor)
                .build();
    }

    private KeysetScrollPosition keysetPosition(String after, String sortField) {
        // The connection's own sort keys (B2, the CodeRabbit correctness
        // round): pageTypes defaults to NAME and the service orders that
        // window by (name, id) — the cursor key must match the requested
        // sort or the gate rejects the emitted cursor on the next request
        // (the previous fall-through handed pageTypes' NAME windows a
        // slug-keyed cursor).
        String property = switch (sortField) {
            case "TITLE" -> "title";
            case "CREATED_AT", "PUBLICATION_DATE" -> "createdAt";
            case "NAME" -> "name";
            default -> "slug";
        };
        java.util.function.Function<Object, Object> value = "createdAt".equals(property)
                ? KeysetCursorGate::instantValue : KeysetCursorGate::textValue;
        return KeysetCursorGate.forwardKeyset(cursorStrategy, after, property, value,
                "pages with the same sortBy");
    }

    private List<UUID> validatedAttributes(List<String> attributeIds) {
        if (attributeIds == null || attributeIds.isEmpty()) {
            return List.of();
        }
        // The wave-4 review (Minor · Performance & Scalability): the bound
        // moves INSIDE — pageTypeCreate/Update hand their add/remove
        // attribute lists straight here without a requireBoundedList call,
        // and each entry costs the owner one findAttributeById read; the
        // check covers every caller from this one place (the same policy
        // the page roots already enforce for their own lists).
        properties.requireBoundedList(attributeIds, "pageType attributes");
        Authentication caller = currentAuthentication();
        List<UUID> decoded = attributeIds.stream()
                .map(id -> globalIdCodec.decode(id, "Attribute"))
                .toList();
        for (UUID attributeId : decoded) {
            if (attributeReadSpi.findAttributeById(attributeId, caller).isEmpty()) {
                throw new com.marketplace.shared.api.ResourceNotFoundException(
                        "No attribute " + attributeId);
            }
        }
        return decoded;
    }

    private static String seoTitle(PageCreateInput input) {
        return input == null || input.getSeo() == null ? null : input.getSeo().getTitle();
    }

    private static String seoDescription(PageCreateInput input) {
        return input == null || input.getSeo() == null ? null : input.getSeo().getDescription();
    }

    private static String seoTitle(PageInput input) {
        return input == null || input.getSeo() == null ? null : input.getSeo().getTitle();
    }

    private static String seoDescription(PageInput input) {
        return input == null || input.getSeo() == null ? null : input.getSeo().getDescription();
    }

    private static void requireInput(Object input) {
        if (input == null) {
            throw new IllegalArgumentException("An input is required");
        }
    }

    /**
     * The shard review (Minor · Functional Correctness, 2026-11-01): the
     * SDL declares {@code publishedAt} on the page inputs, but the owning
     * {@code CmsPageSpi} has no scheduled-publication capability — the
     * value was accepted and silently dropped (a successful
     * {@code errors: []} response that saved nothing the caller asked
     * for). The file's own contract for unimplemented inputs
     * ({@code rejectChannel}/{@code slugLanguageCode}) is explicit
     * rejection: never a silent no-op.
     */
    private static void rejectPublishedAt(String publishedAt) {
        if (publishedAt != null && !publishedAt.isBlank()) {
            throw new IllegalArgumentException(
                    "page publishedAt is not implemented on this platform yet");
        }
    }

    private static void rejectChannel(String channel) {
        if (channel != null && !channel.isBlank()) {
            throw new IllegalArgumentException(
                    "The channel argument is not implemented on this platform yet "
                            + "(the platform is a single-channel store)");
        }
    }

    private UUID decodeId(String id, String tag, String message) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return globalIdCodec.decode(id, tag);
    }

    private List<UUID> decodeIds(List<String> ids, String tag, String message) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return ids.stream().map(id -> decodeId(id, tag, message)).toList();
    }

    private Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new org.springframework.security.authentication
                    .AuthenticationCredentialsNotFoundException(
                    "Authentication is required for CMS administration");
        }
        return authentication;
    }
}
