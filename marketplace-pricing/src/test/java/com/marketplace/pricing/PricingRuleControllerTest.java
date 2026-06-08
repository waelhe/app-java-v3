package com.marketplace.pricing;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PricingRuleControllerTest {

    private final PricingService pricingService = mock(PricingService.class);
    private final PricingRuleController controller = new PricingRuleController(pricingService);

    @Test
    void listRules_returnsAll() {
        var rule = PricingRule.create("Test", null, BigDecimal.ZERO, BigDecimal.ZERO);
        when(pricingService.listRules()).thenReturn(List.of(rule));

        var result = controller.listRules();

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(1, result.getBody().size());
    }

    @Test
    void createRule_returnsCreated() {
        var request = new PricingRuleController.CreateRuleRequest(
                "New Rule", "services", new BigDecimal("0.1500"), new BigDecimal("0.0500")
        );
        var saved = PricingRule.create("New Rule", "services",
                new BigDecimal("0.1500"), new BigDecimal("0.0500"));
        when(pricingService.createRule("New Rule", "services",
                new BigDecimal("0.1500"), new BigDecimal("0.0500"))).thenReturn(saved);

        var result = controller.createRule(request);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertNotNull(result.getBody().getId());
        assertEquals("New Rule", result.getBody().getName());
    }

    @Test
    void activateRule_returnsOk() {
        UUID id = UUID.randomUUID();
        var rule = PricingRule.create("Test", null, BigDecimal.ZERO, BigDecimal.ZERO);
        when(pricingService.findById(id)).thenReturn(Optional.of(rule));

        var result = controller.activateRule(id);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertTrue(result.getBody().isActive());
    }

    @Test
    void activateRule_notFound() {
        UUID id = UUID.randomUUID();
        when(pricingService.findById(id)).thenReturn(Optional.empty());

        var result = controller.activateRule(id);

        assertEquals(HttpStatus.NOT_FOUND, result.getStatusCode());
    }

    @Test
    void deactivateRule_returnsOk() {
        UUID id = UUID.randomUUID();
        var rule = PricingRule.create("Test", null, BigDecimal.ZERO, BigDecimal.ZERO);
        when(pricingService.findById(id)).thenReturn(Optional.of(rule));

        var result = controller.deactivateRule(id);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertFalse(result.getBody().isActive());
    }

    @Test
    void deactivateRule_notFound() {
        UUID id = UUID.randomUUID();
        when(pricingService.findById(id)).thenReturn(Optional.empty());

        var result = controller.deactivateRule(id);

        assertEquals(HttpStatus.NOT_FOUND, result.getStatusCode());
    }

    @Test
    void deleteRule_returnsNoContent() {
        UUID id = UUID.randomUUID();
        when(pricingService.findById(id)).thenReturn(Optional.of(PricingRule.create("Test", null, BigDecimal.ZERO, BigDecimal.ZERO)));

        var result = controller.deleteRule(id);

        assertEquals(HttpStatus.NO_CONTENT, result.getStatusCode());
        verify(pricingService).deleteById(id);
    }

    @Test
    void deleteRule_notFound() {
        UUID id = UUID.randomUUID();
        when(pricingService.findById(id)).thenReturn(Optional.empty());

        var result = controller.deleteRule(id);

        assertEquals(HttpStatus.NOT_FOUND, result.getStatusCode());
        verify(pricingService, never()).deleteById(any());
    }
}
