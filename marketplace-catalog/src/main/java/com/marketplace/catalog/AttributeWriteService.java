package com.marketplace.catalog;

import com.marketplace.catalog.spi.AttributeCreateCommand;
import com.marketplace.catalog.spi.AttributeMutationError;
import com.marketplace.catalog.spi.AttributeMutationErrorCode;
import com.marketplace.catalog.spi.AttributeMutationResult;
import com.marketplace.catalog.spi.AttributeUpdateCommand;
import com.marketplace.catalog.spi.AttributeView;
import com.marketplace.catalog.spi.AttributeWriteSpi;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code attributeCreate}, {@code attributeUpdate},
 * {@code attributeDelete} and {@code attributeBulkDelete} mutation
 * roots, the agent-2 packet): the owning use case for the reusable
 * typed attribute collection's ADMIN write surface. These are Saleor
 * MANAGEMENT roots ("Requires one of the following permissions,
 * depending on the attribute type: MANAGE_PRODUCT_TYPES_AND_ATTRIBUTES
 * for PRODUCT_TYPE attributes...") mapped through the platform's
 * ADMIN capability (the same {@code CatalogAttributeService}
 * registerDefinition/updateDefinition precedent the module itself
 * established — the attribute read seam's own RBAC row) and enforced
 * HERE at the owning use-case boundary by Spring Security's method
 * security — never only in a resolver.
 *
 * <p><b>The validation rules are the row's own contracts</b> — the
 * entity factory's own documented shapes: the {@code code} is a
 * lowercase latin slug (the entity's own {@code ^[a-z][a-z0-9-]*$}
 * shape, at most 50 characters), at least one display name must exist
 * (the bilingual row's own at-least-one contract — the create's Saleor
 * {@code name} binds the name_en dimension), and the closed value
 * shape is required (the {@code value_type} NOT NULL column's own
 * law). The service re-checks all three BEFORE the write (the honest
 * precheck over the derived {@code findByCode} lookup) and lets the
 * column's own uniqueness remain the concurrent-write backstop — the
 * same no-TOCTOU stance the category and product-type registries'
 * write services established. Domain validation outcomes answer as
 * result error entries (the Saleor payload-error ADAPT); transport
 * concerns never reach this service.</p>
 *
 * <p><b>Update is partial</b> — {@code null} command fields preserve
 * the column, the Saleor input's omit-to-preserve semantics; an
 * explicit blank name answers the INVALID domain error (the entity's
 * own at-least-one-name law, re-checked over the MERGED display pair).
 * <b>The row's stable public code and value shape are immutable</b>
 * (the entity's own law — "Type and code are stable identity; only
 * display metadata can be amended", no mutating method exists for
 * either): a slug command equal to the row's own code is the
 * idempotent re-send; any DIFFERENT slug answers the classified
 * INVALID domain error — never a silent drop, never an unauthorized
 * move (the Saleor input's sent-field law).
 * <b>Soft delete</b> rides the {@code BaseEntity} {@code @SoftDelete}
 * discipline: {@code repository.delete} converts to the
 * {@code is_deleted} flag, and the deleted row's last-known projection
 * answers in the payload (Saleor's own delete-payload semantics).</p>
 */
@Service
public class AttributeWriteService implements AttributeWriteSpi {

    /**
     * The code shape the entity factory itself pins — a lowercase latin
     * slug (mirrored at the boundary so an invalid shape answers the
     * classified INVALID domain error, never an entity throw).
     */
    static final Pattern CODE_SHAPE = Pattern.compile("^[a-z][a-z0-9-]*$");

    /** The row's own column ceilings (the entity's own length contracts). */
    private static final int CODE_MAX_LENGTH = 50;
    private static final int NAME_MAX_LENGTH = 100;

    private final CatalogAttributeDefinitionRepository attributeDefinitionRepository;

    public AttributeWriteService(CatalogAttributeDefinitionRepository attributeDefinitionRepository) {
        this.attributeDefinitionRepository = attributeDefinitionRepository;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AttributeMutationResult createAttribute(AttributeCreateCommand command) {
        List<AttributeMutationError> errors = new ArrayList<>();
        String name = command.name() == null ? null : command.name().strip();
        String code = command.slug() == null ? null : command.slug().strip().toLowerCase(Locale.ROOT);
        if (name == null || name.isBlank()) {
            errors.add(new AttributeMutationError("name", AttributeMutationErrorCode.REQUIRED,
                    "An attribute name is required"));
        } else if (name.length() > NAME_MAX_LENGTH) {
            errors.add(new AttributeMutationError("name", AttributeMutationErrorCode.INVALID,
                    "An attribute name is at most " + NAME_MAX_LENGTH + " characters"));
        }
        if (code == null || code.isBlank()) {
            errors.add(new AttributeMutationError("slug", AttributeMutationErrorCode.REQUIRED,
                    "An attribute slug is required"));
        } else if (!CODE_SHAPE.matcher(code).matches() || code.length() > CODE_MAX_LENGTH) {
            errors.add(new AttributeMutationError("slug", AttributeMutationErrorCode.INVALID,
                    "An attribute slug is a lowercase latin slug (at most "
                            + CODE_MAX_LENGTH + " characters)"));
        } else if (attributeDefinitionRepository.findByCode(code).isPresent()) {
            errors.add(new AttributeMutationError("slug", AttributeMutationErrorCode.ALREADY_EXISTS,
                    "An attribute with the slug already exists"));
        }
        if (command.valueType() == null) {
            errors.add(new AttributeMutationError("inputType", AttributeMutationErrorCode.REQUIRED,
                    "An attribute input type is required"));
        }
        if (!errors.isEmpty()) {
            return AttributeMutationResult.failure(errors);
        }
        CatalogAttributeDefinition created = attributeDefinitionRepository.save(
                CatalogAttributeDefinition.create(code, name, null, command.valueType(), null));
        return AttributeMutationResult.success(toView(created));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AttributeMutationResult updateAttribute(UUID id, AttributeUpdateCommand command) {
        Optional<CatalogAttributeDefinition> addressed = id == null
                ? Optional.empty()
                : attributeDefinitionRepository.findById(id);
        if (addressed.isEmpty()) {
            return AttributeMutationResult.failure(List.of(new AttributeMutationError(
                    "id", AttributeMutationErrorCode.NOT_FOUND, "No attribute found for the id")));
        }
        if (command.isEmpty()) {
            return AttributeMutationResult.success(toView(addressed.get()));
        }
        List<AttributeMutationError> errors = new ArrayList<>();
        String name = command.name() == null ? null : command.name().strip();
        String code = command.slug() == null ? null : command.slug().strip().toLowerCase(Locale.ROOT);
        if (name != null) {
            if (name.isBlank()) {
                errors.add(new AttributeMutationError("name", AttributeMutationErrorCode.INVALID,
                        "An attribute name cannot be blank"));
            } else if (name.length() > NAME_MAX_LENGTH) {
                errors.add(new AttributeMutationError("name", AttributeMutationErrorCode.INVALID,
                        "An attribute name is at most " + NAME_MAX_LENGTH + " characters"));
            }
        }
        if (code != null) {
            // The entity's own law: "Type and code are stable identity;
            // only display metadata can be amended" (no code-mutating
            // method exists on CatalogAttributeDefinition). A slug equal
            // to the row's own code is the idempotent re-send; any
            // DIFFERENT slug answers the classified INVALID domain
            // error — a sent field is never a silent no-op.
            if (!code.equals(addressed.get().getCode())) {
                errors.add(new AttributeMutationError("slug", AttributeMutationErrorCode.INVALID,
                        "An attribute slug is immutable; it cannot change after creation"));
            } else if (!CODE_SHAPE.matcher(code).matches() || code.length() > CODE_MAX_LENGTH) {
                errors.add(new AttributeMutationError("slug", AttributeMutationErrorCode.INVALID,
                        "An attribute slug is a lowercase latin slug (at most "
                                + CODE_MAX_LENGTH + " characters)"));
            } else {
                attributeDefinitionRepository.findByCode(code)
                        .filter(existing -> !existing.getId().equals(id))
                        .ifPresent(existing -> errors.add(new AttributeMutationError(
                                "slug", AttributeMutationErrorCode.ALREADY_EXISTS,
                                "An attribute with the slug already exists")));
            }
        }
        if (!errors.isEmpty()) {
            return AttributeMutationResult.failure(errors);
        }
        CatalogAttributeDefinition row = addressed.get();
        // The merged display pair rides the entity's own partial-update
        // law (its at-least-one-name validation runs over the MERGED
        // names, never the command's slice alone).
        String mergedNameEn = name == null ? row.getNameEn() : name;
        row.update(mergedNameEn, row.getNameAr(), row.getDescription());
        CatalogAttributeDefinition updated = attributeDefinitionRepository.save(row);
        return AttributeMutationResult.success(toView(updated));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AttributeMutationResult deleteAttribute(UUID id) {
        Optional<CatalogAttributeDefinition> addressed = id == null
                ? Optional.empty()
                : attributeDefinitionRepository.findById(id);
        if (addressed.isEmpty()) {
            return AttributeMutationResult.failure(List.of(new AttributeMutationError(
                    "id", AttributeMutationErrorCode.NOT_FOUND, "No attribute found for the id")));
        }
        CatalogAttributeDefinition row = addressed.get();
        AttributeView lastKnown = toView(row);
        attributeDefinitionRepository.delete(row);
        return AttributeMutationResult.success(lastKnown);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public int deleteAttributes(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (UUID id : ids) {
            // Unknown ids are skipped — the honest affected count answers
            // what the registry itself deleted (never a fabricated count,
            // never an error entry for an id the registry never knew).
            Optional<CatalogAttributeDefinition> addressed = id == null
                    ? Optional.empty()
                    : attributeDefinitionRepository.findById(id);
            if (addressed.isPresent()) {
                attributeDefinitionRepository.delete(addressed.get());
                deleted++;
            }
        }
        return deleted;
    }

    /** The bounded projection — the read service's own rendering rule. */
    private static AttributeView toView(CatalogAttributeDefinition definition) {
        String name = definition.getNameEn() != null ? definition.getNameEn() : definition.getNameAr();
        return new AttributeView(definition.getId(), name, definition.getCode(),
                definition.getValueType());
    }
}
