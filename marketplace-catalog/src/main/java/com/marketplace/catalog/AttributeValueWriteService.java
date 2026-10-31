package com.marketplace.catalog;

import com.marketplace.catalog.spi.AttributeMutationError;
import com.marketplace.catalog.spi.AttributeMutationErrorCode;
import com.marketplace.catalog.spi.AttributeValueCreateCommand;
import com.marketplace.catalog.spi.AttributeValueMutationResult;
import com.marketplace.catalog.spi.AttributeValueUpdateCommand;
import com.marketplace.catalog.spi.AttributeValueView;
import com.marketplace.catalog.spi.AttributeValueWriteSpi;
import com.marketplace.catalog.spi.AttributeView;
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
 * {@code attributeValueCreate}, {@code attributeValueUpdate},
 * {@code attributeValueDelete} and {@code attributeValueBulkDelete}
 * mutation roots, the agent-2 packet): the owning use case for the
 * CHOICE attribute's registered option values' ADMIN write surface.
 * These are Saleor MANAGEMENT roots (the attribute family's own
 * permission class) mapped through the platform's ADMIN capability —
 * the same {@code CatalogAttributeService.registerOption}/
 * {@code removeOption} precedent the module itself established — and
 * enforced HERE at the owning use-case boundary by Spring Security's
 * method security, never only in a resolver.
 *
 * <p><b>The validation rules are the row's own contracts</b> — the
 * module's own documented laws: option values exist only under
 * CHOICE-typed attributes ({@code CatalogAttributeService}'s own
 * "Allowed values can only be added to CHOICE attributes" rule); the
 * code is a lowercase latin slug unique within the owning attribute
 * (the entity's own shape + the {@code findByCode} uniqueness
 * precheck); at least one display name must exist (the bilingual
 * row's own at-least-one contract — the create's Saleor {@code name}
 * binds the name_en dimension); and the append position is the
 * owner's own max+1 derivation (the reorder root's own seam, never a
 * client-supplied fact). Domain validation outcomes answer as result
 * error entries (the Saleor payload-error ADAPT); transport concerns
 * never reach this service.</p>
 *
 * <p><b>Update is partial</b> — {@code null} command fields preserve
 * the column, the Saleor input's omit-to-preserve semantics; an
 * explicit blank name answers the INVALID domain error (the entity's
 * own at-least-one-name law, re-checked over the MERGED display pair).
 * <b>The row's stable public code is immutable</b> (the entity's own
 * identity law — the code is the option's identity WITHIN the
 * attribute, and no code-mutating method exists): a slug command equal
 * to the row's own code is the idempotent re-send; any DIFFERENT slug
 * answers the classified INVALID domain error — never a silent drop.
 * <b>Soft delete</b> rides the {@code BaseEntity} {@code @SoftDelete}
 * discipline; the deleted row's last-known projection (with the owning
 * attribute's own projection) answers in the payload.</p>
 */
@Service
public class AttributeValueWriteService implements AttributeValueWriteSpi {

    /**
     * The code shape the entity factory itself pins — a lowercase latin
     * slug (mirrored at the boundary so an invalid shape answers the
     * classified INVALID domain error, never an entity throw).
     */
    static final Pattern CODE_SHAPE = Pattern.compile("^[a-z][a-z0-9-]*$");

    /** The row's own column ceilings (the entity's own length contracts). */
    private static final int CODE_MAX_LENGTH = 50;
    private static final int NAME_MAX_LENGTH = 100;

    private final CatalogAttributeOptionRepository attributeOptionRepository;
    private final CatalogAttributeDefinitionRepository attributeDefinitionRepository;

