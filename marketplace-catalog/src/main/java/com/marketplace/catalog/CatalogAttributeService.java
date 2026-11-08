package com.marketplace.catalog;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/** Management of reusable typed catalog attributes and CHOICE options. */
@Service
public class CatalogAttributeService {
    private final CatalogAttributeDefinitionRepository definitions;
    private final CatalogAttributeOptionRepository options;
    /** The live-reference walls the remove law reads (the 2026-11-07 #808 round-20). */
    private final ProductVariantAttributeValueRepository variantValues;
    private final ProductAttributeValueRepository productValues;
    // The 2026-11-08 #808 round-21 review (Minor · Stability &
    // Availability): constructor injection (not @PersistenceContext) so
    // the unit tests can mock the refresh contract — the OrdersService
    // confirmFromPayment precedent.
    private final jakarta.persistence.EntityManager entityManager;

    public CatalogAttributeService(CatalogAttributeDefinitionRepository definitions,
                                   CatalogAttributeOptionRepository options,
                                   ProductVariantAttributeValueRepository variantValues,
                                   ProductAttributeValueRepository productValues,
                                   jakarta.persistence.EntityManager entityManager) {
        this.definitions = definitions;
        this.options = options;
        this.variantValues = variantValues;
        this.productValues = productValues;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public List<CatalogAttributeDefinition> listDefinitions() {
        return definitions.findAllByOrderByCodeAsc();
    }

    @Observed(name = "catalog.attribute-definition.register")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public CatalogAttributeDefinition registerDefinition(String code, String nameEn, String nameAr,
                                                         CatalogAttributeType valueType, String description) {
        CatalogAttributeDefinition candidate = CatalogAttributeDefinition.create(code, nameEn, nameAr, valueType, description);
        definitions.findByCode(candidate.getCode()).ifPresent(existing -> {
            throw new ConflictException("Attribute code '" + candidate.getCode() + "' is already registered");
        });
        return definitions.save(candidate);
    }

    @Observed(name = "catalog.attribute-definition.update")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public CatalogAttributeDefinition updateDefinition(UUID id, String nameEn, String nameAr, String description) {
        CatalogAttributeDefinition definition = findDefinition(id);
        definition.update(nameEn, nameAr, description);
        return definitions.save(definition);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public List<CatalogAttributeOption> listOptions(UUID attributeDefinitionId) {
        findDefinition(attributeDefinitionId);
        return options.findAllByAttributeDefinitionIdOrderByPositionAsc(attributeDefinitionId);
    }

    @Observed(name = "catalog.attribute-option.register")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public CatalogAttributeOption registerOption(UUID attributeDefinitionId, String code,
                                                  String nameEn, String nameAr, int position) {
        // The 2026-11-08 #808 round-21 review (Minor · Data Integrity &
        // Integration): the registration rides the definition's own
        // FOR-UPDATE lock — the unlocked findDefinition let a concurrent
        // deleteAttribute (the locked writer whose children cascade
        // soft-deletes every option) commit between this read and the
        // insert, leaving a LIVE option row bound to a soft-deleted
        // definition (unreachable through the read paths, unremovable
        // through the option-delete guard's parent re-read). Locked
        // first, the pair serializes: the delete's cascade carries THIS
        // insert away, or the delete's verdict was committed first and
        // the fresh locked read answers the honest NOT_FOUND.
        if (attributeDefinitionId == null) {
            throw new ResourceNotFoundException("CatalogAttributeDefinition", null);
        }
        CatalogAttributeDefinition definition = definitions.findByIdForUpdate(attributeDefinitionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CatalogAttributeDefinition", attributeDefinitionId));
        if (definition.getValueType() != CatalogAttributeType.CHOICE) {
            throw new ConflictException("Allowed values can only be added to CHOICE attributes");
        }
        CatalogAttributeOption candidate = CatalogAttributeOption.create(
                attributeDefinitionId, code, nameEn, nameAr, position);
        options.findByAttributeDefinitionIdAndCode(attributeDefinitionId, candidate.getCode()).ifPresent(existing -> {
            throw new ConflictException("Option code '" + candidate.getCode() + "' is already registered");
        });
        if (options.existsByAttributeDefinitionIdAndPosition(attributeDefinitionId, position)) {
            throw new ConflictException("Option position " + position + " is already used");
        }
        return options.save(candidate);
    }

    @Observed(name = "catalog.attribute-option.remove")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void removeOption(UUID optionId) {
        CatalogAttributeOption option = optionId == null ? null
                : options.findById(optionId).orElse(null);
        if (option == null) {
            throw new ResourceNotFoundException("CatalogAttributeOption", optionId);
        }
        // The 2026-11-07 #808 round-20 review (Major · Data Integrity &
        // Integration): the REST remove rides the SAME law the SPI
        // delete owns (deleteAttributeValue) — the definition row's own
        // FOR-UPDATE lock serializes against every reference writer, the
        // live-variant and live-product walls refuse an option the
        // shelf still renders, and the option re-reads AFTER the lock
        // (a concurrent delete of the SAME option never fabricates a
        // success off the stale managed row).
        definitions.findByIdForUpdate(option.getAttributeDefinitionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CatalogAttributeDefinition", option.getAttributeDefinitionId()));
        if (!options.existsById(optionId)) {
            throw new ResourceNotFoundException("CatalogAttributeOption", optionId);
        }
        // The 2026-11-08 #808 round-21 review (Minor · Stability &
        // Availability): the option RE-READS under the lock — it was
        // loaded BEFORE the definition lock, so a translation or reorder
        // that committed while THIS call waited leaves the managed row's
        // @Version stale and the soft delete matches zero rows (the raw
        // OptimisticLockException). refresh(entity, PESSIMISTIC_WRITE)
        // reloads the authoritative row first (the OrdersService
        // confirmFromPayment precedent).
        entityManager.refresh(option, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (variantValues.existsByOptionId(optionId)) {
            throw new ConflictException(
                    "An attribute value referenced by a live variant cannot be deleted");
        }
        if (productValues.existsLiveProductValueForOption(optionId)) {
            throw new ConflictException(
                    "An attribute value referenced by a live product cannot be deleted");
        }
        options.delete(option);
    }

    private CatalogAttributeDefinition findDefinition(UUID id) {
        return definitions.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CatalogAttributeDefinition", id));
    }
}
