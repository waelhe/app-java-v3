package com.marketplace.provider;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
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

    public ProviderController(ProviderService providerService, ProviderMapper providerMapper,
                              CurrentUserProvider currentUserProvider) {
        this.providerService = providerService;
        this.providerMapper = providerMapper;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/providers")
    @Operation(summary = "Become a provider", description = "Creates the caller's provider "
            + "profile (host onboarding).")
    public ResponseEntity<ProviderResponse> create(@Valid @RequestBody ProviderRequest request,
                                                   Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(providerMapper.toResponse(
                providerService.create(request.displayName(), request.bio(), userId)));
    }

    @GetMapping("/providers/{id}")
    @Operation(summary = "Get a provider profile", description = "Public provider profile with "
            + "the stored rating average.")
    public ResponseEntity<ProviderResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(providerMapper.toResponse(providerService.getById(id)));
    }

    @PutMapping("/providers/{id}")
    @Operation(summary = "Update my provider profile", description = "Owner-scoped update of "
            + "display name and bio.")
    public ResponseEntity<ProviderResponse> update(@PathVariable UUID id, @Valid @RequestBody ProviderRequest request,
                                                   Authentication authentication) {
        return ResponseEntity.ok(providerMapper.toResponse(
                providerService.update(id, request.displayName(), request.bio(), authentication)));
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
