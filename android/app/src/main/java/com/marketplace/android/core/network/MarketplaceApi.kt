package com.marketplace.android.core.network

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface MarketplaceApi {
    @GET("api/v1/geo/suggest")
    suspend fun suggestLocations(@Query("q") query: String): List<GeoNodeDto>

    @GET("api/v1/geo/tree")
    suspend fun getGeoTree(): GeoNodeDto

    @GET("api/v1/me/neighborhood")
    suspend fun getMyNeighborhood(): MembershipDto

    @PUT("api/v1/me/neighborhood")
    suspend fun joinNeighborhood(@Body request: JoinNeighborhoodRequest): MembershipDto

    @POST("api/v1/me/neighborhood/verification-requests")
    suspend fun requestNeighborhoodVerification(): MembershipDto

    @GET("api/v1/neighborhood/posts")
    suspend fun getFeed(@Query("page") page: Int = 0, @Query("size") size: Int = 20): ApiPage<PostDto>

    @GET("api/v1/neighborhood/posts/search")
    suspend fun searchPosts(
        @Query("q") query: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): ApiPage<PostDto>

    @POST("api/v1/neighborhood/posts")
    suspend fun createPost(@Body request: CreatePostRequest): PostDto

    @GET("api/v1/posts/{postId}/comments")
    suspend fun getComments(
        @Path("postId") postId: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50
    ): ApiPage<CommentDto>

    @POST("api/v1/posts/{postId}/comments")
    suspend fun createComment(@Path("postId") postId: String, @Body request: CreateCommentRequest): CommentDto

    @POST("api/v1/posts/{postId}/reactions")
    suspend fun reactToPost(@Path("postId") postId: String): ReactionDto

    @DELETE("api/v1/posts/{postId}/reactions")
    suspend fun removePostReaction(@Path("postId") postId: String)

    @GET("api/v1/neighborhood/market")
    suspend fun getMarket(@Query("page") page: Int = 0, @Query("size") size: Int = 30): ApiPage<MarketItemDto>

    @POST("api/v1/neighborhood/market")
    suspend fun createMarketItem(@Body request: CreateMarketItemRequest): MarketItemDto

    @DELETE("api/v1/neighborhood/market/{itemId}")
    suspend fun withdrawMarketItem(@Path("itemId") itemId: String)

    @GET("api/v1/notifications")
    suspend fun getNotifications(@Query("page") page: Int = 0, @Query("size") size: Int = 30): ApiPage<NotificationDto>

    @POST("api/v1/notifications/{id}/read")
    suspend fun markNotificationRead(@Path("id") id: String): NotificationDto
}
