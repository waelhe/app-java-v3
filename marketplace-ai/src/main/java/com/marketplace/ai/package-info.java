@org.springframework.modulith.NamedInterface("ai")
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared :: shared-api",
        "shared :: shared-security",
        "shared :: shared-jpa",
        "knowledge :: knowledge"
    }
)
package com.marketplace.ai;
