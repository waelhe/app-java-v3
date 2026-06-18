package com.marketplace.identity.spi;

import com.marketplace.identity.AuthEventType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.NamedInterface;

/**
 * SPI for admin access to identity/audit operations.
 */
@NamedInterface("identity-admin-spi")
public interface IdentityAdminSpi {

    /**
     * Returns audit log entries for a specific user.
     */
    Page<AuditLogEntry> findAuditLogsByUsername(String username, Pageable pageable);

    /**
     * Returns audit log entries by event type.
     */
    Page<AuditLogEntry> findAuditLogsByEventType(AuthEventType eventType, Pageable pageable);

    /**
     * Returns all audit log entries (paginated).
     */
    Page<AuditLogEntry> findAllAuditLogs(Pageable pageable);

    record AuditLogEntry(
            String username,
            String eventType,
            String ipAddress,
            String details,
            java.time.Instant createdAt
    ) {
    }
}