    public AttributeValueWriteService(CatalogAttributeOptionRepository attributeOptionRepository,
                                      CatalogAttributeDefinitionRepository attributeDefinitionRepository) {
        this.attributeOptionRepository = attributeOptionRepository;
        this.attributeDefinitionRepository = attributeDefinitionRepository;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AttributeValueMutationResult createAttributeValue(UUID attributeId,
                                                              AttributeValueCreateCommand command) {
        List<AttributeMutationError> errors = new ArrayList<>();
        String name = command.name() == null ? null : command.name().strip();
        String code = command.slug() == null ? null : command.slug().strip().toLowerCase(Locale.ROOT);
        Optional<CatalogAttributeDefinition> parent = attributeId == null
                ? Optional.empty()
                : attributeDefinitionRepository.findById(attributeId);
        if (parent.isEmpty()) {
            errors.add(new AttributeMutationError("attribute", AttributeMutationErrorCode.NOT_FOUND,
                    "No attribute found for the id"));
        } else if (parent.get().getValueType() != CatalogAttributeType.CHOICE) {
            // The module's own law: option values exist only under
            // CHOICE-typed attributes (registerOption's own rule).
            errors.add(new AttributeMutationError("attribute", AttributeMutationErrorCode.INVALID,
                    "Allowed values can only be added to CHOICE attributes"));
        }
        if (name == null || name.isBlank()) {
            errors.add(new AttributeMutationError("name", AttributeMutationErrorCode.REQUIRED,
                    "An attribute value name is required"));
        } else if (name.length() > NAME_MAX_LENGTH) {
            errors.add(new AttributeMutationError("name", AttributeMutationErrorCode.INVALID,
                    "An attribute value name is at most " + NAME_MAX_LENGTH + " characters"));
        }
        if (code == null || code.isBlank()) {
            errors.add(new AttributeMutationError("slug", AttributeMutationErrorCode.REQUIRED,
                    "An attribute value slug is required"));
        } else if (!CODE_SHAPE.matcher(code).matches() || code.length() > CODE_MAX_LENGTH) {
            errors.add(new AttributeMutationError("slug", AttributeMutationErrorCode.INVALID,
                    "An attribute value slug is a lowercase latin slug (at most "
                            + CODE_MAX_LENGTH + " characters)"));
        } else if (attributeId != null
                && attributeOptionRepository.findByAttributeDefinitionIdAndCode(attributeId, code).isPresent()) {
            errors.add(new AttributeMutationError("slug", AttributeMutationErrorCode.ALREADY_EXISTS,
                    "An attribute value with the slug already exists within the attribute"));
        }
        if (!errors.isEmpty()) {
            return AttributeValueMutationResult.failure(errors);
        }
        // The append position — the owner's own max+1 derivation (never a
        // client-supplied fact; the position-uniqueness column backstop
        // keeps the concurrent-append window honest).
        int appendPosition = attributeOptionRepository
                .findFirstByAttributeDefinitionIdOrderByPositionDesc(attributeId)
                .map(last -> last.getPosition() + 1)
                .orElse(0);
        CatalogAttributeOption created = attributeOptionRepository.save(
                CatalogAttributeOption.create(attributeId, code, name, null, appendPosition));
        return AttributeValueMutationResult.success(toAttributeView(parent.get()), toValueView(created));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AttributeValueMutationResult updateAttributeValue(UUID id, AttributeValueUpdateCommand command) {
        Optional<CatalogAttributeOption> addressed = id == null
                ? Optional.empty()
                : attributeOptionRepository.findById(id);
        if (addressed.isEmpty()) {
            return AttributeValueMutationResult.failure(List.of(new AttributeMutationError(
                    "id", AttributeMutationErrorCode.NOT_FOUND, "No attribute value found for the id")));
        }
        CatalogAttributeOption row = addressed.get();
        Optional<CatalogAttributeDefinition> parent =
                attributeDefinitionRepository.findById(row.getAttributeDefinitionId());
        if (command.isEmpty()) {
            return AttributeValueMutationResult.success(
                    parent.map(AttributeValueWriteService::toAttributeView).orElse(null), toValueView(row));
        }
        List<AttributeMutationError> errors = new ArrayList<>();
        String name = command.name() == null ? null : command.name().strip();
        String code = command.slug() == null ? null : command.slug().strip().toLowerCase(Locale.ROOT);
        if (name != null) {
            if (name.isBlank()) {
                errors.add(new AttributeMutationError("name", AttributeMutationErrorCode.INVALID,
                        "An attribute value name cannot be blank"));
            } else if (name.length() > NAME_MAX_LENGTH) {
                errors.add(new AttributeMutationError("name", AttributeMutationErrorCode.INVALID,
                        "An attribute value name is at most " + NAME_MAX_LENGTH + " characters"));
            }
        }
        if (code != null) {
            // The entity's own identity law: the code is the option's
            // identity within the attribute (no code-mutating method
            // exists on the row). A slug equal to the row's own code is
            // the idempotent re-send; any DIFFERENT slug answers the
            // classified INVALID domain error — never a silent drop.
            if (!code.equals(row.getCode())) {
                errors.add(new AttributeMutationError("slug", AttributeMutationErrorCode.INVALID,
                        "An attribute value slug is immutable; it cannot change after creation"));
            } else if (!CODE_SHAPE.matcher(code).matches() || code.length() > CODE_MAX_LENGTH) {
                errors.add(new AttributeMutationError("slug", AttributeMutationErrorCode.INVALID,
                        "An attribute value slug is a lowercase latin slug (at most "
                                + CODE_MAX_LENGTH + " characters)"));
            } else {
                attributeOptionRepository.findByAttributeDefinitionIdAndCode(row.getAttributeDefinitionId(), code)
                        .filter(existing -> !existing.getId().equals(id))
                        .ifPresent(existing -> errors.add(new AttributeMutationError(
                                "slug", AttributeMutationErrorCode.ALREADY_EXISTS,
                                "An attribute value with the slug already exists within the attribute")));
            }
        }
        if (!errors.isEmpty()) {
            return AttributeValueMutationResult.failure(errors);
        }
        // The merged display pair rides the entity's own partial-update
        // seam (its at-least-one-name validation runs over the MERGED
        // names, never the command's slice alone).
        String mergedNameEn = name == null ? row.getNameEn() : name;
        row.revise(mergedNameEn, row.getNameAr());
        CatalogAttributeOption updated = attributeOptionRepository.save(row);
        return AttributeValueMutationResult.success(
                parent.map(AttributeValueWriteService::toAttributeView).orElse(null), toValueView(updated));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AttributeValueMutationResult deleteAttributeValue(UUID id) {
        Optional<CatalogAttributeOption> addressed = id == null
                ? Optional.empty()
                : attributeOptionRepository.findById(id);
        if (addressed.isEmpty()) {
            return AttributeValueMutationResult.failure(List.of(new AttributeMutationError(
                    "id", AttributeMutationErrorCode.NOT_FOUND, "No attribute value found for the id")));
        }
        CatalogAttributeOption row = addressed.get();
        AttributeValueView lastKnown = toValueView(row);
        AttributeView parent = attributeDefinitionRepository.findById(row.getAttributeDefinitionId())
                .map(AttributeValueWriteService::toAttributeView)
                .orElse(null);
        attributeOptionRepository.delete(row);
        return AttributeValueMutationResult.success(parent, lastKnown);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public int deleteAttributeValues(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (UUID id : ids) {
            // Unknown ids are skipped — the honest affected count answers
            // what the registry itself deleted (never a fabricated count,
            // never an error entry for an id the registry never knew).
            Optional<CatalogAttributeOption> addressed = id == null
                    ? Optional.empty()
                    : attributeOptionRepository.findById(id);
            if (addressed.isPresent()) {
                attributeOptionRepository.delete(addressed.get());
                deleted++;
            }
        }
        return deleted;
    }

    /** The bounded attribute projection — the read service's own rendering rule. */
    private static AttributeView toAttributeView(CatalogAttributeDefinition definition) {
        String name = definition.getNameEn() != null ? definition.getNameEn() : definition.getNameAr();
        return new AttributeView(definition.getId(), name, definition.getCode(),
                definition.getValueType());
    }

    /** The bounded value projection — the row's own rendered facts. */
    private static AttributeValueView toValueView(CatalogAttributeOption option) {
        String name = option.getNameEn() != null ? option.getNameEn() : option.getNameAr();
        return new AttributeValueView(option.getId(), name, option.getCode());
    }
}
