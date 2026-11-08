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
        CatalogAttributeDefinition definition = findDefinition(attributeDefinitionId);
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
