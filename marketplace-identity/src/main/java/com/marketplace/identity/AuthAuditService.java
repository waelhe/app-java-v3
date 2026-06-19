package com.marketplace.identity;

import com.marketplace.identity.spi.IdentityAdminSpi;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Logs authentication events to the audit log.
 * <p>OWASP recommendation: log all auth events for security monitoring and intrusion detection.
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html">OWASP Authentication Cheat Sheet</a>
 */
@Service
@Transactional
public class AuthAuditService implements IdentityAdminSpi {

    private static final Logger log = LoggerFactory.getLogger(AuthAuditService.class);

    private final AuthAuditLogRepository auditRepository;

    public AuthAuditService(AuthAuditLogRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    /**
     * Logs an authentication event.
     *
     * <p>Uses {@code Propagation.REQUIRES_NEW} so the audit log entry commits in a
     * <strong>separate</strong> transaction that survives the caller's rollback. Without
     * this, {@code LOGIN_FAILURE}, {@code MFA_FAILURE}, and {@code ACCOUNT_LOCKED} events
     * would be rolled back when the caller throws {@code BadRequestException} — the most
     * critical security events would never be persisted. Reference: Spring Framework
     * Reference — Transaction Propagation; OWASP Logging Cheat Sheet — "Log all
     * authentication events (success and failure)".
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void log(String username, AuthEventType eventType, String details) {
        String ipAddress = extractIpAddress();
        String userAgent = extractUserAgent();

        AuthAuditLog auditLog = AuthAuditLog.create(username, eventType, ipAddress, userAgent, details);
        auditRepository.save(auditLog);

        log.info("Auth audit: username={}, event={}, ip={}", username, eventType, ipAddress);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<IdentityAdminSpi.AuditLogEntry> findAuditLogsByUsername(String username, Pageable pageable) {
        return auditRepository.findByUsernameOrderByCreatedAtDesc(username, pageable)
                .map(a -> new IdentityAdminSpi.AuditLogEntry(
                        a.getUsername(), a.getEventType().name(),
                        a.getIpAddress(), a.getDetails(), a.getCreatedAt()
                ));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<IdentityAdminSpi.AuditLogEntry> findAuditLogsByEventType(AuthEventType eventType, Pageable pageable) {
        return auditRepository.findByEventTypeOrderByCreatedAtDesc(eventType, pageable)
                .map(a -> new IdentityAdminSpi.AuditLogEntry(
                        a.getUsername(), a.getEventType().name(),
                        a.getIpAddress(), a.getDetails(), a.getCreatedAt()
                ));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<IdentityAdminSpi.AuditLogEntry> findAllAuditLogs(Pageable pageable) {
        return auditRepository.findAll(pageable)
                .map(a -> new IdentityAdminSpi.AuditLogEntry(
                        a.getUsername(), a.getEventType().name(),
                        a.getIpAddress(), a.getDetails(), a.getCreatedAt()
                ));
    }

    private String extractIpAddress() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                // When forward-headers-strategy=framework is active (set in application-prod.yml),
                // Spring's ForwardedHeaderFilter resolves X-Forwarded-For from the trusted proxy
                // chain into request.getRemoteAddr(). We trust ONLY this resolved value — never
                // the raw X-Forwarded-For header, which is client-controllable and spoofable.
                // Reference: https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc.forwarded-headers
                String remoteAddr = request.getRemoteAddr();
                if (remoteAddr != null && !remoteAddr.isBlank()) {
                    return remoteAddr;
                }
                return "unknown";
            }
        } catch (Exception e) {
            // ignore — not in request context (e.g. scheduled task)
        }
        return null;
    }

    private String extractUserAgent() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                return request.getHeader("User-Agent");
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
}
