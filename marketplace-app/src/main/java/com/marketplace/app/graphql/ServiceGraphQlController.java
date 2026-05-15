package com.marketplace.app.graphql;

import com.marketplace.catalog.CatalogService;
import com.marketplace.catalog.ProviderListing;
import com.marketplace.shared.security.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;

@Controller
public class ServiceGraphQlController {

    private final CatalogService catalogService;
    private final CurrentUserProvider currentUserProvider;
    private final ServiceMapper serviceMapper;

    public ServiceGraphQlController(CatalogService catalogService,
                                    CurrentUserProvider currentUserProvider,
                                    ServiceMapper serviceMapper) {
        this.catalogService = catalogService;
        this.currentUserProvider = currentUserProvider;
        this.serviceMapper = serviceMapper;
    }

    @QueryMapping
    public ServiceResponse service(@Argument UUID id) {
        return serviceMapper.toResponse(catalogService.getById(id));
    }

    @QueryMapping
    public List<ServiceResponse> services() {
        return catalogService.findAll(Pageable.unpaged()).getContent().stream()
                .map(serviceMapper::toResponse)
                .toList();
    }

    @MutationMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ServiceResponse createService(@Argument @Valid ServiceInput input,
                                         Authentication authentication) {
        UUID providerId = currentUserProvider.getCurrentUserId(authentication);
        ProviderListing listing = catalogService.create(
                providerId, input.name(), input.description(),
                input.category(), input.priceCents());
        return serviceMapper.toResponse(listing);
    }
}
