package com.marketplace.provider;

import java.time.Instant;
import java.util.UUID;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-10-08T00:06:26+0000",
    comments = "version: 1.6.3, compiler: javac, environment: Java 25.0.4.1 (Eclipse Adoptium)"
)
@Component
public class ProviderMapperImpl implements ProviderMapper {

    @Override
    public ProviderResponse toResponse(ProviderProfile profile) {
        if ( profile == null ) {
            return null;
        }

        UUID id = null;
        String displayName = null;
        String bio = null;
        ProviderStatus status = null;
        ProviderActorType actorType = null;
        String agencyName = null;
        String licenseNumber = null;
        Double ratingAverage = null;
        Instant createdAt = null;
        Instant updatedAt = null;

        id = profile.getId();
        displayName = profile.getDisplayName();
        bio = profile.getBio();
        status = profile.getStatus();
        actorType = profile.getActorType();
        agencyName = profile.getAgencyName();
        licenseNumber = profile.getLicenseNumber();
        ratingAverage = profile.getRatingAverage();
        createdAt = profile.getCreatedAt();
        updatedAt = profile.getUpdatedAt();

        ProviderResponse providerResponse = new ProviderResponse( id, displayName, bio, status, actorType, agencyName, licenseNumber, ratingAverage, createdAt, updatedAt );

        return providerResponse;
    }
}
