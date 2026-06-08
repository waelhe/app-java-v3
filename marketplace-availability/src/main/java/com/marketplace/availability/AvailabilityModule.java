package com.marketplace.availability;

import org.springframework.modulith.ApplicationModule;

@ApplicationModule(displayName = "Availability",
        allowedDependencies = {"shared :: shared-api", "shared :: shared-jpa"})
public class AvailabilityModule {
}
