package com.marketplace.media;

import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MVC slice for the media controller — happy paths, request validation and the
 * 503 problem-detail contract of the unconfigured provider gate. Role
 * enforcement on the service commands is covered by
 * {@code MediaServiceSecurityTest} (the house pattern: service-level
 * @PreAuthorize is tested against the real service, not a slice mock).
 */
@WebMvcTest(controllers = MediaController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class
    })
@Import(MediaControllerWebMvcTest.MethodSecurityConfig.class)
class MediaControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MediaService mediaService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void requestUpload_returnsCreated() throws Exception {
        UUID listingId = UUID.randomUUID();
        when(mediaService.requestUpload(eq(listingId), eq("image/jpeg"), eq(1024L), any()))
                .thenReturn(new MediaService.MediaUploadView(
                        UUID.randomUUID(), "listings/" + listingId + "/x.jpg",
                        "https://signed", java.time.Duration.ofMinutes(15)));

        mockMvc.perform(post("/api/v1/media/uploads")
                        .contentType("application/json")
                        .content(uploadBody(listingId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadUrl").value("https://signed"))
                .andExpect(jsonPath("$.objectKey").value("listings/" + listingId + "/x.jpg"));
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void requestUpload_whenStorageNotConfigured_returns503ProblemDetail() throws Exception {
        when(mediaService.requestUpload(any(UUID.class), anyString(), anyLong(), any()))
                .thenThrow(new ServiceUnavailableException("Media storage is not configured"));

        mockMvc.perform(post("/api/v1/media/uploads")
                        .contentType("application/json")
                        .content(uploadBody(UUID.randomUUID())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SU-001"))
                .andExpect(jsonPath("$.title").value("Service Unavailable"));
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void requestUpload_withInvalidBody_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/media/uploads")
                        .contentType("application/json")
                        .content("""
                                {"listingId": "%s", "contentType": ""}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void confirmUpload_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        when(mediaService.confirmUpload(eq(id), any()))
                .thenReturn(new MediaService.MediaAssetView(
                        id, UUID.randomUUID(), "image/jpeg", 1024L, "UPLOADED", 1,
                        "https://signed-get", null));

        mockMvc.perform(post("/api/v1/media/{id}/complete", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPLOADED"));
    }

    @Test
    @WithMockUser(roles = "CONSUMER")
    void listByListing_returnsOkArray() throws Exception {
        UUID listingId = UUID.randomUUID();
        when(mediaService.listByListing(listingId)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/media/listings/{listingId}", listingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void delete_returnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/media/{id}", UUID.randomUUID()))
                .andExpect(status().isNoContent());
    }

    private static String uploadBody(UUID listingId) {
        return """
                {"listingId": "%s", "contentType": "image/jpeg", "sizeBytes": 1024}
                """.formatted(listingId);
    }
}
