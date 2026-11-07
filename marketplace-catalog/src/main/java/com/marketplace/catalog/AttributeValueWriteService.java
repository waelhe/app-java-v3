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
    /** The live-variant references the option-delete guard reads (the #808 round). */
    private final ProductVariantAttributeValueRepository variantAttributeValues;
    /** The live-PRODUCT references the option-delete guard reads (the #808 round-3). */
    private final ProductAttributeValueRepository productAttributeValues;

    public AttributeValueWriteService(CatalogAttributeOptionRepository attributeOptionRepository,
                                      CatalogAttributeDefinitionRepository attributeDefinitionRepository,
                                      ProductVariantAttributeValueRepository variantAttributeValues,
                                      ProductAttributeValueRepository productAttributeValues) {
        this.attributeOptionRepository = attributeOptionRepository;
        this.attributeDefinitionRepository = attributeDefinitionRepository;
        this.variantAttributeValues = variantAttributeValues;
        this.productAttributeValues = productAttributeValues;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public AttributeValueMutationResult createAttributeValue(UUID attributeId,
                                                              AttributeValueCreateCommand command) {
        List<AttributeMutationError> errors = new ArrayList<>();
        String name = command.name() == null ? null : command.name().strip();
        String code = command.slug() == null ? null : command.slug().strip().toLowerCase(Locale.ROOT);
        // The 2026-11-06 #808 round review (Minor · Stability &
        // Availability): the append position derives from the locked
        // definition row — two concurrent creates of values under the
        // same attribute serialize on the row and never derive the same
        // append position (the ProductTypeRepository.findByIdForUpdate
        // precedent).
        Optional<CatalogAttributeDefinition> parent = attributeId == null
                ? Optional.empty()
                : attributeDefinitionRepository.findByIdForUpdate(attributeId);
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
        // The 2026-11-05 #808 round (Minor · Functional Correctness): the
        // FIRST position is 1 — the reorder's no-change probe and its
        // compaction are 1-based (i + 1), so the previous 0-based first
        // position made every fresh single-option attribute look
        // "changed" and forced a renumbering write on a true no-op.
        // The 2026-11-07 #808 round-19 review (Minor · Functional
        // Correctness, the overflow family): the REST registerOption
        // surface accepts a client-supplied position with only a
        // @Min(0) guard — a stored Integer.MAX_VALUE wraps the max+1
        // derivation to a negative number and the DB's own CHECK
        // answered an unclassified failure. The shared law answers the
        // classified refusal naming the compaction path (the reorder's
        // dense rewrite), never the raw constraint throw.
        java.util.OptionalInt appendPosition = PositionSequences.appendPosition(
                attributeOptionRepository
                        .findFirstByAttributeDefinitionIdOrderByPositionDesc(attributeId)
                        .map(CatalogAttributeOption::getPosition)
                        .orElse(0));
        if (appendPosition.isEmpty()) {
            return AttributeValueMutationResult.failure(List.of(new AttributeMutationError(
                    "position", AttributeMutationErrorCode.INVALID,
                    "The attribute's option positions are exhausted — reorder its values to compact"
                            + " the positions, then retry the append")));
        }
        CatalogAttributeOption created = attributeOptionRepository.save(
                CatalogAttributeOption.create(attributeId, code, name, null, appendPosition.getAsInt()));
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
        if (parent.isEmpty()) {
            // The 2026-11-06 #808 round review (Minor · Functional
            // Correctness): a concurrently soft-deleted parent attribute
            // used to answer a SUCCESS payload with a null `attribute`
            // (a result-contract violation — the TranslationWriteService
            // precedent answers the classified NOT_FOUND). The parent's
            // absence is the addressed value's own truth: the value's
            // owning attribute no longer resolves.
            return AttributeValueMutationResult.failure(List.of(new AttributeMutationError(
                    "attribute", AttributeMutationErrorCode.NOT_FOUND,
                    "No attribute found for the value")));
        }
        if (command.isEmpty()) {
            return AttributeValueMutationResult.success(
                    Optional.of(parent.get()).map(AttributeValueWriteService::toAttributeView).orElse(null),
                    toValueView(row));
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
                Optional.of(parent.get()).map(AttributeValueWriteService::toAttributeView).orElse(null),
                toValueView(updated));
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
        // The 2026-11-06 #808 round review (Major · Data Integrity &
        // Integration): the parent read takes the definition row's own
        // FOR-UPDATE lock — the delete's existsByOptionId probe and a
        // concurrent variant registration's FOR-SHARE definition read
        // (the ProductVariantService CHOICE leg) serialize on this row,
        // so the probe's zero-references answer is COMMITTED-stable,
        // never a TOCTOU window the registration lands a reference
        // through.
        //
        // The 2026-11-07 #808 round-4 review (Minor · Functional
        // Correctness): a row that answers the locked read EMPTY is the
        // ORPHANED option (its family died mid-race — the attribute
        // delete's children cascade missed this in-flight option). The
        // cleanup still happens (the row goes with this call), but the
        // payload answers the family's own honest absence — the update
        // path's parent-absent precedent — NEVER a partial success with a
        // null attribute projection (the result contract carries the
        // owning attribute's projection on success).
        Optional<CatalogAttributeDefinition> parentRow =
                attributeDefinitionRepository.findByIdForUpdate(row.getAttributeDefinitionId());
        if (parentRow.isEmpty()) {
            attributeOptionRepository.delete(row);
            return AttributeValueMutationResult.failure(List.of(new AttributeMutationError(
                    "attribute", AttributeMutationErrorCode.NOT_FOUND,
                    "The option's owning attribute no longer exists")));
        }
        // The 2026-11-07 #808 round-19 review (Minor · Functional
        // Correctness): the option row re-reads AFTER the definition
        // lock — a concurrent delete of the SAME option committed
        // between the pre-lock load and this lock would otherwise ride
        // the stale managed entity to a fabricated SUCCESS (the
        // soft-delete UPDATE matches zero rows silently). The re-read
        // answers the honest absence — the drop-id/lock/load order the
        // round-13 remove fix established, applied to this delete's
        // own shape.
        if (!attributeOptionRepository.existsById(row.getId())) {
            return AttributeValueMutationResult.failure(List.of(new AttributeMutationError(
                    "id", AttributeMutationErrorCode.NOT_FOUND,
                    "No attribute value found for the id")));
        }
        AttributeView parent = toAttributeView(parentRow.get());
        // The 2026-11-06 #808 round review (Major · Data Integrity &
        // Integration): a variant value referencing the option blocks the
        // soft delete — the read path would keep rendering the option's
        // id while the registry's option list no longer carries it, and
        // every future variant registration against the combination
        // answers the unknown-option refusal (the deleteProductType
        // live-references precedent: the references own their own
        // lifecycle, no cascade).
        if (variantAttributeValues.existsByOptionId(row.getId())) {
            return AttributeValueMutationResult.failure(List.of(new AttributeMutationError(
                    "id", AttributeMutationErrorCode.INVALID,
                    "An attribute value referenced by a live variant cannot be deleted")));
        }
        // The 2026-11-06 #808 round-3 review (Major · Data Integrity &
        // Integration): a live PRODUCT's own value referencing the option
        // blocks the soft delete too — the PRODUCT-scope population's
        // twin of the variant probe (the same FOR-UPDATE pairing on the
        // definition row: the product write path reads it FOR SHARE
        // before saving its value, so this probe's zero answer is
        // COMMITTED-stable). Without this probe the deleted option left
        // the published product's value pointing at a dead option and the
        // publish path's re-validation refused the product forever.
        if (productAttributeValues.existsLiveProductValueForOption(row.getId())) {
            return AttributeValueMutationResult.failure(List.of(new AttributeMutationError(
                    "id", AttributeMutationErrorCode.INVALID,
                    "An attribute value referenced by a live product cannot be deleted")));
        }
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
        // The 2026-11-06 #808 round-3 review (Major · Data Integrity &
        // Integration): the definition locks come BEFORE the reference
        // probes, in ONE deterministic pass — the previous per-id shape
        // probed existsByOptionId FIRST and took the definition lock
        // second, so a concurrent registration's FOR-SHARE read (held to
        // ITS commit) let the probe answer zero while the reference was
        // in flight, and the delete that waited on the lock never
        // re-checked (the single-item leg's lock-first order, inverted
        // here by accident). The two-pass shape resolves every known
        // option, takes each DISTINCT definition row's FOR-UPDATE lock in
        // sorted id order (two concurrent bulk deletes naming overlapping
        // families in different orders can never deadlock), and only then
        // probes and deletes — the probe's verdict is COMMITTED-stable
        // for the whole batch.
        java.util.Map<UUID, CatalogAttributeOption> addressedById = new java.util.LinkedHashMap<>();
        for (UUID id : ids) {
            // Unknown or null ids are skipped — the honest affected count
            // answers what the registry itself deleted (never a fabricated
            // count, never an error entry for an id the registry never
            // knew).
            if (id == null) {
                continue;
            }
            attributeOptionRepository.findById(id)
                    .ifPresent(option -> addressedById.put(id, option));
        }
        if (addressedById.isEmpty()) {
            return 0;
        }
        for (UUID definitionId : addressedById.values().stream()
                .map(CatalogAttributeOption::getAttributeDefinitionId)
                .distinct().sorted().toList()) {
            // A soft-deleted definition answers no row — no lock is taken
            // and none is needed: no registration (variant or product)
            // can reference a dead family's option (the single leg's own
            // law, carried by the batch's shared pass).
            attributeDefinitionRepository.findByIdForUpdate(definitionId);
        }
        int deleted = 0;
        for (CatalogAttributeOption option : addressedById.values()) {
            // The 2026-11-07 #808 round-19 review (Minor · Functional
            // Correctness): the same post-lock re-read in bulk form — a
            // concurrent delete that committed while THIS batch waited
            // on the definition locks leaves the pre-lock resolved row
            // stale; the honest count skips it (never a fabricated
            // delete of an already-absent row).
            if (!attributeOptionRepository.existsById(option.getId())) {
                continue;
            }
            // Options referenced by live variants OR live products are
            // skipped for the same honest-count reason — the single-item
            // leg's own classified refusals, in bulk form (the 2026-11-06
            // #808 round-3 review: the PRODUCT-scope population joins the
            // variant probe's own verdict).
            if (!variantAttributeValues.existsByOptionId(option.getId())
                    && !productAttributeValues.existsLiveProductValueForOption(option.getId())) {
                attributeOptionRepository.delete(option);
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
