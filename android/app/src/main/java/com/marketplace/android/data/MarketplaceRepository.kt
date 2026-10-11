package com.marketplace.android.data

import com.marketplace.android.BuildConfig
import com.marketplace.android.core.network.CreateCommentRequest
import com.marketplace.android.core.network.CreateMarketItemRequest
import com.marketplace.android.core.network.CreatePostRequest
import com.marketplace.android.core.network.JoinNeighborhoodRequest
import com.marketplace.android.core.network.RegisterRequest
import com.marketplace.android.core.network.MarketplaceApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.HttpException
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * The UI's only data entry point. Access tokens stay volatile and memory-only.
 */
class MarketplaceRepository {
    @Volatile
    private var accessToken: String? = null

    private val api: MarketplaceApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original: Request = chain.request()
                val tokenSnapshot = accessToken
                val request = original.newBuilder().apply {
                    if (!tokenSnapshot.isNullOrBlank()) {
                        header("Authorization", "Bearer $tokenSnapshot")
                    }
                    header("Accept", "application/json")
                }.build()
                chain.proceed(request)
            }
            .build()

        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(MarketplaceApi::class.java)
    }

    fun setAccessToken(token: String) { accessToken = token }
    fun clearAccessToken() { accessToken = null }

    suspend fun registerAccount(email: String, password: String, displayName: String) {
        val response = api.registerAccount(RegisterRequest(email = email, password = password, displayName = displayName))
        if (!response.isSuccessful) throw HttpException(response)
        response.body()?.close()
    }

    suspend fun searchListings(
        query: String? = null,
        purpose: String? = null,
        propertyType: String? = null,
        minRating: Double? = null,
        page: Int = 0,
        size: Int = 20
    ) = api.searchListings(query, purpose, propertyType, minRating, page, size)

    suspend fun suggestLocations(query: String) = api.suggestLocations(query)
    suspend fun getGeoTree() = api.getGeoTree()
    suspend fun getMyNeighborhood() = api.getMyNeighborhood()
    suspend fun joinNeighborhood(locationId: String) = api.joinNeighborhood(JoinNeighborhoodRequest(locationId))
    suspend fun requestNeighborhoodVerification() = api.requestNeighborhoodVerification()
    suspend fun getFeed(page: Int = 0) = api.getFeed(page = page)
    suspend fun searchPosts(query: String) = api.searchPosts(query = query)
    suspend fun createPost(request: CreatePostRequest) = api.createPost(request)
    suspend fun getComments(postId: String) = api.getComments(postId)
    suspend fun createComment(postId: String, body: String) = api.createComment(postId, CreateCommentRequest(body))
    suspend fun react(postId: String) = api.reactToPost(postId)
    suspend fun removeReaction(postId: String) = api.removePostReaction(postId)
    suspend fun getMarket(page: Int = 0) = api.getMarket(page = page)
    suspend fun createMarketItem(request: CreateMarketItemRequest) = api.createMarketItem(request)
    suspend fun withdrawMarketItem(itemId: String) = api.withdrawMarketItem(itemId)
    suspend fun getNotifications() = api.getNotifications()
    suspend fun markNotificationRead(id: String) = api.markNotificationRead(id)

    suspend fun getEvents(page: Int = 0) = api.getEvents(page = page)
    suspend fun createEvent(request: com.marketplace.android.core.network.CreateEventRequest) = api.createEvent(request)
    suspend fun rsvpEvent(eventId: String) = api.rsvpEvent(eventId)
    suspend fun cancelEventRsvp(eventId: String) = api.cancelEventRsvp(eventId)
    suspend fun deleteEvent(eventId: String) = api.deleteEvent(eventId)
    suspend fun getGroups(page: Int = 0) = api.getGroups(page = page)
    suspend fun joinGroup(groupId: String) = api.joinGroup(groupId)
    suspend fun leaveGroup(groupId: String) = api.leaveGroup(groupId)
    suspend fun getPolls(page: Int = 0) = api.getPolls(page = page)
    suspend fun createPoll(request: com.marketplace.android.core.network.CreatePollRequest) = api.createPoll(request)
    suspend fun voteOnPoll(pollId: String, optionId: String) =
        api.voteOnPoll(pollId, com.marketplace.android.core.network.VoteRequest(optionId))
    suspend fun withdrawPollVote(pollId: String) = api.withdrawPollVote(pollId)

}
