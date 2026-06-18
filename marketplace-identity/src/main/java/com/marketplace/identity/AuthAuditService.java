package com.marketplace.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class AuthAuditService {

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

    private String extractIpAddress() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                String xff = request.getHeader("X-Forwarded-For");
                if (xff != null && !xff.isEmpty()) {
                    return xff.split(",")[0].trim();
                }
                return request.getRemoteAddr();
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
