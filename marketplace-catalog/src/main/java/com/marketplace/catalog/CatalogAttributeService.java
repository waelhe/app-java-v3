package com.marketplace.catalog;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Management of reusable typed catalog attributes and CHOICE options. */
@Service
public class CatalogAttributeService {
    private final CatalogAttributeDefinitionRepository definitions;
    private final CatalogAttributeOptionRepository options;
    /** The live-reference walls the remove law reads (the 2026-11-07 #808 round-20). */
    private final ProductVariantAttributeValueRepository variantValues;
    private final ProductAttributeValueRepository productValues;

    public CatalogAttributeService(CatalogAttributeDefinitionRepository definitions,
                                   CatalogAttributeOptionRepository options,
                                   ProductVariantAttributeValueRepository variantValues,
                                   ProductAttributeValueRepository productValues) {
        this.definitions = definitions;
        this.options = options;
        this.variantValues = variantValues;
        this.productValues = productValues;
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
        // The 2026-11-09 #808 round-26 review (Minor · Stability &
        // Availability): the drop/lock/load order — the DEFINITION-ID
        // projection resolves the family without managing the option (the
        // round-13 law), the family's FOR-UPDATE lock serializes against
        // every reference writer, and the option's OWN locked load
        // (findByIdForUpdate) answers the authoritative row: a vanished
        // option answers the empty Optional, never the refresh's
        // EntityNotFoundException (which the installed Hibernate 7.4.13
        // converts with session.markForRollbackOnly — the round-26
        // poison), so the classified NOT_FOUND rides a transaction that
        // can still commit.
        Optional<UUID> familyId = optionId == null
                ? Optional.empty() : options.findDefinitionIdById(optionId);
        if (familyId.isEmpty()) {
            throw new ResourceNotFoundException("CatalogAttributeOption", optionId);
        }
        // The 2026-11-07 #808 round-20 review (Major · Data Integrity &
        // Integration): the REST remove rides the SAME law the SPI
        // delete owns (deleteAttributeValue) — the definition row's own
        // FOR-UPDATE lock serializes against every reference writer, and
        // a dead family's option is the orphaned cleanup the SPI leg
        // answers with the family's own honest absence.
        definitions.findByIdForUpdate(familyId.get())
                .orElseThrow(() -> new ResourceNotFoundException("CatalogAttributeDefinition", familyId.get()));
        // The 2026-11-08 #808 round-21 review (Minor · Stability &
        // Availability), re-proven on the round-26 shape: the option
        // RE-READS under its own row lock — the load IS the
        // authoritative re-read (the OrdersService confirmFromPayment
        // precedent's intent, without the refresh's poisoned exception
        // contract).
        CatalogAttributeOption option = options.findByIdForUpdate(optionId)
                .orElseThrow(() -> new ResourceNotFoundException("CatalogAttributeOption", optionId));
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
