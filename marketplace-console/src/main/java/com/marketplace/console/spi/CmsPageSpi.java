package com.marketplace.console.spi;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Window;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 1 / A0 (the Saleor 458-root campaign — the post-merge integration
 * review of PR #696): the CMS page+page-type SPI — the composition
 * boundary's declared seam to the console module's dashboard-managed
 * content registry (the D-CMS-001 ownership decision's final home).
 *
 * <p><b>Why this interface moved out of {@code shared/api}:</b> it was
 * born there as {@code CmsPagePort} riding the shared-api port idiom, but
 * its contract speaks Spring ({@code Authentication}, Spring Data's
 * {@code KeysetScrollPosition}/{@code Limit}/{@code Window}) — exactly
 * what the house rule {@code sharedPortsAreFrameworkNeutral} forbids in
 * the shared-contract module (a port speaking Spring Data's types drags
 * every consumer module into that type system). The recorded migration
 * path in the agent-1 handoff — "a console-owned named SPI when the
 * allowlist entry lands" — is executed here: the seam now lives where the
 * owning domain owns it, the same {@code IdentitySpi}/{@code catalog-spi}
 * precedent every journey seam follows (the module's declared SPI may
 * speak Spring; the SHARED module may not).</p>
 *
 * <p>The house seam discipline verbatim (the IdentitySpi precedent):
 * typed view records, {@code Authentication}-carrying use-case methods
 * (ADMIN enforcement lives in the owning implementation — Saleor's
 * MANAGE_PAGES/MANAGE_PAGE_TYPES_AND_ATTRIBUTES mapped to the platform's
 * ADMIN capability), and the forward-only keyset window contract for the
 * collection reads.</p>
 */
@org.springframework.modulith.NamedInterface("console-spi")
public interface CmsPageSpi {

    /** The page translation projection. */
    record PageTranslationView(String languageCode, String slug, String title,
                               String content, String seoTitle, String seoDescription) {
    }

    /** The page projection (translations keyed by language code). */
    record PageView(UUID id, UUID pageTypeId, String pageTypeName, String pageTypeSlug,
                    String slug, String title, String content,
                    boolean published, java.time.Instant publishedAt, String seoTitle,
                    String seoDescription, java.time.Instant createdAt,
                    Map<String, PageTranslationView> translations) {
    }

    /** The page type projection (assigned attribute IDs in position order). */
    record PageTypeView(UUID id, String name, String slug, List<UUID> attributeIds) {
    }

    /** One page of the collection (the forward-only keyset contract). */
    record PageWindow(Window<PageView> window, long total) {
    }

    /** One page-type page of the collection. */
    record PageTypeWindow(Window<PageTypeView> window, long total) {
    }

    PageView createPage(Authentication caller, String title, String slug, String content,
                        Boolean published, String seoTitle, String seoDescription,
                        UUID pageTypeId);

    PageView updatePage(Authentication caller, UUID id, String title, String slug, String content,
                        Boolean published, String seoTitle, String seoDescription);

    PageView deletePage(Authentication caller, UUID id);

    int bulkDeletePages(Authentication caller, List<UUID> ids);

    int bulkPublishPages(Authentication caller, List<UUID> ids, boolean published);

    PageView translatePage(Authentication caller, UUID id, String languageCode, String slug,
                           String title, String content, String seoTitle, String seoDescription);

    Optional<PageView> findPageById(Authentication caller, UUID id);

    Optional<PageView> findPageBySlug(Authentication caller, String slug);

    PageWindow scrollPages(Authentication caller, String sortField, boolean ascending,
                           KeysetScrollPosition position, Limit limit);

    PageTypeView createPageType(Authentication caller, String name, String slug,
                                List<UUID> attributeIds);

    PageTypeView updatePageType(Authentication caller, UUID id, String name, String slug,
                                List<UUID> addAttributes, List<UUID> removeAttributes);

    PageTypeView deletePageType(Authentication caller, UUID id);

    int bulkDeletePageTypes(Authentication caller, List<UUID> ids);

    PageTypeView assignPageTypeAttributes(Authentication caller, UUID pageTypeId,
                                          List<UUID> attributeIds);

    PageTypeView unassignPageTypeAttributes(Authentication caller, UUID pageTypeId,
                                            List<UUID> attributeIds);

    PageTypeView reorderPageTypeAttributes(Authentication caller, UUID pageTypeId,
                                           List<UUID> attributeIds, List<Integer> deltas);

    Optional<PageTypeView> findPageTypeById(Authentication caller, UUID id);

    PageTypeWindow scrollPageTypes(Authentication caller, String sortField, boolean ascending,
                                   KeysetScrollPosition position, Limit limit);
}
