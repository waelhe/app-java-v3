package com.marketplace.catalog;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * W2 (yelp-level plan §5 — the business page): the category-attribute
 * registry's own HTTP surface — the W1
 * {@code ReviewModerationAdminController} precedent (the module's own
 * controller, the {@code /admin} prefix, the {@code ADMIN} role at the
 * class level) for the administrative half, plus the public read the
 * category surfaces compose.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class CategoryAttributeController {

    private final CategoryAttributeService categoryAttributeService;

    public CategoryAttributeController(CategoryAttributeService categoryAttributeService) {
        this.categoryAttributeService = categoryAttributeService;
    }

    // -- the public read -------------------------------------------------------

    /** One registry row's response shape — the same fields the registry declares. */
    public record CategoryAttributeResponse(
            UUID id,
            UUID categoryId,
            String code,
            String labelEn,
            String labelAr,
            CategoryAttributeType valueType,
            int position
    ) {
        static CategoryAttributeResponse of(CategoryAttribute attribute) {
            return new CategoryAttributeResponse(attribute.getId(), attribute.getCategoryId(),
                    attribute.getCode(), attribute.getLabelEn(), attribute.getLabelAr(),
                    attribute.getValueType(), attribute.getPosition());
        }
    }

    @GetMapping("/categories/{categoryId}/attributes")
    @Operation(summary = "A category's declared attributes",
            description = "W2 (G15): the dynamic per-category attribute registry's public "
                    + "read — one category's attribute definitions in position order. The "
                    + "registry is reference data: what a category's listings carry (wifi, "
                    + "parking, electronic payment), evolving by administrative INSERT, "
                    + "never by migration.")
    public ResponseEntity<List<CategoryAttributeResponse>> byCategory(
            @PathVariable UUID categoryId) {
        return ResponseEntity.ok(categoryAttributeService.byCategoryId(categoryId).stream()
                .map(CategoryAttributeResponse::of).toList());
    }

    // -- the administrative surface --------------------------------------------

    @PostMapping("/admin/categories/{categoryId}/attributes")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Register a category attribute (administrative)",
            description = "W2 (G15): registers one attribute definition on the category — "
                    + "code (the stable API-facing key, unique per category), labels, the "
                    + "closed value-type vocabulary (TEXT/NUMBER/BOOLEAN). Position "
                    + "auto-allocates as max+1. The identity pair (category, code) is "
                    + "immutable once registered.")
    public ResponseEntity<CategoryAttributeResponse> register(
            @PathVariable UUID categoryId,
            @Valid @RequestBody CategoryAttributeService.RegistrationRequest request) {
        return ResponseEntity.ok(CategoryAttributeResponse.of(
                categoryAttributeService.register(categoryId, request.code(), request.labelEn(),
                        request.labelAr(), request.valueType())));
    }

    @PutMapping("/admin/category-attributes/{attributeId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Amend a category attribute (administrative)",
            description = "W2 (G15): PUT replacement of the replaceable fields — labels, "
                    + "value type, position. The identity pair (category, code) is immutable: "
                    + "a re-keyed attribute is a new registration (the V70 identity rule).")
    public ResponseEntity<CategoryAttributeResponse> update(
            @PathVariable UUID attributeId,
            @Valid @RequestBody CategoryAttributeService.AmendmentRequest request) {
        return ResponseEntity.ok(CategoryAttributeResponse.of(
                categoryAttributeService.update(attributeId, request.labelEn(),
                        request.labelAr(), request.valueType(), request.position())));
    }

    @DeleteMapping("/admin/category-attributes/{attributeId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Withdraw a category attribute (administrative)",
            description = "W2 (G15): soft-deletes the definition — the Envers trail keeps "
                    + "the history; a withdrawn code is free for a future registration "
                    + "(the V70 partial-unique identity shape).")
    public ResponseEntity<Void> remove(@PathVariable UUID attributeId) {
        categoryAttributeService.remove(attributeId);
        return ResponseEntity.noContent().build();
    }
}
