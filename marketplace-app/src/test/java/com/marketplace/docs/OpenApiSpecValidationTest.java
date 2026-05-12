package com.marketplace.docs;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiSpecValidationTest {

    @Test
    void openApiSpecShouldContainRequiredTopLevelSections() {
        Yaml yaml = new Yaml();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("openapi/marketplace-openapi.yaml")) {
            assertThat(in).isNotNull();
            Map<String, Object> doc = yaml.load(in);

            assertThat(doc).containsKeys("openapi", "info", "paths", "components");
            assertThat(doc.get("openapi")).isEqualTo("3.1.0");

            @SuppressWarnings("unchecked")
            Map<String, Object> components = (Map<String, Object>) doc.get("components");
            assertThat(components).containsKeys("securitySchemes", "schemas");
        } catch (Exception ex) {
            throw new AssertionError("OpenAPI spec parsing failed", ex);
        }
    }
}
