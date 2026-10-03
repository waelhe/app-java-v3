package com.marketplace.catalog;

import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * W2 (yelp-level plan §5 — the business page): the category-attribute
 * registry's service — the administrative write surface and the public
 * read for the dynamic per-category attributes (G15).
 *
 * <p><b>Reference data (the categories' own lifecycle law, one level
 * deeper):</b> the registry evolves by INSERT through the administrative
 * surface — a new attribute for a category is never a migration
 * («السمات بيانات», the plan's own principle). The identity pair
 * (category, code) is immutable once created — a re-keyed attribute is a
 * new row (the V70 identity rule); labels, type and position replace.
 *
 * <p><b>Position allocation:</b> {@code max + 1} on create (the W1
 * max-allocation lesson — count-based allocation re-issues positions
 * soft deletion still holds). The registry's reads are live reads — the
 * reference data is small and rarely written; no cache layer to
 * invalidate (contrast the provider profile's "providers" cache).
 */
@Service
public class CategoryAttributeService {

    private final CategoryAttributeRepository categoryAttributeRepository;
    private final CategoryRepository categoryRepository;

    public CategoryAttributeService(CategoryAttributeRepository categoryAttributeRepository,
                                    CategoryRepository categoryRepository) {
        this.categoryAttributeRepository = categoryAttributeRepository;
        this.categoryRepository = categoryRepository;
    }

    /**
     * The registry's own read: one category's attributes in position
     * order — the public read the category surfaces compose.
     */
    @Transactional(readOnly = true)
    public List<CategoryAttribute> byCategoryId(UUID categoryId) {
        return categoryAttributeRepository.findByCategoryIdOrderByPositionAsc(categoryId);
    }

    /**
     * The registry's read by the stable API-facing category CODE — the
     * join the public category surface composes (keyed by the code V70
     * keeps unique over the live rows).
     */
    @Transactional(readOnly = true)
    public List<CategoryAttribute> byCategoryCode(String categoryCode) {
        return categoryAttributeRepository.findByCategoryCodeOrderByPositionAsc(categoryCode);
    }

    /**
     * The administrative registration: one attribute definition on one
     * category. The category must exist (the FK's own law, surfaced
     * loudly); the (category, code) identity must be free (the unique
     * key's read form). Position auto-allocates as max+1 — under the
     * registry's advisory lock, so concurrent registrations for one
     * category never read the same maximum (the W1 r9 measured shape).
     */
    @Observed(name = "catalog.category-attributes.register")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public CategoryAttribute register(UUID categoryId, String code, String labelEn,
                                       String labelAr, CategoryAttributeType valueType) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Category not found: " + categoryId));
        categoryAttributeRepository.lockRegistryWrites(category.getId().toString());
        if (categoryAttributeRepository.findByCategoryIdAndCode(categoryId, code).isPresent()) {
            throw new org.springframework.dao.DataIntegrityViolationException(
                    "Attribute code already registered for this category: " + code);
        }
        int nextPosition = categoryAttributeRepository.findMaxPositionByCategoryId(categoryId)
                .orElse(-1) + 1;
        return categoryAttributeRepository.save(
                CategoryAttribute.create(categoryId, code, labelEn, labelAr, valueType, nextPosition));
    }

    /**
     * The administrative amendment: labels, type and position replace
     * (PUT semantics); the identity pair (category, code) is immutable —
     * a re-keyed attribute is a new registration. The requested position
     * must not collide with another live attribute of the same category
     * (greptile W2 round, adopted from the root): the registry's reads
     * sort by position alone, so a duplicate position would leave the
     * display order undefined — the loud rejection teaches the caller
     * instead (the addArea duplicate's own exception family).
     */
    @Observed(name = "catalog.category-attributes.update")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public CategoryAttribute update(UUID attributeId, String labelEn, String labelAr,
                                     CategoryAttributeType valueType, int position) {
        CategoryAttribute attribute = owned(attributeId);
        // The registry's advisory lock FIRST (see register): the collision
        // check below is only race-free against concurrent same-category
        // writes when both hold the lock.
        categoryAttributeRepository.lockRegistryWrites(attribute.getCategoryId().toString());
        if (position != attribute.getPosition()
                && categoryAttributeRepository.existsByCategoryIdAndPositionAndIdNot(
                        attribute.getCategoryId(), position, attribute.getId())) {
            throw new org.springframework.dao.DataIntegrityViolationException(
                    "Position already held by another attribute of this category: " + position);
        }
        attribute.update(labelEn, labelAr, valueType, position);
        return categoryAttributeRepository.save(attribute);
    }

    /** The administrative withdrawal (soft delete — the Envers trail keeps the history). */
    @Observed(name = "catalog.category-attributes.remove")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void remove(UUID attributeId) {
        categoryAttributeRepository.delete(owned(attributeId));
    }

    private CategoryAttribute owned(UUID attributeId) {
        return categoryAttributeRepository.findById(attributeId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Category attribute not found: " + attributeId));
    }

    // -- request shapes -------------------------------------------------------

    /** One registration's shape: the identity pair + the display fields + the value type. */
    public record RegistrationRequest(String code, String labelEn, String labelAr,
                                       CategoryAttributeType valueType) {
    }

    /** One amendment's shape: the replaceable fields (identity immutable). */
    public record AmendmentRequest(String labelEn, String labelAr,
                                    CategoryAttributeType valueType, int position) {
    }
}
