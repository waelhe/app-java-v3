package com.marketplace.provider;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.PROVIDERS, version = "1.0")
public class ProviderController {

    private final ProviderService providerService;

    public ProviderController(ProviderService providerService) {
        this.providerService = providerService;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<ProviderResponse>> list(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(providerService.list(pageable).map(ProviderResponse::from)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProviderResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ProviderResponse.from(providerService.getById(id)));
    }

    @PostMapping
    public ResponseEntity<ProviderResponse> create(@Valid @RequestBody UpsertProviderRequest request, Authentication authentication) {
        ProviderProfile profile = providerService.create(request.userId(), request.displayName(), request.bio(), request.city(), request.country(), authentication);
        return ResponseEntity.created(URI.create(ApiConstants.PROVIDERS + "/" + profile.getId())).body(ProviderResponse.from(profile));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ProviderResponse> update(@PathVariable UUID id, @Valid @RequestBody UpsertProviderRequest request, Authentication authentication) {
        return ResponseEntity.ok(ProviderResponse.from(providerService.update(id, request.displayName(), request.bio(), request.city(), request.country(), authentication)));
    }

    public record UpsertProviderRequest(
            @NotNull UUID userId,
            @NotBlank @Size(max = 160) String displayName,
            @Size(max = 5000) String bio,
            @Size(max = 120) String city,
            @Size(min = 2, max = 2) String country
    ) {}
}
