package com.marketplace.catalog;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.UUID;

/** API for reusable typed attribute definitions and allowed CHOICE values. */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class CatalogAttributeController {
    private final CatalogAttributeService service;
    public CatalogAttributeController(CatalogAttributeService service) { this.service = service; }

    @GetMapping("/store/catalog-attributes")
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    @Operation(summary = "List reusable catalog attribute definitions")
    public ResponseEntity<List<DefinitionResponse>> listDefinitions() {
        return ResponseEntity.ok(service.listDefinitions().stream().map(DefinitionResponse::of).toList());
    }

    @PostMapping("/admin/store/catalog-attributes")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Register a reusable typed catalog attribute")
    public ResponseEntity<DefinitionResponse> register(@Valid @RequestBody RegisterDefinitionRequest request) {
        CatalogAttributeDefinition definition = service.registerDefinition(request.code(), request.nameEn(),
                request.nameAr(), request.valueType(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(DefinitionResponse.of(definition));
    }

    @PutMapping("/admin/store/catalog-attributes/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update attribute display metadata; code and value type remain stable")
    public ResponseEntity<DefinitionResponse> update(@PathVariable UUID id,
            @Valid @RequestBody UpdateDefinitionRequest request) {
        return ResponseEntity.ok(DefinitionResponse.of(
                service.updateDefinition(id, request.nameEn(), request.nameAr(), request.description())));
    }

    @GetMapping("/store/catalog-attributes/{attributeDefinitionId}/options")
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    @Operation(summary = "List allowed values for a CHOICE attribute")
    public ResponseEntity<List<OptionResponse>> listOptions(@PathVariable UUID attributeDefinitionId) {
        return ResponseEntity.ok(service.listOptions(attributeDefinitionId).stream().map(OptionResponse::of).toList());
    }

    @PostMapping("/admin/store/catalog-attributes/{attributeDefinitionId}/options")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Add an allowed value to a CHOICE attribute")
    public ResponseEntity<OptionResponse> registerOption(@PathVariable UUID attributeDefinitionId,
            @Valid @RequestBody RegisterOptionRequest request) {
        CatalogAttributeOption option = service.registerOption(attributeDefinitionId, request.code(),
                request.nameEn(), request.nameAr(), request.position());
        return ResponseEntity.status(HttpStatus.CREATED).body(OptionResponse.of(option));
    }

    @DeleteMapping("/admin/store/catalog-attribute-options/{optionId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Withdraw an allowed attribute value")
    public ResponseEntity<Void> removeOption(@PathVariable UUID optionId) {
        service.removeOption(optionId);
        return ResponseEntity.noContent().build();
    }

    public record RegisterDefinitionRequest(
            @Schema(description = "Stable lowercase code", example = "material")
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]*$") @Size(max = 50) String code,
            @Size(max = 100) String nameEn, @Size(max = 100) String nameAr,
            @NotNull CatalogAttributeType valueType, @Size(max = 1000) String description) {
        @AssertTrue(message = "At least one of nameEn or nameAr is required")
        public boolean isDisplayNamePresent() { return present(nameEn) || present(nameAr); }
    }

    public record UpdateDefinitionRequest(
            @Size(max = 100) String nameEn, @Size(max = 100) String nameAr, @Size(max = 1000) String description) {
        @AssertTrue(message = "At least one of nameEn or nameAr is required")
        public boolean isDisplayNamePresent() { return present(nameEn) || present(nameAr); }
    }

    public record RegisterOptionRequest(
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]*$") @Size(max = 50) String code,
            @Size(max = 100) String nameEn, @Size(max = 100) String nameAr, @Min(0) int position) {
        @AssertTrue(message = "At least one of nameEn or nameAr is required")
        public boolean isDisplayNamePresent() { return present(nameEn) || present(nameAr); }
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }

    public record DefinitionResponse(UUID id, String code, String nameEn, String nameAr,
                                     CatalogAttributeType valueType, String description) {
        static DefinitionResponse of(CatalogAttributeDefinition definition) {
            return new DefinitionResponse(definition.getId(), definition.getCode(), definition.getNameEn(),
                    definition.getNameAr(), definition.getValueType(), definition.getDescription());
        }
    }

    public record OptionResponse(UUID id, UUID attributeDefinitionId, String code, String nameEn,
                                 String nameAr, int position) {
        static OptionResponse of(CatalogAttributeOption option) {
            return new OptionResponse(option.getId(), option.getAttributeDefinitionId(), option.getCode(),
                    option.getNameEn(), option.getNameAr(), option.getPosition());
        }
    }
}
