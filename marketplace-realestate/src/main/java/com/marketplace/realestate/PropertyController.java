package com.marketplace.realestate;

import com.marketplace.shared.api.PropertyDetailsPort.PropertyView;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The listing's property surface (realestate systems plan L31): the PUT is
 * provider-owned (service-level ownership against the listing's provider),
 * the GET is public and rides the listings-browse permitAll surface
 * (`GET /api/v1/listings/**`). Deletion follows the listing — there is no
 * delete endpoint (the plan's lifecycle decision D-R7).
 */
@RestController
@RequestMapping(value = "/api/v1/listings", version = "1.0")
public class PropertyController {

    private final RealestateService realestateService;

    public PropertyController(RealestateService realestateService) {
        this.realestateService = realestateService;
    }

    @PutMapping("/{listingId}/property")
    @Operation(summary = "Create or replace a listing's property details (provider)",
            description = "Full upsert of the real-estate field set (purpose, type, area, rooms, "
                    + "floor, amenities, location, coordinates). The listing must exist and belong "
                    + "to the caller (403 otherwise); the location, when provided, must exist in "
                    + "the geo tree (404 otherwise). Invalid values are rejected before any write.")
    public ResponseEntity<PropertyView> upsert(
            @PathVariable UUID listingId,
            @Valid @RequestBody PropertyDetailsRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(
                realestateService.upsert(listingId, request, authentication));
    }

    @GetMapping("/{listingId}/property")
    @Operation(summary = "A listing's property details (public)",
            description = "Visible exactly with an ACTIVE listing (404 for paused/archived/"
                    + "deleted listings — details follow the listing's lifecycle) and 404 when "
                    + "the listing has no property block.")
    public ResponseEntity<PropertyView> getByListingId(@PathVariable UUID listingId) {
        return ResponseEntity.ok(realestateService.getPublicByListingId(listingId));
    }
}
