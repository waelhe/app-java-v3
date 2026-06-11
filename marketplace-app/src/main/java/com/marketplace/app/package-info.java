@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared :: shared-api",
        "catalog :: catalog-api",
        "messaging :: messaging-api"
    }
)
package com.marketplace.app;
