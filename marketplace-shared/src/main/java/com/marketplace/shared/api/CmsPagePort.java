package com.marketplace.shared.api;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Window;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696 — the
 * D-CMS-001 ownership decision): the CMS page+page-type port — the
 * composition boundary's declared seam to the console module's
 * dashboard-managed content registry (the shared-api port idiom: the app
 * layer consumes the interface, the console module provides the
 * implementation; the handoff records the A0 migration path to a
 * console-owned named SPI when the allowlist entry lands).
 *
 * <p>The house seam discipline verbatim (the IdentitySpi precedent):
 * typed view records, {@code Authentication}-carrying use-case methods
 * (ADMIN enforcement lives in the owning implementation), and the
 * forward-only keyset window contract for the collection reads.</p>
 */
public interface CmsPagePort {

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
