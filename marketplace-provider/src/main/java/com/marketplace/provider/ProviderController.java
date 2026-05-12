package com.marketplace.provider;

import com.marketplace.shared.api.ApiConstants;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class ProviderController {

    private final ProviderService providerService;

    public ProviderController(ProviderService providerService) {
        this.providerService = providerService;
    }

    @PostMapping("/providers")
    public ResponseEntity<ProviderResponse> create(@Valid @RequestBody ProviderRequest request) {
        return ResponseEntity.ok(ProviderResponse.from(providerService.create(request.displayName(), request.bio())));
    }

    @GetMapping("/providers/{id}")
    public ResponseEntity<ProviderResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ProviderResponse.from(providerService.getById(id)));
    }

    @PutMapping("/providers/{id}")
    public ResponseEntity<ProviderResponse> update(@PathVariable UUID id, @Valid @RequestBody ProviderRequest request) {
        return ResponseEntity.ok(ProviderResponse.from(providerService.update(id, request.displayName(), request.bio())));
    }

    @PostMapping("/admin/providers/{id}/verify")
    public ResponseEntity<ProviderResponse> verify(@PathVariable UUID id) {
        return ResponseEntity.ok(ProviderResponse.from(providerService.verify(id)));
    }

    @PostMapping("/admin/providers/{id}/suspend")
    public ResponseEntity<ProviderResponse> suspend(@PathVariable UUID id) {
        return ResponseEntity.ok(ProviderResponse.from(providerService.suspend(id)));
    }
}
