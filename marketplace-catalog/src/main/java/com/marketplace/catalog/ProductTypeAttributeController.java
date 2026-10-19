package com.marketplace.catalog;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/** Configuration surface that assigns reusable attribute definitions to a goods ProductType. */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class ProductTypeAttributeController {
    private final ProductTypeAttributeService service;
    public ProductTypeAttributeController(ProductTypeAttributeService service) { this.service = service; }

    @GetMapping("/store/product-types/{productTypeId}/attributes")
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    @Operation(summary = "List a product type's assigned attribute schema")
    public ResponseEntity<List<AssignmentResponse>> list(@PathVariable UUID productTypeId) {
        return ResponseEntity.ok(service.list(productTypeId).stream().map(AssignmentResponse::of).toList());
    }

    @PostMapping("/admin/store/product-types/{productTypeId}/attributes")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Assign a reusable attribute to a product type",
            description = "VARIANT_SELECTION requires a CHOICE attribute with at least two allowed options.")
    public ResponseEntity<AssignmentResponse> assign(@PathVariable UUID productTypeId,
            @Valid @RequestBody AssignAttributeRequest request) {
        ProductTypeAttributeAssignment assignment = service.assign(productTypeId, request.attributeDefinitionId(),
                request.scope(), request.required(), request.position());
        return ResponseEntity.status(HttpStatus.CREATED).body(AssignmentResponse.of(assignment));
    }

    @DeleteMapping("/admin/store/product-type-attributes/{assignmentId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Remove an attribute assignment from a product type")
    public ResponseEntity<Void> remove(@PathVariable UUID assignmentId) {
        service.remove(assignmentId);
        return ResponseEntity.noContent().build();
    }

    public record AssignAttributeRequest(@NotNull UUID attributeDefinitionId,
            @NotNull ProductTypeAttributeScope scope, boolean required, @Min(0) int position) { }

    public record AssignmentResponse(UUID id, UUID productTypeId, UUID attributeDefinitionId,
            ProductTypeAttributeScope scope, boolean required, int position) {
        static AssignmentResponse of(ProductTypeAttributeAssignment assignment) {
            return new AssignmentResponse(assignment.getId(), assignment.getProductTypeId(),
                    assignment.getAttributeDefinitionId(), assignment.getScope(),
                    assignment.isRequired(), assignment.getPosition());
        }
    }
}
