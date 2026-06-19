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

    @Transactional
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
                // Spring already resolves X-Forwarded-For into request.getRemoteAddr().
                // We no longer trust the raw X-Forwarded-For header directly — that was spoofable
                // because without forward-headers-strategy the header is client-controlled.
                // Spring's ForwardedHeaderFilter validates the trusted-proxy chain before
                // setting the resolved IP. Reference:
                // https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc.forwarded-headers
                String remoteAddr = request.getRemoteAddr();
                if (remoteAddr != null && !remoteAddr.isBlank()) {
                    return remoteAddr;
                }
                String xff = request.getHeader("X-Forwarded-For");
                if (xff != null && !xff.isBlank()) {
                    // Fallback only — should rarely trigger when forward-headers-strategy is active.
                    return xff.split(",")[0].trim();
                }
                return null;
            }
        } catch (Exception e) {
            // ignore — not in request context
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
