package com.marketplace.catalog;

import com.marketplace.catalog.spi.CollectionCreateCommand;
import com.marketplace.catalog.spi.CollectionMutationError;
import com.marketplace.catalog.spi.CollectionMutationErrorCode;
import com.marketplace.catalog.spi.CollectionMutationResult;
import com.marketplace.catalog.spi.CollectionProductMove;
import com.marketplace.catalog.spi.CollectionUpdateCommand;
import com.marketplace.catalog.spi.CollectionView;
import com.marketplace.catalog.spi.CollectionWriteSpi;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor
 * {@code collectionCreate}, {@code collectionUpdate},
 * {@code collectionDelete}, {@code collectionBulkDelete},
 * {@code collectionAddProducts}, {@code collectionRemoveProducts} and
 * {@code collectionReorderProducts} mutation roots, packet agent-2): the
 * owning use case for the storefront collection registry's ADMIN write
 * surface and its product membership. These are Saleor MANAGEMENT roots
 * ("Requires one of the following permissions: MANAGE_PRODUCTS") mapped
 * through the platform's ADMIN capability (the SaleorPermissionCatalog
 * contract) and enforced HERE at the owning use-case boundary by Spring
 * Security's method security — the same {@code @PreAuthorize} mechanism
 * every write family in this module established — never only in a
 * resolver.
 *
 * <p><b>The validation rules are the registry's own contracts</b> — the
 * entity's own documented shapes: the slug is the stable API-facing key
 * whose DB CHECK pins lowercase latin/digits/dashes (the {@code V253}
 * shape), and the {@code uq_collections_slug_live} partial unique index
 * keeps LIVE rows unique while a soft-deleted row releases its slug for
 * reuse (the V47 slug precedent — presentation identity). The service
 * re-checks both BEFORE the write (the honest precheck over the derived
 * {@code findBySlug} lookup) and lets the partial unique index remain the
 * concurrent-write backstop — the same no-TOCTOU stance the repository's
 * own javadoc documents. Domain validation outcomes answer as result
 * error entries (the Saleor payload-error ADAPT); transport concerns
 * (malformed typed global IDs, denied authorization) never reach this
 * service.</p>
 *
 * <p><b>The membership write surfaces ride the {@code V253}
 * {@code collection_products} registry's own laws:</b> the live-pair
 * partial unique index is the duplicate-add backstop (the precheck
 * answers the classified DUPLICATED_INPUT_ITEM domain error with the
 * offending product ids — the pinned error shape's own {@code products}
 * member); the append position is the owner's own max+1 derivation; the
 * reorder resolves the pinned relative moves against the membership's
 * current order with the compacted 1..n re-derivation (the position
 * column's own deterministic law — the
 * {@code ProductTypeAttributeAssignment} reorder precedent); the remove
 * is the honest idempotent skip for a non-member product. <b>Soft
 * delete</b> rides the {@code BaseEntity} {@code @SoftDelete}
 * discipline, and the deleted row's live membership rides the same
 * transaction (the membership rows reference the collection — the
 * registry's own referential law).</p>
 */
@Service
public class CollectionWriteService implements CollectionWriteSpi {

    /**
     * The slug shape the DB CHECK itself pins — lowercase latin letters,
     * digits and dashes (the {@code V253} CHECK, mirrored at the boundary
     * so an invalid shape answers the classified INVALID domain error,
     * never a DB CHECK failure.
     */
    static final Pattern SLUG_SHAPE = Pattern.compile("[a-z0-9][a-z0-9-]*");

    /** The registry's own column ceilings (the entity's own length contracts). */
    private static final int SLUG_MAX_LENGTH = 255;
    private static final int NAME_MAX_LENGTH = 100;

    private final CollectionRepository collectionRepository;
    private final CollectionProductRepository collectionProductRepository;
    private final ProductRepository productRepository;

    public CollectionWriteService(CollectionRepository collectionRepository,
                                  CollectionProductRepository collectionProductRepository,
                                  ProductRepository productRepository) {
        this.collectionRepository = collectionRepository;
        this.collectionProductRepository = collectionProductRepository;
        this.productRepository = productRepository;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CollectionMutationResult createCollection(CollectionCreateCommand command) {
        List<CollectionMutationError> errors = new ArrayList<>();
        String name = command.name() == null ? null : command.name().strip();
        String slug = command.slug() == null ? null : command.slug().strip().toLowerCase(Locale.ROOT);
        if (name == null || name.isBlank()) {
            errors.add(new CollectionMutationError("name", CollectionMutationErrorCode.REQUIRED,
                    "A collection name is required"));
        } else if (name.length() > NAME_MAX_LENGTH) {
            errors.add(new CollectionMutationError("name", CollectionMutationErrorCode.INVALID,
                    "A collection name is at most " + NAME_MAX_LENGTH + " characters"));
        }
        if (slug == null || slug.isBlank()) {
            errors.add(new CollectionMutationError("slug", CollectionMutationErrorCode.REQUIRED,
                    "A collection slug is required"));
        } else if (!SLUG_SHAPE.matcher(slug).matches() || slug.length() > SLUG_MAX_LENGTH) {
            errors.add(new CollectionMutationError("slug", CollectionMutationErrorCode.INVALID,
                    "A collection slug is lowercase latin letters, digits and dashes (at most "
                            + SLUG_MAX_LENGTH + " characters)"));
        } else if (collectionRepository.findBySlug(slug).isPresent()) {
            errors.add(new CollectionMutationError("slug", CollectionMutationErrorCode.UNIQUE,
                    "A collection with the slug already exists"));
        }
        // The optional initial membership: every product must exist, and no
        // duplicate id may ride the request (the pinned DUPLICATED_INPUT_ITEM
        // law over the input's own products member).
        List<UUID> productIds = command.productIds() == null ? List.of() : command.productIds();
        List<UUID> duplicated = duplicatedIds(productIds);
        if (!duplicated.isEmpty()) {
            errors.add(new CollectionMutationError("products",
                    CollectionMutationErrorCode.DUPLICATED_INPUT_ITEM,
                    "The same product id appears more than once in the request", duplicated));
        }
        List<UUID> unknown = unknownProducts(productIds);
        if (!unknown.isEmpty()) {
            errors.add(new CollectionMutationError("products", CollectionMutationErrorCode.NOT_FOUND,
                    "No product found for the given ids", unknown));
        }
        if (!errors.isEmpty()) {
            return CollectionMutationResult.failure(errors);
        }
        boolean published = Boolean.TRUE.equals(command.published());
        Collection created = collectionRepository.save(
                Collection.create(slug, name, null, published));
        appendMembership(created.getId(), productIds);
        return CollectionMutationResult.success(toView(created));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CollectionMutationResult updateCollection(UUID id, CollectionUpdateCommand command) {
        Optional<Collection> addressed = id == null ? Optional.empty() : collectionRepository.findById(id);
        if (addressed.isEmpty()) {
            return CollectionMutationResult.failure(List.of(new CollectionMutationError(
                    "id", CollectionMutationErrorCode.NOT_FOUND, "No collection found for the id")));
        }
        List<CollectionMutationError> errors = new ArrayList<>();
        String name = command.name() == null ? null : command.name().strip();
        String slug = command.slug() == null ? null : command.slug().strip().toLowerCase(Locale.ROOT);
        if (name != null) {
            if (name.isBlank()) {
                errors.add(new CollectionMutationError("name", CollectionMutationErrorCode.INVALID,
                        "A collection name cannot be blank"));
            } else if (name.length() > NAME_MAX_LENGTH) {
                errors.add(new CollectionMutationError("name", CollectionMutationErrorCode.INVALID,
                        "A collection name is at most " + NAME_MAX_LENGTH + " characters"));
            }
        }
        if (slug != null) {
            if (!SLUG_SHAPE.matcher(slug).matches() || slug.length() > SLUG_MAX_LENGTH) {
                errors.add(new CollectionMutationError("slug", CollectionMutationErrorCode.INVALID,
                        "A collection slug is lowercase latin letters, digits and dashes (at most "
                                + SLUG_MAX_LENGTH + " characters)"));
            } else {
                collectionRepository.findBySlug(slug)
                        .filter(existing -> !existing.getId().equals(id))
                        .ifPresent(existing -> errors.add(new CollectionMutationError(
                                "slug", CollectionMutationErrorCode.UNIQUE,
                                "A collection with the slug already exists")));
            }
        }
        if (!errors.isEmpty()) {
            return CollectionMutationResult.failure(errors);
        }
        Collection row = addressed.get();
        row.revise(slug, name, command.published() == null ? row.isPublished() : command.published());
        Collection updated = collectionRepository.save(row);
        return CollectionMutationResult.success(toView(updated));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CollectionMutationResult deleteCollection(UUID id) {
        Optional<Collection> addressed = id == null ? Optional.empty() : collectionRepository.findById(id);
        if (addressed.isEmpty()) {
            return CollectionMutationResult.failure(List.of(new CollectionMutationError(
                    "id", CollectionMutationErrorCode.NOT_FOUND, "No collection found for the id")));
        }
        Collection row = addressed.get();
        CollectionView lastKnown = toView(row);
        // The live membership rides the same transaction (the membership
        // rows reference the collection — the registry's own referential
        // law; a soft-deleted membership releases the pair for a later
        // re-add, the release-on-delete law).
        for (CollectionProduct member : collectionProductRepository
                .findByCollectionIdOrderByPositionAscIdAsc(row.getId())) {
            collectionProductRepository.delete(member);
        }
        collectionRepository.delete(row);
        return CollectionMutationResult.success(lastKnown);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public int deleteCollections(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (UUID id : ids) {
            // Unknown ids are skipped — the honest affected count answers
            // what the registry itself deleted (never a fabricated count,
            // never an error entry for an id the registry never knew).
            Optional<Collection> addressed = id == null ? Optional.empty() : collectionRepository.findById(id);
            if (addressed.isPresent()) {
                for (CollectionProduct member : collectionProductRepository
                        .findByCollectionIdOrderByPositionAscIdAsc(id)) {
                    collectionProductRepository.delete(member);
                }
                collectionRepository.delete(addressed.get());
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CollectionMutationResult addProducts(UUID collectionId, List<UUID> productIds) {
        Optional<Collection> addressed = collectionId == null
                ? Optional.empty() : collectionRepository.findById(collectionId);
        if (addressed.isEmpty()) {
            return CollectionMutationResult.failure(List.of(new CollectionMutationError(
                    "id", CollectionMutationErrorCode.NOT_FOUND, "No collection found for the id")));
        }
        List<CollectionMutationError> errors = new ArrayList<>();
        List<UUID> duplicated = duplicatedIds(productIds);
        if (!duplicated.isEmpty()) {
            errors.add(new CollectionMutationError("products",
                    CollectionMutationErrorCode.DUPLICATED_INPUT_ITEM,
                    "The same product id appears more than once in the request", duplicated));
        }
        List<UUID> unknown = unknownProducts(productIds);
        if (!unknown.isEmpty()) {
            errors.add(new CollectionMutationError("products", CollectionMutationErrorCode.NOT_FOUND,
                    "No product found for the given ids", unknown));
        }
        // The already-member pairs answer the same DUPLICATED_INPUT_ITEM
        // law with the offending ids (the live-pair precheck — the partial
        // unique index remains the concurrent-write backstop).
        List<UUID> alreadyMember = new ArrayList<>();
        for (UUID productId : distinct(productIds)) {
            if (collectionProductRepository
                    .findByCollectionIdAndProductId(collectionId, productId).isPresent()) {
                alreadyMember.add(productId);
            }
        }
        if (!alreadyMember.isEmpty()) {
            errors.add(new CollectionMutationError("products",
                    CollectionMutationErrorCode.DUPLICATED_INPUT_ITEM,
                    "The products are already members of the collection", alreadyMember));
        }
        if (!errors.isEmpty()) {
            return CollectionMutationResult.failure(errors);
        }
        appendMembership(collectionId, productIds);
        return CollectionMutationResult.success(toView(addressed.get()));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CollectionMutationResult removeProducts(UUID collectionId, List<UUID> productIds) {
        Optional<Collection> addressed = collectionId == null
                ? Optional.empty() : collectionRepository.findById(collectionId);
        if (addressed.isEmpty()) {
            return CollectionMutationResult.failure(List.of(new CollectionMutationError(
                    "id", CollectionMutationErrorCode.NOT_FOUND, "No collection found for the id")));
        }
        // A non-member product is the honest no-op skip (the remove's own
        // idempotent law — never an error, never a fabricated absence).
        for (UUID productId : distinct(productIds)) {
            collectionProductRepository
                    .findByCollectionIdAndProductId(collectionId, productId)
                    .ifPresent(collectionProductRepository::delete);
        }
        return CollectionMutationResult.success(toView(addressed.get()));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public CollectionMutationResult reorderProducts(UUID collectionId,
                                                    List<CollectionProductMove> moves) {
        Optional<Collection> addressed = collectionId == null
                ? Optional.empty() : collectionRepository.findById(collectionId);
        if (addressed.isEmpty()) {
            return CollectionMutationResult.failure(List.of(new CollectionMutationError(
                    "id", CollectionMutationErrorCode.NOT_FOUND, "No collection found for the id")));
        }
        List<CollectionProduct> membership = collectionProductRepository
                .findByCollectionIdOrderByPositionAscIdAsc(collectionId);
        // A move addressing a non-member product answers NOT_FOUND with
        // the pinned error shape's own products member.
        List<UUID> nonMembers = new ArrayList<>();
        Map<UUID, Integer> requested = new LinkedHashMap<>();
        for (CollectionProductMove move : moves) {
            if (move.productId() == null) {
                continue;
            }
            boolean isMember = membership.stream()
                    .anyMatch(row -> row.getProductId().equals(move.productId()));
            if (!isMember) {
                if (!nonMembers.contains(move.productId())) {
                    nonMembers.add(move.productId());
                }
                continue;
            }
            int shift = move.sortOrder() == null ? 0 : move.sortOrder();
            requested.merge(move.productId(), shift, Integer::sum);
        }
        if (!nonMembers.isEmpty()) {
            return CollectionMutationResult.failure(List.of(new CollectionMutationError(
                    "moves", CollectionMutationErrorCode.NOT_FOUND,
                    "The products are not members of the collection", nonMembers)));
        }

        // The pinned relative-move resolution against the membership's
        // current order, then the compacted 1..n re-derivation (the
        // position column's own deterministic law — the assignment
        // registry's reorder precedent).
        List<CollectionProduct> reordered = new ArrayList<>(membership);
        for (Map.Entry<UUID, Integer> move : requested.entrySet()) {
            int index = indexOfProduct(reordered, move.getKey());
            if (index < 0) {
                continue;
            }
            int target = index + move.getValue();
            if (target < 0 || target >= reordered.size()) {
                return CollectionMutationResult.failure(List.of(new CollectionMutationError(
                        "moves", CollectionMutationErrorCode.INVALID,
                        "The relative move leaves the collection's product order's range "
                                + "(a move of " + move.getValue() + " from position " + (index + 1)
                                + " is out of range)",
                        List.of(move.getKey()))));
            }
            CollectionProduct moved = reordered.remove(index);
            reordered.add(target, moved);
        }
        for (int index = 0; index < reordered.size(); index++) {
            CollectionProduct member = reordered.get(index);
            if (member.getPosition() != index + 1) {
                member.reposition(index + 1);
                collectionProductRepository.save(member);
            }
        }
        return CollectionMutationResult.success(toView(addressed.get()));
    }

    /**
     * The membership's append — the owner's own max+1 derivation over the
     * current live membership (the assignment registry's own append law).
     */
    private void appendMembership(UUID collectionId, List<UUID> productIds) {
        int next = collectionProductRepository
                .findByCollectionIdOrderByPositionAscIdAsc(collectionId).size() + 1;
        for (UUID productId : distinct(productIds)) {
            collectionProductRepository.save(CollectionProduct.create(collectionId, productId, next++));
        }
    }

    /** The request's own duplicate ids (the pinned DUPLICATED_INPUT_ITEM law's own input). */
    private static List<UUID> duplicatedIds(List<UUID> productIds) {
        List<UUID> duplicated = new ArrayList<>();
        if (productIds != null) {
            LinkedHashSet<UUID> seen = new LinkedHashSet<>();
            for (UUID productId : productIds) {
                if (productId != null && !seen.add(productId) && !duplicated.contains(productId)) {
                    duplicated.add(productId);
                }
            }
        }
        return duplicated;
    }

    /** The request's unknown product ids (the NOT_FOUND law's own input). */
    private List<UUID> unknownProducts(List<UUID> productIds) {
        List<UUID> unknown = new ArrayList<>();
        if (productIds != null) {
            for (UUID productId : distinct(productIds)) {
                if (productId == null || productRepository.findById(productId).isEmpty()) {
                    if (productId != null) {
                        unknown.add(productId);
                    }
                }
            }
        }
        return unknown;
    }

    /** The request's distinct non-null ids, first-seen order preserved. */
    private static List<UUID> distinct(List<UUID> productIds) {
        LinkedHashSet<UUID> distinct = new LinkedHashSet<>();
        if (productIds != null) {
            for (UUID productId : productIds) {
                if (productId != null) {
                    distinct.add(productId);
                }
            }
        }
        return List.copyOf(distinct);
    }

    /** The member row's own index in the current order (the id's own address). */
    private static int indexOfProduct(List<CollectionProduct> membership, UUID productId) {
        for (int index = 0; index < membership.size(); index++) {
            if (membership.get(index).getProductId().equals(productId)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The bounded projection — the read service's own rendering rule (the
     * rendered primary display name with the bilingual second name, the
     * stable public slug, the row's own publication fact).
     */
    private static CollectionView toView(Collection collection) {
        String name = collection.getNameEn() != null ? collection.getNameEn() : collection.getNameAr();
        return new CollectionView(collection.getId(), name, collection.getNameAr(),
                collection.getSlug(), collection.isPublished());
    }
}
