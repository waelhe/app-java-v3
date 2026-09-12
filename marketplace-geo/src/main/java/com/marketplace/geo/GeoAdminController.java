package com.marketplace.geo;

import com.marketplace.shared.api.GeoLookupPort.GeoNode;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin write surface for the tree (realestate systems plan L30). ADMIN
 * is enforced twice — the /api/v1/admin/** security-chain rule (SYSTEM.md
 * §6) and {@code @PreAuthorize} on the service (the house's three-layer
 * authorization; negative 403 tests are mandatory).
 */
/**
 * The class-level ADMIN gate is the house pattern for admin controllers
 * (AdminController — the "four admin controller gates" of the three-layer
 * authorization model); the service-level @PreAuthorize stays as defense
 * in depth.
 */
@RestController
@RequestMapping(value = "/api/v1/admin/geo", version = "1.0")
@PreAuthorize("hasRole('ADMIN')")
public class GeoAdminController {

    private final GeoService geoService;

    public GeoAdminController(GeoService geoService) {
        this.geoService = geoService;
    }

    @PostMapping
    @Operation(summary = "Create a child location (admin)",
            description = "Appends a child under an existing parent; the level is derived "
                    + "from the parent (a skip is impossible by construction). Slug conflicts "
                    + "answer 409.")
    public ResponseEntity<GeoNode> create(@Valid @RequestBody CreateGeoLocationRequest request) {
        GeoNode created = geoService.createChild(
                request.parentId(), request.nameAr(), request.nameEn(), request.slug());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Rename / re-slug a location (admin)",
            description = "Names and slug are amendable; level and parent are immutable. "
                    + "Slug conflicts answer 409.")
    public ResponseEntity<GeoNode> update(@PathVariable UUID id,
                                          @Valid @RequestBody UpdateGeoLocationRequest request) {
        return ResponseEntity.ok(geoService.update(
                id, request.nameAr(), request.nameEn(), request.slug()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a location (admin)",
            description = "Soft-deletes a childless node; a node with children answers 409 "
                    + "(no silent subtree orphaning).")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        geoService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Creation contract: shape constraints only ({@code @NotBlank} +
     * slug pattern) — the hierarchy rule (parent exists, level = parent+1,
     * depth floor) is the entity/service gate, the same layering as
     * {@code ProviderListing.create} (request shape → entity floor → DB
     * CHECK).
     */
    public record CreateGeoLocationRequest(
            UUID parentId,
            @NotBlank String nameAr,
            String nameEn,
            @NotBlank @Pattern(regexp = "[a-z0-9-]{2,120}",
                    message = "slug must be 2-120 lowercase latin letters, digits or dashes")
            String slug
    ) {
    }

    public record UpdateGeoLocationRequest(
            @NotBlank String nameAr,
            String nameEn,
            @NotBlank @Pattern(regexp = "[a-z0-9-]{2,120}",
                    message = "slug must be 2-120 lowercase latin letters, digits or dashes")
            String slug
    ) {
    }
}
