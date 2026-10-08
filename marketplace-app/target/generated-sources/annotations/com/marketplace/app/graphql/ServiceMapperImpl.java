package com.marketplace.app.graphql;

import com.marketplace.shared.api.ProviderListingView;
import java.util.UUID;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-10-08T00:06:52+0000",
    comments = "version: 1.6.3, compiler: javac, environment: Java 25.0.4.1 (Eclipse Adoptium)"
)
@Component
public class ServiceMapperImpl implements ServiceMapper {

    @Override
    public ServiceResponse toResponse(ProviderListingView listing) {
        if ( listing == null ) {
            return null;
        }

        String name = null;
        UUID id = null;
        String description = null;
        String category = null;
        String currency = null;

        name = listing.title();
        id = listing.id();
        description = listing.description();
        category = listing.category();
        currency = listing.currency();

        double price = listing.priceCents() != null ? listing.priceCents().doubleValue() / 100.0 : 0.0;
        String status = "ACTIVE".equals(listing.status()) ? "ACTIVE" : "INACTIVE";

        ServiceResponse serviceResponse = new ServiceResponse( id, name, description, category, price, currency, status );

        return serviceResponse;
    }
}
