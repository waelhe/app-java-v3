package com.marketplace.provider;

import com.marketplace.shared.api.ApiConstants;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.ADMIN + "/providers", version = "1.0")
public class AdminProviderController {
    private final ProviderService providerService;

    public AdminProviderController(ProviderService providerService) {
        this.providerService = providerService;
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProviderResponse> verify(@PathVariable UUID id) {
        return ResponseEntity.ok(ProviderResponse.from(providerService.verify(id)));
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ProviderResponse> suspend(@PathVariable UUID id) {
        return ResponseEntity.ok(ProviderResponse.from(providerService.suspend(id)));
    }
}
