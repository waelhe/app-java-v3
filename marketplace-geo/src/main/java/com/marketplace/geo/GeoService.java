package com.marketplace.geo;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The geo module's read/write surface. Implements {@link GeoLookupPort} so
 * search (L32) and realestate (L31) resolve locations through the shared-api
 * abstraction — the data-owner-implements-the-port pattern (SYSTEM.md §5,
 * same as {@code CatalogService} implementing {@code CatalogSearchPort}).
 *
 * <p>Caching: the full tree is cached under {@code geo-tree} (the house's
 * 14th named cache — plan L30). Every admin amendment publishes
 * {@link CacheInvalidationRequested} so the existing AFTER_COMMIT relay
 * (SYSTEM.md §7) evicts on the same mechanism every other module uses —
 * no new invalidation machinery.
 *
 * <p>Observation policy (layer 6): admin commands only
 * ({@code geo.admin.create/update/delete}); reads ride the framework's
 * {@code http.server.requests}.
 */
@Service
@Transactional
public class GeoService implements GeoLookupPort {

    /** The single cache this module owns (invalidated through the relay). */
    public static final Set<String> GEO_CACHE_NAMES = Set.of("geo-tree");

    /** The plan's autocomplete floor: a 1-character prefix is a 400, not a query. */
    static final int MIN_SUGGEST_PREFIX = 2;

    static final int SUGGEST_LIMIT = 20;

    private final GeoLocationRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public GeoService(GeoLocationRepository repository,
                      ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "geo-tree")
    public GeoNode getTree() {
        List<GeoLocation> all = repository.findAll(); // soft-delete auto-filtered
        if (all.isEmpty()) {
            return new GeoNode(null, null, GeoLevel.COUNTRY.level(), "", null, "");
        }
        Map<UUID, List<GeoLocation>> byParent = all.stream()
                .filter(location -> location.getParentId() != null)
                .collect(Collectors.groupingBy(GeoLocation::getParentId));
        GeoLocation root = all.stream()
                .filter(location -> location.getParentId() == null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "geo tree has no root — the seed is missing"));
        return toNode(root, byParent);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GeoNode> getChildren(UUID parentId) {
        requireExisting(parentId);
        return repository.findByParentIdOrderBySlugAsc(parentId)
                .stream().map(GeoService::toNode).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<GeoNode> suggest(String prefix) {
        String trimmed = prefix == null ? "" : prefix.trim();
        if (trimmed.length() < MIN_SUGGEST_PREFIX) {
            throw new BadRequestException(
                    "suggest prefix must be at least " + MIN_SUGGEST_PREFIX + " characters");
        }
        return repository.suggestByPrefix(trimmed + "%", SUGGEST_LIMIT)
                .stream().map(GeoService::toNode).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findSelfAndDescendants(UUID locationId) {
        requireExisting(locationId);
        Set<UUID> ids = repository.findSelfAndDescendantIds(locationId);
        if (ids.isEmpty()) {
            // requireExisting passed but the CTE saw nothing — treat as absent.
            throw new ResourceNotFoundException("GeoLocation", locationId);
        }
        return ids;
    }

    @Override
    @Transactional(readOnly = true)
    public GeoNode getLocation(UUID id) {
        return toNode(requireExisting(id));
    }

    // ---- admin surface (the tree's only write path) ----

    /**
     * Creates a child under an existing parent (the root is seed-owned:
     * the admin surface never creates a second country — one tree, by
     * design).
     */
    @PreAuthorize("hasRole('ADMIN')")
    public GeoNode createChild(UUID parentId, String nameAr, String nameEn, String slug) {
        GeoLocation parent = requireExisting(parentId);
        if (repository.existsBySlug(slug)) {
            throw new ConflictException("slug already exists: " + slug);
        }
        GeoLocation saved = repository.save(
                GeoLocation.createChild(parent, nameAr, nameEn, slug));
        eventPublisher.publishEvent(new CacheInvalidationRequested(GEO_CACHE_NAMES));
        return toNode(saved);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public GeoNode update(UUID id, String nameAr, String nameEn, String slug) {
        GeoLocation location = requireExisting(id);
        if (!location.getSlug().equals(slug) && repository.existsBySlug(slug)) {
            throw new ConflictException("slug already exists: " + slug);
        }
        location.update(nameAr, nameEn, slug);
        eventPublisher.publishEvent(new CacheInvalidationRequested(GEO_CACHE_NAMES));
        return toNode(location);
    }

    /**
     * Deletes a node — refused while children exist (409): removing a
     * parent silently would orphan a subtree, and cascading is a policy
     * the tree refuses to guess (the acceptance criterion).
     */
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(UUID id) {
        GeoLocation location = requireExisting(id);
        if (repository.existsByParentId(id)) {
            throw new ConflictException(
                    "geo location " + id + " has children — move or delete them first");
        }
        repository.delete(location); // soft delete via @SoftDelete
        eventPublisher.publishEvent(new CacheInvalidationRequested(GEO_CACHE_NAMES));
    }

    private GeoLocation requireExisting(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("GeoLocation", id));
    }

    private static GeoNode toNode(GeoLocation location) {
        return new GeoNode(location.getId(), location.getParentId(), location.getLevel(),
                location.getNameAr(), location.getNameEn(), location.getSlug());
    }

    private static GeoNode toNode(GeoLocation root, Map<UUID, List<GeoLocation>> byParent) {
        List<GeoNode> children = new ArrayList<>();
        for (GeoLocation child : byParent.getOrDefault(root.getId(), List.of())) {
            children.add(toNode(child, byParent));
        }
        return new GeoNode(root.getId(), root.getParentId(), root.getLevel(),
                root.getNameAr(), root.getNameEn(), root.getSlug(), children);
    }
}
