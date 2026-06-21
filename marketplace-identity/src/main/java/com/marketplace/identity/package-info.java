@org.springframework.modulith.NamedInterface("identity")
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared :: shared-api", "shared :: shared-security", "shared :: shared-jpa", "shared :: shared-config"}
)
package com.marketplace.identity;
