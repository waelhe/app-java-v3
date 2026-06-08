package com.marketplace.disputes;

import org.springframework.modulith.ApplicationModule;

@ApplicationModule(displayName = "Disputes",
        allowedDependencies = {"shared :: shared-api", "shared :: shared-jpa", "shared :: shared-security"})
public class DisputesModule {
}
