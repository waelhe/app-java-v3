package com.marketplace.identity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AuthAuditLogRepository extends JpaRepository<AuthAuditLog, UUID> {

    Page<AuthAuditLog> findByUsernameOrderByCreatedAtDesc(String username, Pageable pageable);

    Page<AuthAuditLog> findByEventTypeOrderByCreatedAtDesc(AuthEventType eventType, Pageable pageable);
}
