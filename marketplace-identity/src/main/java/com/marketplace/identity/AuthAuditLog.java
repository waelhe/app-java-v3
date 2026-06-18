package com.marketplace.identity;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * Audit log for authentication events.
 * <p>Follows OWASP Authentication Cheat Sheet — log all auth events for security monitoring.
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html">OWASP Authentication Cheat Sheet</a>
 */
@Entity
@Table(name = "auth_audit_log")
@Audited
public class AuthAuditLog extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "username", nullable = false, length = 320)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private AuthEventType eventType;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    protected AuthAuditLog() {
    }

    private AuthAuditLog(UUID id, String username, AuthEventType eventType,
                          String ipAddress, String userAgent, String details) {
        this.id = id;
        this.username = username;
        this.eventType = eventType;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.details = details;
    }

    public static AuthAuditLog create(String username, AuthEventType eventType,
                                       String ipAddress, String userAgent, String details) {
        return new AuthAuditLog(UUID.randomUUID(), username, eventType, ipAddress, userAgent, details);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public AuthEventType getEventType() {
        return eventType;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getDetails() {
        return details;
    }
}
