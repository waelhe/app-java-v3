package com.marketplace.docs;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiSpecValidationTest {

    @Test
    void openApiSpecShouldContainRequiredTopLevelSections() {
        Map<String, Object> doc = loadDoc();

        assertThat(doc).containsKeys("openapi", "info", "paths", "components");
        assertThat(doc.get("openapi")).isEqualTo("3.1.0");

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) doc.get("components");
        assertThat(components).containsKeys("securitySchemes", "schemas");
    }

    @Test
    void referencedSchemasShouldExist() {
        Map<String, Object> doc = loadDoc();

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) doc.get("components");
        @SuppressWarnings("unchecked")
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");

        assertThat(schemas).containsKeys("ProviderRequest", "ProviderResponse", "DisputeCreateRequest", "ErrorResponse");
    }


    @Test
    void requiredPathsShouldExist() {
        Map<String, Object> doc = loadDoc();

        @SuppressWarnings("unchecked")
        Map<String, Object> paths = (Map<String, Object>) doc.get("paths");
        assertThat(paths).containsKeys(
                "/api/providers",
                "/api/search",
                "/api/payments/webhooks",
                "/api/disputes"
        );
    }

    private Map<String, Object> loadDoc() {
        Yaml yaml = new Yaml();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("openapi/marketplace-openapi.yaml")) {
            assertThat(in).isNotNull();
            return yaml.load(in);
        } catch (Exception ex) {
            throw new AssertionError("OpenAPI spec parsing failed", ex);
        }
    }
}
