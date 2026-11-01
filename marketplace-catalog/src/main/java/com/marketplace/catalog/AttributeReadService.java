package com.marketplace.catalog;

import com.marketplace.catalog.spi.AttributePage;
import com.marketplace.catalog.spi.AttributeReadSpi;
import com.marketplace.catalog.spi.AttributeSort;
import com.marketplace.catalog.spi.AttributeView;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code attribute} and
 * {@code attributes} read roots, the declared continuation of the
 * campaign-b2-a2 product-type slice): the owning use case for the typed
 * attribute reference collection. Both reads are ADMIN-enforced at this
 * boundary (the platform's server-side mapping of Saleor's staff
 * MANAGE_PRODUCTS-class requirement — the RBAC divergence row), read-only,
 * and own every business decision the boundary needs.
 *
 * <p><b>The scroll executes in memory over the bounded reference
 * collection</b> — the honest scope decision, documented: the
 * {@code catalog_attribute_definitions} table is reusable reference data
 * by the entity's own contract, and the repository's own whole-table
 * {@code findAllByOrderByCodeAsc} seam is the codebase's standing
 * precedent for reading it. The scroll keeps the SAME semantics the
 * roster's SQL keyset executes: the cursor identifies one row's own sort
 * key (the executed sort field, {@code id}), the page follows it strictly
 * in the requested direction, and {@code hasNext} states whether elements
 * remain — never an absolute offset. If the collection ever grows beyond
 * reference-data scale, the SQL keyset methods (the {@code UserRepository}
 * precedent) replace the in-memory window without changing the seam.</p>
 */
@Service
public class AttributeReadService implements AttributeReadSpi {

    private final CatalogAttributeDefinitionRepository attributeDefinitionRepository;

    public AttributeReadService(CatalogAttributeDefinitionRepository attributeDefinitionRepository) {
        this.attributeDefinitionRepository = attributeDefinitionRepository;
    }

    /**
     * The attribute lookup by the native UUID behind the typed global ID —
     * ADMIN-enforced. An unknown but well-formed UUID answers empty: the
     * nullable root's own honest absence, never an error and never a
     * fabricated attribute.
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Optional<AttributeView> findAttributeById(UUID id, Authentication authentication) {
        if (id == null) {
            return Optional.empty();
        }
        return attributeDefinitionRepository.findById(id).map(AttributeReadService::toView);
    }

    /**
     * The attribute lookup by the public slug (the row's immutable
     * {@code code} — the Saleor slug mapping) — ADMIN-enforced. An unknown
     * but well-formed slug answers empty: the nullable root's own honest
     * absence, never an error and never a fabricated attribute.
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Optional<AttributeView> findAttributeBySlug(String slug, Authentication authentication) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return attributeDefinitionRepository.findByCode(slug).map(AttributeReadService::toView);
    }

    /**
     * The attribute collection's forward-only keyset scroll —
     * ADMIN-enforced, the owning sort field (the rendered NAME or the
     * immutable SLUG code, each with the {@code id} tiebreaker), plus the
     * collection total Saleor's {@code totalCount} field carries.
     *
     * <p>The boundary's {@code KeysetCursorGate} has already validated the
     * cursor's {@code (sortProperty, id)} key set against this scroll's own
     * vocabulary; a position that is not the initial one must still carry
     * those keys here, or the honest {@link IllegalArgumentException}
     * answers — never a silent no-op.</p>
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public AttributePage scrollAttributes(Authentication authentication,
                                          AttributeSort sort,
                                          KeysetScrollPosition position,
                                          Limit limit) {
        if (sort == null || sort.field() == null) {
            throw new IllegalArgumentException("An attribute sort is required");
        }
        List<AttributeView> attributes = new ArrayList<>();
        for (CatalogAttributeDefinition definition : attributeDefinitionRepository.findAll()) {
            attributes.add(toView(definition));
        }

        // The deterministic ordering — the owning sort field with the id
        // tiebreaker, both in the requested direction (the roster's SQL
        // keyset methods' own shape: ...AscIdAsc / ...DescIdDesc).
        Comparator<String> fieldOrder = sort.ascending()
                ? Comparator.naturalOrder()
                : Comparator.reverseOrder();
        Comparator<UUID> idOrder = sort.ascending()
                ? Comparator.naturalOrder()
                : Comparator.reverseOrder();
        Function<AttributeView, String> fieldExtractor = sort.field() == AttributeSort.Field.SLUG
                ? AttributeView::slug
                : AttributeView::name;
        attributes.sort(Comparator
                .comparing(fieldExtractor, fieldOrder)
                .thenComparing(AttributeView::id, idOrder));

        // The keyset predicate: elements strictly AFTER the cursor's own
        // (sortProperty, id) sort key in the requested direction — the same
        // "greater than the sort key" the framework's keyset predicate
        // builds for the SQL scrolls.
        String sortProperty = sort.property();
        List<AttributeView> scrollable = attributes;
        if (position != null && !position.isInitial()) {
            if (!(position instanceof KeysetScrollPosition keyset)
                    || !keyset.scrollsForward()
                    || keyset.getKeys().get(sortProperty) == null
                    || keyset.getKeys().get("id") == null) {
                throw new IllegalArgumentException(
                        "The attribute scroll position must be a forward keyset over (" + sortProperty + ", id)");
            }
            String cursorField = String.valueOf(keyset.getKeys().get(sortProperty));
            UUID cursorId = keyset.getKeys().get("id") instanceof UUID uuid
                    ? uuid
                    : UUID.fromString(String.valueOf(keyset.getKeys().get("id")));
            scrollable = attributes.stream()
                    .filter(attribute -> {
                        int byField = fieldOrder.compare(fieldExtractor.apply(attribute), cursorField);
                        return byField != 0 ? byField > 0
                                : idOrder.compare(attribute.id(), cursorId) > 0;
                    })
                    .toList();
        }

        int pageSize = limit == null || !limit.isLimited()
                ? scrollable.size()
                : Math.min(limit.max(), scrollable.size());
        boolean hasNext = scrollable.size() > pageSize;
        List<AttributeView> content = scrollable.subList(0, pageSize);
        Window<AttributeView> window = Window.from(content, index ->
                ScrollPosition.forward(Map.of(
                        sortProperty, fieldExtractor.apply(content.get(index)),
                        "id", content.get(index).id())),
                hasNext);
        return new AttributePage(window, attributes.size());
    }

    /**
     * The bounded projection — only facts the
     * {@code catalog_attribute_definitions} row owns: the rendered primary
     * display name ({@code nameEn} when present, else {@code nameAr} — the
     * bilingual row's own at-least-one guarantee makes it non-null), the
     * stable public code as the Saleor slug, and the closed domain value
     * shape (the fact the boundary renders as Saleor's {@code inputType}
     * and {@code withChoices}).
     */
    private static AttributeView toView(CatalogAttributeDefinition definition) {
        String name = definition.getNameEn() != null ? definition.getNameEn() : definition.getNameAr();
        return new AttributeView(definition.getId(), name, definition.getCode(),
                definition.getValueType());
    }
}
