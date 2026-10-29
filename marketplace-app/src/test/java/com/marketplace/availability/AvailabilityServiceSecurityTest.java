package com.marketplace.availability;

import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.security.AuthHelper;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { AvailabilityService.class, AvailabilityConfig.class })
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

    /**
     * CodeRabbit round 1 adoption on PR #471: the daily generation unit is a
     * separate bean (REQUIRES_NEW through the proxy) — AvailabilityService's
     * constructor now takes it. This slice tests the @PreAuthorize surface of
     * createSlot/createRule/createTimeOff only, so the generator rides as a
     * mock bean.
     */
    @MockitoBean
    private AvailabilitySlotGenerator slotGenerator;

    @MockitoBean(name = "authHelper")
    private AuthHelper authHelper;

    /**
     * Part-3's ownership read (1b6d1a1b): the service now resolves the
     * provider through the shared port before answering a slot/rule/time-off
     * write, and the write itself requires a VERIFIED profile (the
     * eligibility gate the same review round added). The slice asserts the
     * @PreAuthorize surface, so the port answers the eligibility fact every
     * authorized call needs; the denial tests never reach it (the method
     * interceptor throws first).
     */
    @MockitoBean
    private com.marketplace.shared.api.ProviderLookupPort providerLookupPort;

    @BeforeEach
    void theProviderIsEligible() {
        when(providerLookupPort.findByUserId(any())).thenAnswer(invocation ->
                Optional.of(new ProviderSummary(
                        UUID.randomUUID(), "Provider", "VERIFIED", invocation.getArgument(0))));
    }

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

    @Test
    @WithMockUser(roles = "PROVIDER")
    void createSlot_whenOwner_thenInvokes() {
        UUID providerId = UUID.randomUUID();
        when(authHelper.ownsProvider(any(), any())).thenReturn(true);
        when(repository.save(any(AvailabilitySlot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AvailabilitySlotResponse result = availabilityService.createSlot(providerId, Instant.now(), Instant.now());

        assertThat(result.providerId()).isEqualTo(providerId);
        verify(repository).save(any(AvailabilitySlot.class));
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void createRule_whenOwner_thenInvokes() {
        UUID providerId = UUID.randomUUID();
        when(authHelper.ownsProvider(any(), any())).thenReturn(true);
        when(ruleRepository.save(any(ProviderAvailabilityRule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProviderAvailabilityRuleResponse result = availabilityService.createRule(
                providerId, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0));

        assertThat(result.providerId()).isEqualTo(providerId);
        verify(ruleRepository).save(any(ProviderAvailabilityRule.class));
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void createTimeOff_whenOwner_thenInvokes() {
        UUID providerId = UUID.randomUUID();
        when(authHelper.ownsProvider(any(), any())).thenReturn(true);
        when(timeOffRepository.save(any(ProviderTimeOff.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProviderTimeOffResponse result = availabilityService.createTimeOff(providerId, Instant.now(), Instant.now());

        assertThat(result.id()).isNotNull();
        verify(timeOffRepository).save(any(ProviderTimeOff.class));
    }
}
