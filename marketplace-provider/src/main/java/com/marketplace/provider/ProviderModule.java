package com.marketplace.provider;

import org.springframework.modulith.ApplicationModule;

@ApplicationModule(displayName = "Provider",
        allowedDependencies = {"shared :: shared-api", "shared :: shared-jpa", "shared :: shared-security"})
public class ProviderModule {
}
