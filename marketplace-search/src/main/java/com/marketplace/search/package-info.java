@org.springframework.modulith.NamedInterface("search")
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared :: shared-api",
        "shared :: shared-security",
        "shared :: shared-jpa"
    }
)
package com.marketplace.search;
