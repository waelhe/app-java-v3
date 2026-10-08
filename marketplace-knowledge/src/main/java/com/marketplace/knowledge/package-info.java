@org.springframework.modulith.NamedInterface("knowledge")
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared :: shared-api", "shared :: shared-security", "shared :: shared-jpa"}
)
package com.marketplace.knowledge;
