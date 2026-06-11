package com.marketplace.pricing;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PricingRuleController.class)
@WithMockUser(roles = "ADMIN")
class PricingRuleControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PricingService pricingService;

    @MockitoBean
    private PricingRuleMapper pricingRuleMapper;

    @Test
    void listRules_returnsOk() throws Exception {
        when(pricingService.listRules()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/pricing/rules"))
                .andExpect(status().isOk());
    }

    @Test
    void createRule_returnsCreated() throws Exception {
        var rule = mockRule();
        var response = mockResponse();

        when(pricingService.createRule(any(), any(), any(), any())).thenReturn(rule);
        when(pricingRuleMapper.toResponse(rule)).thenReturn(response);

        mockMvc.perform(post("/api/v1/pricing/rules")
                        .contentType("application/json")
                        .content("""
                                {"name": "Test Rule", "category": "cat", "taxRate": 0.15, "discountPct": 0.05}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void activateRule_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        var rule = mockRule();
        var response = mockResponse();

        when(pricingService.activate(any())).thenReturn(rule);
        when(pricingRuleMapper.toResponse(rule)).thenReturn(response);

        mockMvc.perform(put("/api/v1/pricing/rules/{id}/activate", id))
                .andExpect(status().isOk());
    }

    private static PricingRule mockRule() {
        return org.mockito.Mockito.mock(PricingRule.class);
    }

    private static PricingRuleResponse mockResponse() {
        return new PricingRuleResponse(UUID.randomUUID(), null, null, null, null, false, null, null);
    }
}
