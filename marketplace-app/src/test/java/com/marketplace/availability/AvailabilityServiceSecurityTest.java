package com.marketplace.availability;

import com.marketplace.shared.security.AuthHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@EnableMethodSecurity
@ActiveProfiles("test")
class AvailabilityServiceSecurityTest {

    @Autowired
    private AvailabilityService availabilityService;

    @MockitoBean
    private AuthHelper authHelper;

    @Test
    @WithMockUser(roles = "PROVIDER")
    void createSlot_whenNotOwner_thenAccessDenied() {
        UUID providerId = UUID.randomUUID();
        when(authHelper.ownsProvider(any(), any())).thenReturn(false);

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> availabilityService.createSlot(providerId, Instant.now(), Instant.now()));
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void createRule_whenNotOwner_thenAccessDenied() {
        UUID providerId = UUID.randomUUID();
        when(authHelper.ownsProvider(any(), any())).thenReturn(false);

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> availabilityService.createRule(providerId, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0)));
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void createTimeOff_whenNotOwner_thenAccessDenied() {
        UUID providerId = UUID.randomUUID();
        when(authHelper.ownsProvider(any(), any())).thenReturn(false);

        assertThatExceptionOfType(AccessDeniedException.class).isThrownBy(
                () -> availabilityService.createTimeOff(providerId, Instant.now(), Instant.now()));
    }
}
