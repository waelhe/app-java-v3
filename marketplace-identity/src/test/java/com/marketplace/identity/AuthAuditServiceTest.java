package com.marketplace.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthAuditServiceTest {

    @Mock private AuthAuditLogRepository auditRepository;

    @InjectMocks private AuthAuditService auditService;

    @Test
    void log_savesAuditEntry() {
        when(auditRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        auditService.log("user@test.com", AuthEventType.LOGIN_SUCCESS, "Login from 1.2.3.4");

        verify(auditRepository).save(any(AuthAuditLog.class));
    }

    @Test
    void findAuditLogsByUsername_returnsPage() {
        String username = "user@test.com";
        AuthAuditLog log = AuthAuditLog.create(username, AuthEventType.LOGIN_SUCCESS, "1.2.3.4", "UA", "details");
        when(auditRepository.findByUsernameOrderByCreatedAtDesc(username, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(log)));

        var result = auditService.findAuditLogsByUsername(username, PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
        assertEquals(username, result.getContent().getFirst().username());
    }

    @Test
    void findAllAuditLogs_returnsPage() {
        AuthAuditLog log = AuthAuditLog.create("user", AuthEventType.REGISTRATION, null, null, "test");
        when(auditRepository.findAll(PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(log)));

        var result = auditService.findAllAuditLogs(PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
    }

    @Test
    void findAuditLogsByEventType_returnsPage() {
        AuthAuditLog log = AuthAuditLog.create("user", AuthEventType.LOGIN_FAILURE, "1.2.3.4", "UA", "fail");
        when(auditRepository.findByEventTypeOrderByCreatedAtDesc(AuthEventType.LOGIN_FAILURE, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(log)));

        var result = auditService.findAuditLogsByEventType(AuthEventType.LOGIN_FAILURE, PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
    }
}
