@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared :: shared-api", "shared :: shared-security", "shared :: shared-jpa"}
)
@org.springframework.modulith.NamedInterface("booking-spi")
package com.marketplace.booking;
