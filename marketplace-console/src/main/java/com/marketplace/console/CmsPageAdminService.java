package com.marketplace.console;

import com.marketplace.console.spi.CmsPageSpi;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Window;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696 — the
 * D-CMS-001 ownership decision): the console module's CMS page+page-type
 * owning use case — the {@code page*}/{@code pageType*}/{@code pageAttribute*}
 * mutations and the {@code page(s)}/{@code pageType(s)} reads behind the
 * {@link CmsPageSpi} seam. ADMIN-enforced at this boundary (Saleor
 * requires MANAGE_PAGES/MANAGE_PAGE_TYPES_AND_ATTRIBUTES — the platform's
 * mapped ADMIN capability, the RBAC mapping divergence row).
 */
@Service
@Transactional
public class CmsPageAdminService implements CmsPageSpi {

    private static final Logger log = LoggerFactory.getLogger(CmsPageAdminService.class);
    private static final Pattern SLUG_SHAPE = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private final CmsPageRepository pageRepository;
    private final CmsPageTranslationRepository translationRepository;
    private final CmsPageTypeRepository pageTypeRepository;
    private final CmsPageTypeAttributeRepository attributeRepository;
    private final CmsMenuItemRepository menuItemRepository;

    public CmsPageAdminService(CmsPageRepository pageRepository,
                               CmsPageTranslationRepository translationRepository,
                               CmsPageTypeRepository pageTypeRepository,
                               CmsPageTypeAttributeRepository attributeRepository,
                               CmsMenuItemRepository menuItemRepository) {
        this.pageRepository = pageRepository;
        this.translationRepository = translationRepository;
        this.pageTypeRepository = pageTypeRepository;
        this.attributeRepository = attributeRepository;
        this.menuItemRepository = menuItemRepository;
    }

    // ---------------------------------------------------------------------
    // Pages
    // ---------------------------------------------------------------------

    @Override
    @Observed(name = "cms.page.create")
    public PageView createPage(Authentication caller, String title, String slug, String content,
                               Boolean published, String seoTitle, String seoDescription,
                               UUID pageTypeId) {
        requireAdmin(caller);
        String trimmedTitle = requireTitle(title);
        UUID type = requirePageTypeExists(pageTypeId);
        requireFieldLimits(slug, seoTitle, seoDescription);
        String resolvedSlug = requireFreeSlug(slug == null || slug.isBlank()
                ? slugify(trimmedTitle) : slug.trim());
        CmsPage row = CmsPage.create(type, resolvedSlug, trimmedTitle);
        row.apply(null, null, content, published, seoTitle, seoDescription);
        pageRepository.save(row);
        log.info("CMS page created: id={}, slug={}, actor={}", row.getId(), resolvedSlug,
                caller.getName());
        return toView(row);
    }

    @Override
    @Observed(name = "cms.page.update")
    public PageView updatePage(Authentication caller, UUID id, String title, String slug,
                               String content, Boolean published, String seoTitle,
                               String seoDescription) {
        requireAdmin(caller);
        CmsPage row = requirePage(id);
        requireFieldLimits(slug, seoTitle, seoDescription);
        // The 2026-11-05 #810 round review (Minor · Functional
        // Correctness): a non-empty rename longer than the VARCHAR(255)
        // column answers the classified validation error HERE — the same
        // bound the create path carries (null and blank stay "no
        // information", the entity's own apply contract).
        if (title != null && !title.isBlank() && title.trim().length() > 255) {
            throw new IllegalArgumentException("The page title exceeds 255 characters");
        }
        if (slug != null && !slug.isBlank()) {
            String resolved = requireSlugShape(slug.trim());
            if (!resolved.equals(row.getSlug()) && pageRepository.existsBySlug(resolved)) {
                throw new ConflictException("The slug already exists: " + resolved);
            }
        }
        row.apply(slug, title, content, published, seoTitle, seoDescription);
        log.info("CMS page updated: id={}, actor={}", id, caller.getName());
        return toView(row);
    }

