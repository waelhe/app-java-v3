package com.marketplace.notifications;

import org.springframework.modulith.ApplicationModule;

@ApplicationModule(displayName = "Notifications",
        allowedDependencies = {"shared :: shared-api", "shared :: shared-jpa", "shared :: shared-security", "shared :: shared-email"})
public class NotificationsModule {
}
