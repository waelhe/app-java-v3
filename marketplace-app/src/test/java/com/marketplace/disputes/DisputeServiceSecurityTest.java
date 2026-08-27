package com.marketplace.disputes;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@SpringBootTest
@EnableMethodSecurity
@ActiveProfiles("test")
class DisputeServiceSecurityTest {

    @Autowired
    private DisputeService disputeService;

    @Test
    @WithMockUser(roles = "USER")
    void resolve_whenNotAdmin_thenAccessDenied() {
        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> disputeService.resolve(UUID.randomUUID(), null));
    }
}
