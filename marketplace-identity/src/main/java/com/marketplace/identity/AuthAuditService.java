package com.marketplace.identity;

import com.marketplace.identity.spi.IdentityAdminSpi;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
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
     * would be rolled back when the caller throws {@code BadRequestException} -- the most
     * critical security events would never be persisted. Reference: Spring Framework
     * Reference -- Transaction Propagation; OWASP Logging Cheat Sheet -- "Log all
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

    /**
     * Extracts the client IP address from the current request, if any.
     *
     * <p>Uses Java {@code instanceof} pattern matching (JEP 394, Java 16+) to safely narrow
     * the {@link RequestAttributes} to {@link ServletRequestAttributes} without an explicit
     * cast and without a broad {@code catch (Exception)} block. When no request is bound
     * to the current thread (e.g. scheduled tasks, async), {@code getRequestAttributes()}
     * returns {@code null} and this method returns {@code null}.
     *
     * <p>When {@code server.forward-headers-strategy=framework} is active (set in
     * application-prod.yml), Spring's {@code ForwardedHeaderFilter} resolves
     * {@code X-Forwarded-For} from the trusted proxy chain into {@code request.getRemoteAddr()}.
     * We trust ONLY this resolved value -- never the raw {@code X-Forwarded-For} header,
     * which is client-controllable and spoofable.
     * Reference: https://docs.spring.io/spring-boot/reference/web/servlet.html#web.servlet.spring-mvc.forwarded-headers
     */
    private String extractIpAddress() {
        RequestAttributes rawAttrs = RequestContextHolder.getRequestAttributes();
        if (rawAttrs instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            String remoteAddr = request.getRemoteAddr();
            if (remoteAddr != null && !remoteAddr.isBlank()) {
                return remoteAddr;
            }
            return "unknown";
        }
        return null;
    }

    /**
     * Extracts the User-Agent header from the current request, if any.
     *
     * <p>Uses the same {@code instanceof} pattern-matching approach as {@link #extractIpAddress()}
     * -- no cast, no broad {@code catch (Exception)}.
     */
    private String extractUserAgent() {
        RequestAttributes rawAttrs = RequestContextHolder.getRequestAttributes();
        if (rawAttrs instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getHeader("User-Agent");
        }
        return null;
    }
}
