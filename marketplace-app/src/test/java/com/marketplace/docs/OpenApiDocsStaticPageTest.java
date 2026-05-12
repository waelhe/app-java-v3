package com.marketplace.docs;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiDocsStaticPageTest {

    @Test
    void docsLandingPageShouldLinkToBothYamlEndpoints() throws Exception {
        var stream = getClass().getClassLoader().getResourceAsStream("static/docs/index.html");
        assertThat(stream).isNotNull();

        var html = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        assertThat(html).contains("/v3/api-docs.yaml");
        assertThat(html).contains("/openapi.yaml");
    }
}
