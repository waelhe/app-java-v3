package com.marketplace.releases;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The web-layer slice of the publication surface — the exact CI leg with
 * the REAL argument resolution, the boundary parse, the class-level
 * method-security, and the taxonomy advice, the service mocked: if the
 * journey's 400 reproduces here, the root is the web layer; if it does not,
 * the root lives under the transactional/repository half the mock isolates.
 * The failure message carries the WHOLE body (no truncation).
 */
@WebMvcTest(controllers = PlatformReleaseAdminController.class,
        excludeAutoConfiguration = {
                OAuth2ResourceServerAutoConfiguration.class
        })
class PlatformReleaseAdminControllerWebSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PlatformReleaseService releases;

    @Test
    @WithMockUser(roles = "ADMIN")
    void theJourneysExactPublicationBodyIsAccepted() throws Exception {
        when(releases.publish(any(), anyString(), anyString(), anyString(), anyBoolean(),
                anyInt(), any())).thenReturn(new PlatformReleaseService.PlatformReleaseView(
                        java.util.UUID.randomUUID(), com.marketplace.shared.api.PlatformReleaseChannel.IOS,
                        "3.14.4321", "First publication.", "1.0.0", false, 24,
                        java.time.Instant.now()));

        String body = """
                {"channel": "IOS", "version": "3.14.4321",
                 "changelog": "First publication.",
                 "minVersion": "1.0.0", "mandatory": false, "graceHours": 24}
                """;

        MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/v1/admin/releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("the journey's exact publication body — full response: %s",
                        result.getResponse().getContentAsString())
                .isEqualTo(201);
    }
}
