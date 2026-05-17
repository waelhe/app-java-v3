package com.marketplace.availability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AvailabilityControllerTest {

    @Mock
    private AvailabilityService availabilityService;

    @InjectMocks
    private AvailabilityController controller;

    @Test
    void createSlotReturnsCreatedSlot() {
        UUID providerId = UUID.randomUUID();
        Instant startsAt = Instant.parse("2026-06-01T09:00:00Z");
        Instant endsAt = Instant.parse("2026-06-01T10:00:00Z");
        AvailabilitySlot slot = AvailabilitySlot.open(providerId, startsAt, endsAt);

        when(availabilityService.createSlot(providerId, startsAt, endsAt)).thenReturn(slot);

        ResponseEntity<AvailabilitySlot> result = controller.createSlot(providerId, startsAt, endsAt);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(slot);
    }

    @Test
    void getSlotsReturnsSlotList() {
        UUID providerId = UUID.randomUUID();
        Instant from = Instant.parse("2026-06-01T00:00:00Z");
        Instant to = Instant.parse("2026-06-30T00:00:00Z");
        List<AvailabilitySlot> slots = List.of(mock(AvailabilitySlot.class));

        when(availabilityService.getSlots(providerId, from, to)).thenReturn(slots);

        ResponseEntity<List<AvailabilitySlot>> result = controller.getSlots(providerId, from, to);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(slots);
    }

    @Test
    void createRuleReturnsCreatedRule() {
        UUID providerId = UUID.randomUUID();
        DayOfWeek dayOfWeek = DayOfWeek.MONDAY;
        LocalTime startTime = LocalTime.of(9, 0);
        LocalTime endTime = LocalTime.of(17, 0);
        ProviderAvailabilityRule rule = ProviderAvailabilityRule.create(providerId, dayOfWeek, startTime, endTime);

        when(availabilityService.createRule(providerId, dayOfWeek, startTime, endTime)).thenReturn(rule);

        ResponseEntity<ProviderAvailabilityRule> result = controller.createRule(providerId, dayOfWeek, startTime, endTime);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(rule);
    }

    @Test
    void createTimeOffReturnsCreatedTimeOff() {
        UUID providerId = UUID.randomUUID();
        Instant startsAt = Instant.parse("2026-07-01T00:00:00Z");
        Instant endsAt = Instant.parse("2026-07-07T00:00:00Z");
        ProviderTimeOff timeOff = ProviderTimeOff.create(providerId, startsAt, endsAt);

        when(availabilityService.createTimeOff(providerId, startsAt, endsAt)).thenReturn(timeOff);

        ResponseEntity<ProviderTimeOff> result = controller.createTimeOff(providerId, startsAt, endsAt);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isSameAs(timeOff);
    }
}
