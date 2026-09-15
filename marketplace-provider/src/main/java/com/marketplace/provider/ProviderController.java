package com.marketplace.provider;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class ProviderController {

    private final ProviderService providerService;
    private final ProviderMapper providerMapper;
    private final CurrentUserProvider currentUserProvider;
    private final ProviderPublicPageService providerPublicPageService;

    public ProviderController(ProviderService providerService, ProviderMapper providerMapper,
                              CurrentUserProvider currentUserProvider,
                              ProviderPublicPageService providerPublicPageService) {
        this.providerService = providerService;
        this.providerMapper = providerMapper;
        this.currentUserProvider = currentUserProvider;
        this.providerPublicPageService = providerPublicPageService;
    }

    @PostMapping("/providers")
    @Operation(summary = "Become a provider", description = "Creates the caller's provider "
            + "profile (host onboarding). L36: the persona fields (actor type, agency "
            + "name, license number) ride the creation; an omitted actor type is the "
            + "individual default.")
    public ResponseEntity<ProviderResponse> create(@Valid @RequestBody ProviderRequest request,
                                                   Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(providerMapper.toResponse(
                providerService.create(request.displayName(), request.bio(), userId,
                        request.actorType(), request.agencyName(), request.licenseNumber())));
    }

    @GetMapping("/providers/{id}")
    @Operation(summary = "Get a provider profile", description = "Public provider profile with "
            + "the stored rating average.")
    public ResponseEntity<ProviderResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(providerMapper.toResponse(providerService.getById(id)));
    }

    @PutMapping("/providers/{id}")
    @Operation(summary = "Update my provider profile", description = "Owner-scoped update of "
            + "display name, bio and the L36 persona fields. PUT replacement semantics per "
            + "field class: omitted agency name / license number clear them (the bio "
            + "contract); an omitted actor type keeps the stored classification (a "
            + "required classification is never silently reset).")
    public ResponseEntity<ProviderResponse> update(@PathVariable UUID id, @Valid @RequestBody ProviderRequest request,
                                                   Authentication authentication) {
        return ResponseEntity.ok(providerMapper.toResponse(
                providerService.update(id, request.displayName(), request.bio(),
                        request.actorType(), request.agencyName(), request.licenseNumber(),
                        authentication)));
    }

    @GetMapping("/providers/{id}/public")
    @Operation(summary = "Get a provider's public page",
            description = "L36: the agent/office public page — profile with the L36 persona "
                    + "fields, the VERIFIED badge status, the rating block (fresh aggregate) "
                    + "and the ACTIVE listings page. Non-VERIFIED profiles get an empty "
                    + "listings block (a suspended broker's inventory is hidden on his "
                    + "page); the response carries no private contact data.")
    public ResponseEntity<ProviderPublicPageResponse> getPublicPage(@PathVariable UUID id,
                                                                    Pageable pageable) {
        return ResponseEntity.ok(providerPublicPageService.getPublicPage(id, pageable));
    }

    @PostMapping("/admin/providers/{id}/verify")
    public ResponseEntity<ProviderResponse> verify(@PathVariable UUID id) {
        return ResponseEntity.ok(providerMapper.toResponse(providerService.verify(id)));
    }

    @PostMapping("/admin/providers/{id}/suspend")
    public ResponseEntity<ProviderResponse> suspend(@PathVariable UUID id) {
        return ResponseEntity.ok(providerMapper.toResponse(providerService.suspend(id)));
    }
}
