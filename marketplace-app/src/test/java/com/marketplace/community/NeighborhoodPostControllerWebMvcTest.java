package com.marketplace.community;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L42 MVC slice: the feed surface's request validation and status shape.
 * The membership 403s (G-N3), the gate orders, the event publication and
 * the deterministic pagination are pinned against the REAL chain by the
 * module integration test; this slice pins the HTTP contract itself (the
 * L41/L31 WebMvc precedent): the type gate's 400s (invalid category,
 * blank title, over-limit body — before any write), the honest 404s, the
 * 201 writes and the 204 delete.
 */
@WebMvcTest(controllers = NeighborhoodPostController.class,
        excludeAutoConfiguration = {
                OAuth2ResourceServerAutoConfiguration.class
        })
@WithMockUser
@Import(NeighborhoodPostControllerWebMvcTest.MethodSecurityConfig.class)
class NeighborhoodPostControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NeighborhoodPostService postService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    private UUID stubCaller() {
        // The house form (the L41 slice's own note): the untyped any()
        // with the generic hint matches a null Authentication too.
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(
                org.mockito.ArgumentMatchers.<org.springframework.security.core.Authentication>any()))
                .thenReturn(userId);
        return userId;
    }

    private NeighborhoodPostView postView(UUID authorId, UUID locationId) {
        Instant now = Instant.parse("2026-09-17T09:30:00Z");
        return new NeighborhoodPostView(UUID.randomUUID(), authorId, locationId,
                "GENERAL", "Title", "Body", "VISIBLE", now, now);
    }

    @Test
    void getFeed_member_answersThePagedBody() throws Exception {
        UUID userId = stubCaller();
        UUID locationId = UUID.randomUUID();
        NeighborhoodPostView view = postView(userId, locationId);
        when(postService.getFeed(eq(userId), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(view)));

        mockMvc.perform(get("/api/v1/neighborhood/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].authorId").value(userId.toString()))
                .andExpect(jsonPath("$.content[0].status").value("VISIBLE"))
                .andExpect(jsonPath("$.pageNumber").value(0));
    }

    @Test
    void getFeed_invalidCategory_is400AtTheBoundary() throws Exception {
        stubCaller();

        mockMvc.perform(get("/api/v1/neighborhood/posts")
                        .param("category", "NOT_A_CATEGORY"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getFeed_noMembership_answers403ProblemDetail() throws Exception {
        UUID userId = stubCaller();
        when(postService.getFeed(eq(userId), isNull(), any()))
                .thenThrow(new AccessDeniedException("Join a neighborhood first"));

        mockMvc.perform(get("/api/v1/neighborhood/posts"))
                .andExpect(status().isForbidden());
    }

    @Test
    void postCreate_validBody_answers201() throws Exception {
        UUID userId = stubCaller();
        UUID locationId = UUID.randomUUID();
        when(postService.createPost(eq(userId), eq(locationId), eq(PostCategory.GENERAL),
                eq("Title"), eq("Body")))
                .thenReturn(postView(userId, locationId));

        mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + locationId + "\", "
                                + "\"category\": \"GENERAL\", \"title\": \"Title\", "
                                + "\"body\": \"Body\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("GENERAL"))
                .andExpect(jsonPath("$.title").value("Title"));
    }

    @Test
    void postCreate_invalidCategory_is400AtTheBoundary() throws Exception {
        stubCaller();

        mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + UUID.randomUUID() + "\", "
                                + "\"category\": \"SPAM\", \"title\": \"Title\", "
                                + "\"body\": \"Body\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postCreate_blankCategory_is400AtTheBoundary() throws Exception {
        // The CodeRabbit round-1 adoption: @NotBlank (not @NotNull) at the
        // boundary — "" must answer the clean 400, never reach
        // parseCategory's null branch.
        stubCaller();

        mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + UUID.randomUUID() + "\", "
                                + "\"category\": \"  \", \"title\": \"Title\", "
                                + "\"body\": \"Body\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postCreate_blankTitle_is400BeforeAnyWrite() throws Exception {
        stubCaller();

        mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + UUID.randomUUID() + "\", "
                                + "\"category\": \"GENERAL\", \"title\": \"  \", "
                                + "\"body\": \"Body\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postCreate_overLimitBody_is400BeforeAnyWrite() throws Exception {
        stubCaller();
        String over = "x".repeat(NeighborhoodPostController.MAX_BODY_LENGTH + 1);

        mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + UUID.randomUUID() + "\", "
                                + "\"category\": \"GENERAL\", \"title\": \"Title\", "
                                + "\"body\": \"" + over + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postCreate_overLimitTitle_is400BeforeAnyWrite() throws Exception {
        stubCaller();
        String over = "x".repeat(201);

        mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + UUID.randomUUID() + "\", "
                                + "\"category\": \"GENERAL\", \"title\": \"" + over + "\", "
                                + "\"body\": \"Body\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postCreate_noMembership_answers403ProblemDetail() throws Exception {
        UUID userId = stubCaller();
        UUID locationId = UUID.randomUUID();
        when(postService.createPost(eq(userId), eq(locationId), eq(PostCategory.GENERAL),
                eq("Title"), eq("Body")))
                .thenThrow(new AccessDeniedException("Join a neighborhood first"));

        mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + locationId + "\", "
                                + "\"category\": \"GENERAL\", \"title\": \"Title\", "
                                + "\"body\": \"Body\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void postCreate_unknownLocation_answers404ProblemDetail() throws Exception {
        UUID userId = stubCaller();
        UUID locationId = UUID.randomUUID();
        when(postService.createPost(eq(userId), eq(locationId), eq(PostCategory.GENERAL),
                eq("Title"), eq("Body")))
                .thenThrow(new ResourceNotFoundException("Location", locationId));

        mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + locationId + "\", "
                                + "\"category\": \"GENERAL\", \"title\": \"Title\", "
                                + "\"body\": \"Body\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void postCreate_nonLevel3Node_answers400ProblemDetail() throws Exception {
        UUID userId = stubCaller();
        UUID locationId = UUID.randomUUID();
        when(postService.createPost(eq(userId), eq(locationId), eq(PostCategory.GENERAL),
                eq("Title"), eq("Body")))
                .thenThrow(new BadRequestException(
                        "locationId must reference a level-3 neighborhood node, got level 2"));

        mockMvc.perform(post("/api/v1/neighborhood/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + locationId + "\", "
                                + "\"category\": \"GENERAL\", \"title\": \"Title\", "
                                + "\"body\": \"Body\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void commentCreate_validBody_answers201() throws Exception {
        UUID commenterId = stubCaller();
        UUID postId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-17T09:30:00Z");
        when(postService.comment(eq(commenterId), eq(postId), eq("Nice")))
                .thenReturn(new PostCommentView(UUID.randomUUID(), postId, commenterId,
                        "Nice", now, now));

        mockMvc.perform(post("/api/v1/posts/{id}/comments", postId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Nice\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postId").value(postId.toString()))
                .andExpect(jsonPath("$.body").value("Nice"));
    }

    @Test
    void commentCreate_blankBody_is400BeforeAnyWrite() throws Exception {
        stubCaller();

        mockMvc.perform(post("/api/v1/posts/{id}/comments", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void commentCreate_unknownPost_answers404ProblemDetail() throws Exception {
        UUID commenterId = stubCaller();
        UUID postId = UUID.randomUUID();
        when(postService.comment(eq(commenterId), eq(postId), eq("Nice")))
                .thenThrow(new ResourceNotFoundException("Post", postId));

        mockMvc.perform(post("/api/v1/posts/{id}/comments", postId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Nice\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void commentCreate_nonMember_answers403ProblemDetail() throws Exception {
        UUID commenterId = stubCaller();
        UUID postId = UUID.randomUUID();
        when(postService.comment(eq(commenterId), eq(postId), eq("Nice")))
                .thenThrow(new AccessDeniedException(
                        "Only members of the post's neighborhood can comment"));

        mockMvc.perform(post("/api/v1/posts/{id}/comments", postId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\": \"Nice\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void commentRead_answersThePagedBody() throws Exception {
        UUID callerId = stubCaller();
        UUID postId = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-17T09:30:00Z");
        when(postService.getComments(eq(callerId), eq(postId), any()))
                .thenReturn(new PageImpl<>(List.of(new PostCommentView(
                        UUID.randomUUID(), postId, callerId, "Nice", now, now))));

        mockMvc.perform(get("/api/v1/posts/{id}/comments", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].postId").value(postId.toString()));
    }

    @Test
    void commentRead_hiddenPost_answers404() throws Exception {
        UUID callerId = stubCaller();
        UUID postId = UUID.randomUUID();
        when(postService.getComments(eq(callerId), eq(postId), any()))
                .thenThrow(new ResourceNotFoundException("Post", postId));

        mockMvc.perform(get("/api/v1/posts/{id}/comments", postId))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteMyPost_answers204() throws Exception {
        UUID authorId = stubCaller();
        UUID postId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/posts/{id}", postId))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteAnotherAuthorsPost_answers403() throws Exception {
        UUID callerId = stubCaller();
        UUID postId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new AccessDeniedException(
                        "Only the post's author can delete it"))
                .when(postService).deleteByAuthor(callerId, postId);

        mockMvc.perform(delete("/api/v1/posts/{id}", postId))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteUnknownPost_answers404() throws Exception {
        UUID callerId = stubCaller();
        UUID postId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Post", postId))
                .when(postService).deleteByAuthor(callerId, postId);

        mockMvc.perform(delete("/api/v1/posts/{id}", postId))
                .andExpect(status().isNotFound());
    }
}
