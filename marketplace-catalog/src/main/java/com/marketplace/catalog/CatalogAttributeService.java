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

    public CatalogAttributeService(CatalogAttributeDefinitionRepository definitions,
                                   CatalogAttributeOptionRepository options) {
        this.definitions = definitions;
        this.options = options;
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
        CatalogAttributeOption option = options.findById(optionId)
                .orElseThrow(() -> new ResourceNotFoundException("CatalogAttributeOption", optionId));
        options.delete(option);
    }

    private CatalogAttributeDefinition findDefinition(UUID id) {
        return definitions.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("CatalogAttributeDefinition", id));
    }
}
