@org.springframework.modulith.NamedInterface("catalog")
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared :: shared-api", "shared :: shared-security", "shared :: shared-jpa", "shared :: shared-cache"}
)
package com.marketplace.catalog;
