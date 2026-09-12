@org.springframework.modulith.NamedInterface("geo")
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared :: shared-api", "shared :: shared-security", "shared :: shared-jpa"}
)
package com.marketplace.geo;
