package com.marketplace.pricing;

import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PricingRuleControllerTest {

    private final PricingService pricingService = mock(PricingService.class);
    private final PricingRuleMapper pricingRuleMapper = mock(PricingRuleMapper.class);
    private final PricingRuleController controller = new PricingRuleController(pricingService, pricingRuleMapper);

    @Test
    void listRules_returnsAll() {
        var rule = PricingRule.create("Test", null, BigDecimal.ZERO, BigDecimal.ZERO);
        var response = new PricingRuleResponse(rule.getId(), rule.getName(), rule.getCategory(),
                rule.getTaxRate(), rule.getDiscountPct(), rule.isActive(), null, null);
        when(pricingService.listRules()).thenReturn(List.of(rule));
        when(pricingRuleMapper.toResponse(rule)).thenReturn(response);

        var result = controller.listRules();

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(1, result.getBody().size());
        assertEquals("Test", result.getBody().getFirst().name());
    }

    @Test
    void createRule_returnsCreated() {
        var request = new PricingRuleController.CreateRuleRequest(
                "New Rule", "services", new BigDecimal("0.1500"), new BigDecimal("0.0500")
        );
        var saved = PricingRule.create("New Rule", "services",
                new BigDecimal("0.1500"), new BigDecimal("0.0500"));
        var response = new PricingRuleResponse(saved.getId(), saved.getName(), saved.getCategory(),
                saved.getTaxRate(), saved.getDiscountPct(), saved.isActive(), null, null);
        when(pricingService.createRule("New Rule", "services",
                new BigDecimal("0.1500"), new BigDecimal("0.0500"))).thenReturn(saved);
        when(pricingRuleMapper.toResponse(saved)).thenReturn(response);

        var result = controller.createRule(request);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertNotNull(result.getBody().id());
        assertEquals("New Rule", result.getBody().name());
    }

    @Test
    void activateRule_returnsOk() {
        UUID id = UUID.randomUUID();
        var rule = PricingRule.create("Test", null, BigDecimal.ZERO, BigDecimal.ZERO);
        rule.activate();
        var response = new PricingRuleResponse(rule.getId(), rule.getName(), rule.getCategory(),
                rule.getTaxRate(), rule.getDiscountPct(), rule.isActive(), null, null);
        when(pricingService.activate(id)).thenReturn(rule);
        when(pricingRuleMapper.toResponse(rule)).thenReturn(response);

        var result = controller.activateRule(id);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertTrue(result.getBody().active());
        verify(pricingService).activate(id);
    }

    @Test
    void activateRule_notFound() {
        UUID id = UUID.randomUUID();
        when(pricingService.activate(id)).thenThrow(new ResourceNotFoundException("PricingRule", id));

        assertThrows(ResourceNotFoundException.class, () -> controller.activateRule(id));
    }

    @Test
    void deactivateRule_returnsOk() {
        UUID id = UUID.randomUUID();
        var rule = PricingRule.create("Test", null, BigDecimal.ZERO, BigDecimal.ZERO);
        rule.deactivate();
        var response = new PricingRuleResponse(rule.getId(), rule.getName(), rule.getCategory(),
                rule.getTaxRate(), rule.getDiscountPct(), rule.isActive(), null, null);
        when(pricingService.deactivate(id)).thenReturn(rule);
        when(pricingRuleMapper.toResponse(rule)).thenReturn(response);

        var result = controller.deactivateRule(id);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertFalse(result.getBody().active());
        verify(pricingService).deactivate(id);
    }

    @Test
    void deactivateRule_notFound() {
        UUID id = UUID.randomUUID();
        when(pricingService.deactivate(id)).thenThrow(new ResourceNotFoundException("PricingRule", id));

        assertThrows(ResourceNotFoundException.class, () -> controller.deactivateRule(id));
    }

    @Test
    void deleteRule_returnsNoContent() {
        UUID id = UUID.randomUUID();
        doNothing().when(pricingService).deleteById(id);

        var result = controller.deleteRule(id);

        assertEquals(HttpStatus.NO_CONTENT, result.getStatusCode());
        verify(pricingService).deleteById(id);
    }

    @Test
    void deleteRule_notFound() {
        UUID id = UUID.randomUUID();
        doThrow(new ResourceNotFoundException("PricingRule", id)).when(pricingService).deleteById(id);

        assertThrows(ResourceNotFoundException.class, () -> controller.deleteRule(id));
    }
}
