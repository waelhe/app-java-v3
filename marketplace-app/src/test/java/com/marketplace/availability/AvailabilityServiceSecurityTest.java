package com.marketplace.availability;

import com.marketplace.shared.security.AuthHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { AvailabilityService.class })
@EnableMethodSecurity(proxyTargetClass = true)
class AvailabilityServiceSecurityTest {

    @Autowired
    private AvailabilityService availabilityService;

    @MockitoBean
    private AvailabilitySlotRepository repository;

    @MockitoBean
    private ProviderAvailabilityRuleRepository ruleRepository;

    @MockitoBean
    private ProviderTimeOffRepository timeOffRepository;

    @MockitoBean(name = "authHelper")
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