    @Override
    @Observed(name = "cms.page.delete")
    public PageView deletePage(Authentication caller, UUID id) {
        requireAdmin(caller);
        // The 2026-11-05 #810 round review (Minor · Functional
        // Correctness): the delete-vs-link race — the row loads
        // PESSIMISTIC_WRITE so a concurrent menu-item link (which locks
        // the same row before its insert) serializes BEHIND this delete:
        // the reference check and the link insert can no longer interleave
        // into a committed orphan.
        CmsPage row = pageRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("No page " + id));
        if (menuItemRepository.existsByPageId(id)) {
            throw new ConflictException(
                    "The page is referenced by a menu item — remove the link first");
        }
        translationRepository.findAllByPageId(id).forEach(translationRepository::delete);
        pageRepository.delete(row);
        log.info("CMS page deleted: id={}, actor={}", id, caller.getName());
        return toView(row);
    }

    @Override
    @Observed(name = "cms.page.bulk_delete")
    public int bulkDeletePages(Authentication caller, List<UUID> ids) {
        requireAdmin(caller);
        int count = 0;
        for (UUID id : ids) {
            // The 2026-11-05 #810 round review: the same locked load the
            // single delete carries (the delete-vs-link serialization).
            Optional<CmsPage> row = pageRepository.findByIdForUpdate(id);
            if (row.isPresent() && !menuItemRepository.existsByPageId(id)) {
                translationRepository.findAllByPageId(id).forEach(translationRepository::delete);
                pageRepository.delete(row.get());
                count++;
            }
        }
        log.info("CMS pages bulk-deleted: count={}, actor={}", count, caller.getName());
        return count;
    }

    @Override
    @Observed(name = "cms.page.bulk_publish")
    public int bulkPublishPages(Authentication caller, List<UUID> ids, boolean published) {
        requireAdmin(caller);
        int count = 0;
        for (UUID id : ids) {
            Optional<CmsPage> row = pageRepository.findById(id);
            if (row.isPresent()) {
                row.get().setPublished(published);
                count++;
            }
        }
        log.info("CMS pages bulk-published: count={}, published={}, actor={}",
                count, published, caller.getName());
        return count;
    }

    @Override
    @Observed(name = "cms.page.translate")
    public PageView translatePage(Authentication caller, UUID id, String languageCode,
                                  String slug, String title, String content, String seoTitle,
                                  String seoDescription) {
        requireAdmin(caller);
        CmsPage page = requirePage(id);
        String language = requireLanguage(languageCode);
        requireFieldLimits(slug, seoTitle, seoDescription);
        // The 2026-11-05 #810 round review: the translated title carries
        // the same 255 bound the page's own title carries.
        if (title != null && !title.isBlank() && title.trim().length() > 255) {
            throw new IllegalArgumentException("The page title exceeds 255 characters");
        }
        CmsPageTranslation translation = translationRepository
                .findByPageIdAndLanguageCode(id, language)
                .orElseGet(() -> {
                    CmsPageTranslation fresh = CmsPageTranslation.create(id, language);
                    translationRepository.save(fresh);
                    return fresh;
                });
        translation.apply(slug, title, content, seoTitle, seoDescription);
        log.info("CMS page translated: id={}, language={}, actor={}", id, language,
                caller.getName());
        return toView(page);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PageView> findPageById(Authentication caller, UUID id) {
        requireAdmin(caller);
        return pageRepository.findById(id).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PageView> findPageBySlug(Authentication caller, String slug) {
        requireAdmin(caller);
        return pageRepository.findBySlug(slug).map(this::toView);
    }

    @Override
    @Transactional(readOnly = true)
    public PageWindow scrollPages(Authentication caller, String sortField, boolean ascending,
                                  KeysetScrollPosition position, Limit limit) {
        requireAdmin(caller);
        requireForwardScroll(position, "pages");
        // The 2026-11-05 #810 round review (Minor · Functional
        // Correctness): an UNKNOWN sort field answers the classified
        // validation error — never a silent fallback to the default sort
        // the caller did not ask for (null stays the documented default).
        // PUBLICATION_DATE rides createdAt per the registered
        // DECISION-10 divergence (the materialized column is deferred
        // until the real sort need exists).
        Window<CmsPage> window = switch (sortField == null ? "SLUG" : sortField) {
            case "SLUG" -> ascending
                    ? pageRepository.findAllByOrderBySlugAscIdAsc(position, limit)
                    : pageRepository.findAllByOrderBySlugDescIdDesc(position, limit);
            case "TITLE" -> ascending
                    ? pageRepository.findAllByOrderByTitleAscIdAsc(position, limit)
                    : pageRepository.findAllByOrderByTitleDescIdDesc(position, limit);
            case "CREATED_AT", "PUBLICATION_DATE" -> ascending
                    ? pageRepository.findAllByOrderByCreatedAtAscIdAsc(position, limit)
                    : pageRepository.findAllByOrderByCreatedAtDescIdDesc(position, limit);
            default -> throw new IllegalArgumentException(
                    "Unsupported pages sort field: " + sortField);
        };
        return new PageWindow(window.map(this::toView), pageRepository.count());
    }

    // ---------------------------------------------------------------------
    // Page types
    // ---------------------------------------------------------------------

    @Override
    @Observed(name = "cms.page_type.create")
    public PageTypeView createPageType(Authentication caller, String name, String slug,
                                        List<UUID> attributeIds) {
        requireAdmin(caller);
        String trimmed = requireTypeName(name);
        String resolvedSlug = requireFreeTypeSlug(slug == null || slug.isBlank()
                ? slugify(trimmed) : slug.trim());
        CmsPageType row = pageTypeRepository.save(CmsPageType.create(trimmed, resolvedSlug));
        assignPairs(row.getId(), attributeIds);
        log.info("CMS page type created: id={}, slug={}, actor={}", row.getId(), resolvedSlug,
                caller.getName());
        return toTypeView(row);
    }

    @Override
    @Observed(name = "cms.page_type.update")
    public PageTypeView updatePageType(Authentication caller, UUID id, String name, String slug,
                                       List<UUID> addAttributes, List<UUID> removeAttributes) {
        requireAdmin(caller);
        // The 2026-11-05 #810 round review (Minor · Functional
        // Correctness): the locked load — the update path's own assign
        // legs ride the same serialization; and the non-empty rename
        // carries the 255 bound the create path already carries (the
        // classified validation error, never the flush's raw error).
        CmsPageType row = requirePageTypeForUpdate(id);
        if (name != null && !name.isBlank() && name.trim().length() > 255) {
            throw new IllegalArgumentException("The page type name exceeds 255 characters");
        }
        if (slug != null && !slug.isBlank() && slug.trim().length() > 255) {
            throw new IllegalArgumentException("The slug exceeds 255 characters");
        }
        if (slug != null && !slug.isBlank()) {
            String resolved = requireSlugShape(slug.trim());
            if (!resolved.equals(row.getSlug()) && pageTypeRepository.existsBySlug(resolved)) {
                throw new ConflictException("The slug already exists: " + resolved);
            }
        }
        row.apply(name, slug);
        assignPairs(id, addAttributes);
        if (removeAttributes != null) {
            for (UUID attributeId : removeAttributes) {
                attributeRepository.findByPageTypeIdAndAttributeId(id, attributeId)
                        .ifPresent(attributeRepository::delete);
            }
            resequence(id);
        }
        log.info("CMS page type updated: id={}, actor={}", id, caller.getName());
        return toTypeView(row);
    }

    @Override
    @Observed(name = "cms.page_type.delete")
    public PageTypeView deletePageType(Authentication caller, UUID id) {
        requireAdmin(caller);
        CmsPageType row = requirePageType(id);
        if (pageRepository.existsByPageTypeId(id)) {
            throw new ConflictException(
                    "Pages still reference the page type — move or delete them first");
        }
        attributeRepository.findAllByPageTypeIdOrderByPositionAscIdAsc(id)
                .forEach(attributeRepository::delete);
        pageTypeRepository.delete(row);
        log.info("CMS page type deleted: id={}, actor={}", id, caller.getName());
        return toTypeView(row);
    }

    @Override
    @Observed(name = "cms.page_type.bulk_delete")
    public int bulkDeletePageTypes(Authentication caller, List<UUID> ids) {
        requireAdmin(caller);
        int count = 0;
        for (UUID id : ids) {
            Optional<CmsPageType> row = pageTypeRepository.findById(id);
            if (row.isPresent() && !pageRepository.existsByPageTypeId(id)) {
                attributeRepository.findAllByPageTypeIdOrderByPositionAscIdAsc(id)
                        .forEach(attributeRepository::delete);
                pageTypeRepository.delete(row.get());
                count++;
            }
        }
        log.info("CMS page types bulk-deleted: count={}, actor={}", count, caller.getName());
        return count;
    }

    @Override
    public PageTypeView assignPageTypeAttributes(Authentication caller, UUID pageTypeId,
                                                 List<UUID> attributeIds) {
        requireAdmin(caller);
        // The 2026-11-05 #810 round review (Minor · Functional
        // Correctness): the locked load — the assign leg joins the same
        // serialization family as unassign/reorder (assignPairs re-locks
        // idempotently inside the transaction).
        CmsPageType row = requirePageTypeForUpdate(pageTypeId);
        requireAttributeList(attributeIds);
        assignPairs(pageTypeId, attributeIds);
        log.info("CMS page type attributes assigned: typeId={}, count={}, actor={}",
                pageTypeId, attributeIds.size(), caller.getName());
        return toTypeView(row);
    }

    @Override
    public PageTypeView unassignPageTypeAttributes(Authentication caller, UUID pageTypeId,
                                                   List<UUID> attributeIds) {
        requireAdmin(caller);
        // The 2026-11-05 #810 round review (Minor · Functional
        // Correctness): the locked load — the unassign + resequence
        // serializes against every other assignment-family write.
        CmsPageType row = requirePageTypeForUpdate(pageTypeId);
        requireAttributeList(attributeIds);
        for (UUID attributeId : attributeIds) {
            CmsPageTypeAttribute pair = attributeRepository
                    .findByPageTypeIdAndAttributeId(pageTypeId, attributeId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "The attribute is not assigned to the page type: " + attributeId));
            attributeRepository.delete(pair);
        }
        resequence(pageTypeId);
        log.info("CMS page type attributes unassigned: typeId={}, count={}, actor={}",
                pageTypeId, attributeIds.size(), caller.getName());
        return toTypeView(row);
    }

    @Override
    public PageTypeView reorderPageTypeAttributes(Authentication caller, UUID pageTypeId,
                                                  List<UUID> attributeIds, List<Integer> deltas) {
        requireAdmin(caller);
        // The 2026-11-05 #810 round review (Minor · Functional
        // Correctness): the locked load — the position read and rewrite
        // serialize against every other assignment write on the same type
        // (the schema does not enforce (page_type_id, position) uniqueness;
        // two concurrent reorders could read one state and both write it).
        CmsPageType row = pageTypeRepository.findByIdForUpdate(pageTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("No page type " + pageTypeId));
        // The 2026-11-05 #810 round review: the lists are one-per-move by
        // contract — a null list or a length mismatch answers the
        // classified validation error BEFORE any database read (the old
        // path answered the raw NullPointerException or
        // IndexOutOfBoundsException mid-batch, and silently IGNORED
        // surplus deltas).
        requireAttributeList(attributeIds);
        if (deltas == null || deltas.size() != attributeIds.size()) {
            throw new IllegalArgumentException(
                    "reorderPageTypeAttributes requires deltas with exactly one entry per"
                            + " attribute");
        }
        List<CmsPageTypeAttribute> pairs =
                new ArrayList<>(attributeRepository.findAllByPageTypeIdOrderByPositionAscIdAsc(pageTypeId));
        // The deterministic relative-move semantics (the CodeRabbit
        // correctness round — the same fix the menu-item move carries):
        // each moved attribute is REMOVED from the ordered list and
        // reinserted at clamp(index + delta), so a shifted position never
        // ties with a sibling and the UUID tie-breaker never decides the
        // order; dense positions follow. The list is a mutable copy —
        // repository queries may return immutable lists.
        for (int i = 0; i < attributeIds.size(); i++) {
            UUID attributeId = attributeIds.get(i);
            int from = indexByAttributeId(pairs, attributeId);
            if (from < 0) {
                throw new ResourceNotFoundException(
                        "The attribute is not assigned to the page type: " + attributeId);
            }
            int delta = deltas.get(i) == null ? 0 : deltas.get(i);
            CmsPageTypeAttribute pair = pairs.remove(from);
            // The 2026-11-05 #810 round review (Minor · Functional
            // Correctness): the clamp arithmetic runs in LONG — from +
            // Integer.MAX_VALUE overflows int to a NEGATIVE value the
            // clamp then reads as 0 (a wrong-but-accepted move); the long
            // sum clamps to the list's own end honestly.
            int to = (int) Math.max(0L, Math.min((long) from + delta, pairs.size()));
            pairs.add(to, pair);
        }
        parkAndWriteDense(pairs);
        log.info("CMS page type attributes reordered: typeId={}, moves={}, actor={}",
                pageTypeId, attributeIds.size(), caller.getName());
        return toTypeView(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PageTypeView> findPageTypeById(Authentication caller, UUID id) {
        requireAdmin(caller);
        return pageTypeRepository.findById(id).map(this::toTypeView);
    }

    @Override
    @Transactional(readOnly = true)
    public PageTypeWindow scrollPageTypes(Authentication caller, String sortField,
                                          boolean ascending, KeysetScrollPosition position,
                                          Limit limit) {
        requireAdmin(caller);
        requireForwardScroll(position, "page types");
        // The 2026-11-05 #810 round review: an UNKNOWN sort field answers
        // the classified validation error, never a silent fallback (null
        // stays the documented default).
        Window<CmsPageType> window = switch (sortField == null ? "NAME" : sortField) {
            case "NAME" -> ascending
                    ? pageTypeRepository.findAllByOrderByNameAscIdAsc(position, limit)
                    : pageTypeRepository.findAllByOrderByNameDescIdDesc(position, limit);
            case "SLUG" -> ascending
                    ? pageTypeRepository.findAllByOrderBySlugAscIdAsc(position, limit)
                    : pageTypeRepository.findAllByOrderBySlugDescIdDesc(position, limit);
            default -> throw new IllegalArgumentException(
                    "Unsupported page types sort field: " + sortField);
        };
        return new PageTypeWindow(window.map(this::toTypeView), pageTypeRepository.count());
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private void assignPairs(UUID pageTypeId, List<UUID> attributeIds) {
        if (attributeIds == null || attributeIds.isEmpty()) {
            return;
        }
        // The 2026-11-05 #810 round review (Minor · Functional
        // Correctness): the assignment's position read and insert
        // serialize on the type row's PESSIMISTIC_WRITE lock (the caller
        // already holds it on the update path; the create path locks its
        // own fresh row — the lock is idempotent inside the transaction).
        pageTypeRepository.findByIdForUpdate(pageTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("No page type " + pageTypeId));
        List<CmsPageTypeAttribute> existing =
                attributeRepository.findAllByPageTypeIdOrderByPositionAscIdAsc(pageTypeId);
        int nextPosition = existing.isEmpty() ? 0
                : existing.get(existing.size() - 1).getPosition() + 1;
        // The seen-set carries BOTH the pre-existing assignments and every
        // ID this request already accepted (the CodeRabbit integrity
        // round): a request such as [a, a] previously compared each entry
        // only against the pre-existing list, saved two pairs, and answered
        // the (type, attribute) unique index's raw database error instead
        // of the classified ConflictException.
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (CmsPageTypeAttribute pair : existing) {
            seen.add(pair.getAttributeId());
        }
        for (UUID attributeId : attributeIds) {
            if (!seen.add(attributeId)) {
                throw new ConflictException(
                        "The attribute is already assigned to the page type: " + attributeId);
            }
            attributeRepository.save(
                    CmsPageTypeAttribute.create(pageTypeId, attributeId, nextPosition++));
        }
    }

    private static int indexByAttributeId(List<CmsPageTypeAttribute> pairs, UUID attributeId) {
        for (int i = 0; i < pairs.size(); i++) {
            if (pairs.get(i).getAttributeId().equals(attributeId)) {
                return i;
            }
        }
        return -1;
    }

    private void resequence(UUID pageTypeId) {
        List<CmsPageTypeAttribute> pairs =
                attributeRepository.findAllByPageTypeIdOrderByPositionAscIdAsc(pageTypeId);
        parkAndWriteDense(pairs);
    }

    /**
     * The two-phase dense rewrite the live-rows unique index
     * (page_type_id, position) demands: a direct rewrite collides
     * transiently (a pair moving to position 0 shares it with the current
     * holder until that pair's own UPDATE executes — the partial unique
     * index sees the intermediate state). Phase 1 parks the whole list
     * beyond its current maximum, flushes (the live range is now empty),
     * then phase 2 writes the final dense order — one transaction,
     * all-or-nothing. The same repair the catalog reorders carry.
     */
    private void parkAndWriteDense(List<CmsPageTypeAttribute> pairs) {
        if (pairs.isEmpty()) {
            return;
        }
        boolean changed = false;
        for (int i = 0; i < pairs.size(); i++) {
            if (pairs.get(i).getPosition() != i) {
                changed = true;
                break;
            }
        }
        if (!changed) {
            // The idempotent no-op: the resolved order is the stored order —
            // nothing changes, nothing writes.
            return;
        }
        int park = pairs.stream().mapToInt(CmsPageTypeAttribute::getPosition).max().orElse(0) + 1;
        for (CmsPageTypeAttribute pair : pairs) {
            pair.moveTo(park++);
        }
        attributeRepository.saveAll(pairs);
        attributeRepository.flush();
        for (int i = 0; i < pairs.size(); i++) {
            pairs.get(i).moveTo(i);
        }
        attributeRepository.saveAll(pairs);
    }

    private PageView toView(CmsPage row) {
        Map<String, PageTranslationView> translations = new HashMap<>();
        for (CmsPageTranslation translation : translationRepository.findAllByPageId(row.getId())) {
            translations.put(translation.getLanguageCode(), new PageTranslationView(
                    translation.getId(), translation.getLanguageCode(), translation.getSlug(),
                    translation.getTitle(), translation.getContent(),
                    translation.getSeoTitle(), translation.getSeoDescription()));
        }
        CmsPageType pageType = pageTypeRepository.findById(row.getPageTypeId()).orElse(null);
        return new PageView(row.getId(), row.getPageTypeId(),
                pageType == null ? "" : pageType.getName(),
                pageType == null ? "" : pageType.getSlug(),
                row.getSlug(), row.getTitle(),
                row.getContent(), row.isPublished(), row.getPublishedAt(), row.getSeoTitle(),
                row.getSeoDescription(), row.getCreatedAt(), translations);
    }

    private PageTypeView toTypeView(CmsPageType row) {
        return new PageTypeView(row.getId(), row.getName(), row.getSlug(),
                attributeRepository.findAllByPageTypeIdOrderByPositionAscIdAsc(row.getId())
                        .stream().map(CmsPageTypeAttribute::getAttributeId).toList());
    }

    private CmsPage requirePage(UUID id) {
        return pageRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No page " + id));
    }

    /**
     * The 2026-11-05 #810 round review (Minor · Functional Correctness):
     * the V281/V282 column bounds validated at the boundary — a present
     * (non-null) value longer than its column answers the classified
     * validation error, never the flush's raw database error (the null
     * and blank semantics belong to the entity's own apply contract).
     */
    private static void requireFieldLimits(String slug, String seoTitle, String seoDescription) {
        if (slug != null && !slug.isBlank() && slug.trim().length() > 255) {
            throw new IllegalArgumentException("The slug exceeds 255 characters");
        }
        if (seoTitle != null && !seoTitle.isBlank() && seoTitle.trim().length() > 255) {
            throw new IllegalArgumentException("The SEO title exceeds 255 characters");
        }
        if (seoDescription != null && !seoDescription.isBlank()
                && seoDescription.trim().length() > 512) {
            throw new IllegalArgumentException("The SEO description exceeds 512 characters");
        }
    }

    /**
     * The 2026-11-05 #810 round review (Minor · Functional Correctness):
     * the forward-only scroll contract enforced BEFORE the repository —
     * Spring Data Commons 4.x's KeysetScrollPosition.isInitial() is
     * keys.isEmpty() (direction-independent), so an empty BACKWARD
     * position used to slip past an isInitial()-only guard and answer a
     * forward page with forward cursors from the END of the window's
     * math; the direction itself is the contract.
     */
    private static void requireForwardScroll(
            org.springframework.data.domain.KeysetScrollPosition position, String surface) {
        if (position != null && !position.scrollsForward()) {
            throw new IllegalArgumentException(
                    "The " + surface + " scroll supports forward-only pagination");
        }
    }

    private CmsPageType requirePageType(UUID id) {
        return pageTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No page type " + id));
    }

    /**
     * The 2026-11-05 #810 round review (Minor · Functional Correctness):
     * the PESSIMISTIC_WRITE load the assignment-family write paths carry —
     * every path that reads the type's assigned positions and rewrites
     * them (assign, unassign + resequence) serializes on the type row, so
     * concurrent writers cannot read one last-position and both insert at
     * it (the reservation-wall pattern the catalog assignment carries).
     */
    private CmsPageType requirePageTypeForUpdate(UUID id) {
        return pageTypeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("No page type " + id));
    }

    private UUID requirePageTypeExists(UUID id) {
        if (id == null || !pageTypeRepository.existsById(id)) {
            throw new ResourceNotFoundException("No page type " + id);
        }
        return id;
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A page title is required");
        }
        String trimmed = title.trim();
        if (trimmed.length() > 255) {
            throw new IllegalArgumentException("The page title exceeds 255 characters");
        }
        return trimmed;
    }

    private static String requireTypeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A page type name is required");
        }
        return name.trim();
    }

    private String requireFreeSlug(String slug) {
        requireSlugShape(slug);
        if (pageRepository.existsBySlug(slug)) {
            throw new ConflictException("The slug already exists: " + slug);
        }
        return slug;
    }

    private String requireFreeTypeSlug(String slug) {
        requireSlugShape(slug);
        if (pageTypeRepository.existsBySlug(slug)) {
            throw new ConflictException("The slug already exists: " + slug);
        }
        return slug;
    }

    private static String requireSlugShape(String slug) {
        if (slug == null || !SLUG_SHAPE.matcher(slug).matches()) {
            throw new IllegalArgumentException("The slug must be lowercase kebab-case: " + slug);
        }
        return slug;
    }

    private static String requireLanguage(String languageCode) {
        if (languageCode == null || !languageCode.matches("^[a-z]{2}(-[A-Za-z]{2,4})?$")) {
            throw new IllegalArgumentException("A language code is required: " + languageCode);
        }
        return languageCode;
    }

    private static void requireAttributeList(List<UUID> attributeIds) {
        if (attributeIds == null || attributeIds.isEmpty() || attributeIds.size() > 100) {
            throw new IllegalArgumentException(
                    "Between one and a hundred attribute IDs are required");
        }
    }

    private static String slugify(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private static void requireAdmin(Authentication caller) {
        boolean admin = caller.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (!admin) {
            throw new AccessDeniedException(
                    "CMS page administration requires the ADMIN capability");
        }
    }
}
