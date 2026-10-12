package com.marketplace.android.data

import android.content.Context
import android.net.Uri
import com.marketplace.android.BuildConfig
import com.marketplace.android.core.network.CreateCommentRequest
import com.marketplace.android.core.network.CreateMarketItemRequest
import com.marketplace.android.core.network.CreatePostRequest
import com.marketplace.android.core.network.EmailOnlyRequest
import com.marketplace.android.core.network.PasskeyRegistrationRequest
import com.marketplace.android.core.network.JoinNeighborhoodRequest
import com.marketplace.android.core.network.RegisterRequest
import com.marketplace.android.core.network.RequestPostMediaUpload
import com.marketplace.android.core.network.UpdateLostFoundStateRequest
import com.marketplace.android.core.network.MarketplaceApi
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.PUT
import retrofit2.http.Url
import retrofit2.HttpException
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * The UI's only data entry point. Access tokens stay volatile and memory-only.
 */
class MarketplaceRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val applicationContext: Context = context.applicationContext
    private val storageUploadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.MINUTES)
            .callTimeout(2, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    // Separate Retrofit service: presigned storage requests must never receive API auth headers.
    // Retrofit's suspend adapter also cancels the underlying OkHttp Call when the coroutine is cancelled.
    private val storageUploadApi: PresignedStorageApi by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(storageUploadClient)
            .build()
            .create(PresignedStorageApi::class.java)
    }

    @Volatile
    private var accessToken: String? = null

    private val api: MarketplaceApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original: Request = chain.request()
                val tokenSnapshot = accessToken
                val request = original.newBuilder().apply {
                    // OAuth protocol requests (notably the public passkey grant) do not
                    // receive a stale resource-server bearer token.
                    if (!tokenSnapshot.isNullOrBlank() && original.url.encodedPath.startsWith("/api/")) {
                        header("Authorization", "Bearer $tokenSnapshot")
                    }
                    header("Accept", "application/json")
                }.build()
                chain.proceed(request)
            }
            .build()

        val moshi = Moshi.Builder()
            .add(java.math.BigDecimal::class.java, com.marketplace.android.core.network.BigDecimalJsonAdapter().nullSafe())
            .add(KotlinJsonAdapterFactory())
            .build()
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

    suspend fun resendVerificationEmail(email: String) {
        val response = api.resendVerificationEmail(EmailOnlyRequest(email = email))
        if (!response.isSuccessful) throw HttpException(response)
        response.body()?.close()
    }

    suspend fun createPasskeyAssertionOptions() = api.createPasskeyAssertionOptions()

    suspend fun signInWithPasskey(challengeId: String, credentialJson: String): String {
        check(BuildConfig.OIDC_CLIENT_ID.isNotBlank()) {
            "The public Android OAuth client ID is not configured in this build."
        }
        val response = api.exchangePasskeyGrant(
            grantType = "urn:marketplace:params:oauth:grant-type:passkey",
            clientId = BuildConfig.OIDC_CLIENT_ID,
            challengeId = challengeId,
            credentialJson = credentialJson
        )
        if (!response.isSuccessful) throw HttpException(response)
        val body = response.body() ?: throw IOException("The token endpoint returned an empty response.")
        return body.accessToken.takeIf(String::isNotBlank)
            ?: throw IOException("The token endpoint did not return an access token.")
    }

    suspend fun createPasskeyRegistrationOptions() = api.createPasskeyRegistrationOptions()

    suspend fun registerPasskey(challengeId: String, credentialJson: String, label: String = "Android device") {
        val response = api.registerPasskey(
            PasskeyRegistrationRequest(
                challengeId = challengeId,
                credentialJson = credentialJson,
                label = label
            )
        )
        if (!response.isSuccessful) throw HttpException(response)
        response.body()?.close()
    }

    suspend fun searchListings(
        query: String? = null,
        locationId: String? = null,
        purpose: String? = null,
        propertyType: String? = null,
        minRating: Double? = null,
        page: Int = 0,
        size: Int = 20
    ) = api.searchListings(query, locationId, purpose, propertyType, minRating, page, size)

    suspend fun suggestLocations(query: String) = api.suggestLocations(query)
    suspend fun getGeoTree() = api.getGeoTree()
    suspend fun getMyNeighborhood() = api.getMyNeighborhood()
    suspend fun joinNeighborhood(locationId: String) = api.joinNeighborhood(JoinNeighborhoodRequest(locationId))
    suspend fun requestNeighborhoodVerification() = api.requestNeighborhoodVerification()
    suspend fun getFeed(page: Int = 0) = api.getFeed(page = page)
    suspend fun searchPosts(query: String, page: Int = 0, size: Int = 20) =
        api.searchPosts(query = query, page = page, size = size)
    suspend fun createPost(request: CreatePostRequest) = api.createPost(request)
    suspend fun updateLostFoundState(postId: String, state: String) =
        api.updateLostFoundState(postId, UpdateLostFoundStateRequest(state))

    suspend fun uploadPostMedia(postId: String, uri: Uri) {
        val context = applicationContext

        withContext(ioDispatcher) {
            val resolver = context.contentResolver
            val allowedContentTypes = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
            val contentType = resolver.getType(uri)
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it in allowedContentTypes }
                ?: throw IOException("صيغة الصورة غير مدعومة. الصيغ المعتمدة: JPEG وPNG وWebP وGIF.")

            val file = File.createTempFile("community-post-", ".upload", context.cacheDir)
            try {
                val maxBytes = BuildConfig.MEDIA_MAX_UPLOAD_BYTES
                var sizeBytes = 0L
                val input = resolver.openInputStream(uri)
                    ?: throw IOException("تعذر قراءة الصورة المختارة.")
                input.use { source ->
                    file.outputStream().buffered().use { target ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = source.read(buffer)
                            if (read < 0) break
                            if (read.toLong() > maxBytes - sizeBytes) {
                                throw IOException(
                                    "حجم الصورة يتجاوز الحد المضبوط لهذا البناء (${maxBytes / (1024 * 1024)} MiB)."
                                )
                            }
                            target.write(buffer, 0, read)
                            sizeBytes += read
                        }
                    }
                }
                if (sizeBytes <= 0L) throw IOException("ملف الصورة فارغ أو غير قابل للقراءة.")

                val upload = api.requestPostMediaUpload(
                    RequestPostMediaUpload(postId, contentType, sizeBytes)
                )
                val uploadUrl = upload.uploadUrl.toHttpUrlOrNull()
                    ?.takeIf { it.isHttps && it.host.isNotBlank() }
                    ?: throw IOException("لم يُرجع الخادم رابط رفع HTTPS صالحًا للصورة.")
                if (upload.mediaId.isBlank()) {
                    throw IOException("لم يُرجع الخادم معرّف رفع صالحًا للصورة.")
                }

                // Never send the bearer token to the third-party presigned storage URL.
                val response = storageUploadApi.upload(
                    uploadUrl.toString(),
                    file.asRequestBody(contentType.toMediaType())
                )
                try {
                    if (!response.isSuccessful) {
                        throw IOException("رفض مخزن الصور الرفع (HTTP ${response.code()}).")
                    }
                } finally {
                    response.body()?.close()
                    response.errorBody()?.close()
                }
                api.confirmPostMediaUpload(upload.mediaId)
            } finally {
                file.delete()
            }
        }
    }
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


/** Cancellable PUT to an opaque presigned URL. This service intentionally has no auth interceptor. */
private interface PresignedStorageApi {
    @PUT
    suspend fun upload(
        @Url url: String,
        @Body body: RequestBody
    ): Response<ResponseBody>
}
