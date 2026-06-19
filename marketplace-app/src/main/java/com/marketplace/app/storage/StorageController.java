package com.marketplace.app.storage;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.security.CurrentUserProvider;
import com.marketplace.shared.storage.StorageException;
import com.marketplace.shared.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

/**
 * REST Controller for file storage operations.
 *
 * <p>Provides endpoints for:
 * <ul>
 *   <li>Uploading files (multipart/form-data)</li>
 *   <li>Downloading files by ID</li>
 *   <li>Deleting files by ID</li>
 * </ul>
 *
 * <p>Pattern follows {@code AdminController}: {@code @RestController} +
 * {@code @RequestMapping(ApiConstants.API_V1)} + constructor injection +
 * {@code CurrentUserProvider}.
 *
 * <p>Reference: Spring Boot How-to -- Handling Multipart File Uploads:
 * "When you want to receive multipart encoded file data as a @RequestParam-annotated
 * parameter of type MultipartFile in a Spring MVC controller handler method."
 * https://docs.spring.io/spring-boot/how-to/spring-mvc.html
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1 + "/storage", version = "1.0")
@PreAuthorize("isAuthenticated()")
public class StorageController {

    private static final Logger log = LoggerFactory.getLogger(StorageController.class);

    private final StorageService storageService;
    private final CurrentUserProvider currentUserProvider;

    public StorageController(StorageService storageService,
                              CurrentUserProvider currentUserProvider) {
        this.storageService = storageService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            Authentication auth) {
        UUID userId = currentUserProvider.getCurrentUserId(auth);
        StorageService.StoredFile stored = storageService.store(file, userId);

        return ResponseEntity.ok(Map.of(
                "id", stored.id().toString(),
                "originalName", stored.originalName(),
                "contentType", stored.contentType() != null ? stored.contentType() : "application/octet-stream",
                "sizeBytes", stored.sizeBytes(),
                "message", "File uploaded successfully"
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        StorageService.StoredFile metadata = storageService.getFile(id);
        if (metadata == null) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = storageService.load(metadata.storedPath());
        String contentType = metadata.contentType() != null ? metadata.contentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + metadata.originalName() + "\"")
                .body(resource);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        StorageService.StoredFile metadata = storageService.getFile(id);
        if (metadata == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            storageService.delete(metadata.storedPath());
            log.info("File deleted: id={}, name={}", id, metadata.originalName());
            return ResponseEntity.noContent().build();
        } catch (StorageException e) {
            log.error("Failed to delete file: id={}, error={}", id, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}
