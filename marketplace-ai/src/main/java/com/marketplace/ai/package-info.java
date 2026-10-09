@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "shared :: shared-api",
        "shared :: shared-security",
        "shared :: shared-jpa",
        "knowledge"
    }
)
package com.marketplace.ai;
