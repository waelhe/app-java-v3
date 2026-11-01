package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.Page;
import com.marketplace.app.graphql.generated.types.PageType;
import com.marketplace.console.spi.CmsPageSpi;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * The wave-3 review's {@code MenuItem.page} binding (Minor · Functional
 * Correctness): the page projection's mapping lived as a private method on
 * {@code CmsPageDataFetcher} alone, so no other surface could render a
 * {@code Page} without duplicating it. The mapper is extracted verbatim
 * (the same fields, the same ISO-8601 temporal rendering, the same
 * representable-floor null handling) — the 20-strong *GraphQlMapper
 * component precedent the package already follows — and the navigation
 * fetcher's {@code MenuItem.page} field resolver reuses it.
 */
@Component
public class CmsPageGraphQlMapper {

    static final String PAGE_TYPE_TAG = "Page";
    static final String PAGE_TYPE_TYPE_TAG = "PageType";

    private final SaleorGlobalIdCodec globalIdCodec;

    public CmsPageGraphQlMapper(SaleorGlobalIdCodec globalIdCodec) {
        this.globalIdCodec = globalIdCodec;
    }

    /** The page projection (the extracted CmsPageDataFetcher mapping, verbatim). */
    public Page toGraphQlPage(CmsPageSpi.PageView view) {
        return Page.newBuilder()
                .id(globalIdCodec.encode(PAGE_TYPE_TAG, view.id()))
                .seoTitle(view.seoTitle())
                .seoDescription(view.seoDescription())
                .title(view.title())
                .content(view.content())
                .isPublished(view.published())
                .publishedAt(view.publishedAt() == null ? null
                        : DateTimeFormatter.ISO_INSTANT.format(view.publishedAt()))
                .slug(view.slug())
                .created(view.createdAt() == null ? null
                        : DateTimeFormatter.ISO_INSTANT.format(view.createdAt()))
                .pageType(PageType.newBuilder()
                        .id(globalIdCodec.encode(PAGE_TYPE_TYPE_TAG, view.pageTypeId()))
                        .name(view.pageTypeName() == null ? "" : view.pageTypeName())
                        .slug(view.pageTypeSlug() == null ? "" : view.pageTypeSlug())
                        .build())
                .build();
    }
}
