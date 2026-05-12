package com.marketplace.docs;

import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("dev")
@RestController
public class OpenApiDocsController {

    @GetMapping("/docs")
    public ResponseEntity<Void> docsHome() {
        return ResponseEntity.status(302)
                .header("Location", "/docs/index.html")
                .build();
    }

    @GetMapping(value = {"/openapi.yaml", "/v3/api-docs.yaml"}, produces = "application/yaml")
    public ResponseEntity<Resource> openApiYaml() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/yaml"))
                .body(new ClassPathResource("openapi/marketplace-openapi.yaml"));
    }
}
