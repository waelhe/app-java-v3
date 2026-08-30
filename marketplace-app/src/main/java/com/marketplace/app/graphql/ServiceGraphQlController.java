package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.shared.api.ProviderListingView;
import com.marketplace.shared.security.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;

@Controller
public class ServiceGraphQlController {

    private final CatalogSpi catalogSpi;
    private final CurrentUserProvider currentUserProvider;
    private final ServiceMapper serviceMapper;

    public ServiceGraphQlController(CatalogSpi catalogSpi,
                                    CurrentUserProvider currentUserProvider,
                                    ServiceMapper serviceMapper) {
        this.catalogSpi = catalogSpi;
        this.currentUserProvider = currentUserProvider;
        this.serviceMapper = serviceMapper;
    }

    @QueryMapping
    public ServiceResponse service(@Argument UUID id) {
        return serviceMapper.toResponse(catalogSpi.getActiveById(id));
    }

    @QueryMapping
    public List<ServiceResponse> services() {
        return catalogSpi.findAll(Pageable.unpaged()).getContent().stream()
                .map(serviceMapper::toResponse)
                .toList();
    }

    @MutationMapping
    public ServiceResponse createService(@Argument @Valid ServiceInput input,
                                         Authentication authentication) {
        UUID providerId = currentUserProvider.getCurrentUserId(authentication);
        ProviderListingView listing = catalogSpi.create(
                providerId, input.name(), input.description(),
                input.category(), input.priceCents());
        return serviceMapper.toResponse(listing);
    }
}
