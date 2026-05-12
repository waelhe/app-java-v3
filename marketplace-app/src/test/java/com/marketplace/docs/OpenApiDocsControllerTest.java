package com.marketplace.docs;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpenApiDocsControllerTest {

    @Test
    void shouldExposeOpenApiYaml() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new OpenApiDocsController()).build();

        mockMvc.perform(get("/openapi.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/yaml"));
    }
}
