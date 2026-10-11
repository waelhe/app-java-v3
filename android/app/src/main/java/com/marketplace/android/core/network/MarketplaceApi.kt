package com.marketplace.android.core.network

import com.marketplace.android.core.model.CreateCommentRequest
import com.marketplace.android.core.model.CreatePostRequest
import com.marketplace.android.core.model.EmailOnlyRequest
import com.marketplace.android.core.model.GeoNodeDto
import com.marketplace.android.core.model.MediaUploadDto
import com.marketplace.android.core.model.NeighborhoodJoinRequest
import com.marketplace.android.core.model.NeighborhoodMembershipDto
import com.marketplace.android.core.model.PagedResponse
import com.marketplace.android.core.model.PostCommentDto
import com.marketplace.android.core.model.PostDto
import com.marketplace.android.core.model.RegisterRequest
import com.marketplace.android.core.model.RegisteredUserDto
import com.marketplace.android.core.model.RequestMediaUpload
import com.marketplace.android.core.model.UserProfileDto
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.IOException

interface MarketplaceApi {
    @GET("api/v1/users/me")
    suspend fun myProfile(): UserProfileDto

    @POST("api/v1/auth/email-verification/resend")
    suspend fun resendEmailVerification(@Body request: EmailOnlyRequest)

    @GET("api/v1/geo/suggest")
    suspend fun suggestNeighborhoods(@Query("q") query: String): List<GeoNodeDto>

    @GET("api/v1/me/neighborhood")
    suspend fun myNeighborhood(): NeighborhoodMembershipDto

    @PUT("api/v1/me/neighborhood")
    suspend fun joinNeighborhood(@Body request: NeighborhoodJoinRequest): NeighborhoodMembershipDto

    @DELETE("api/v1/me/neighborhood")
    suspend fun leaveNeighborhood()

    @GET("api/v1/neighborhood/posts")
    suspend fun feed(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
        @Query("sort") sort: String = "createdAt,desc"
    ): PagedResponse<PostDto>

    @GET("api/v1/neighborhood/posts/search")
    suspend fun searchPosts(
        @Query("q") query: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20,
        @Query("sort") sort: String = "createdAt,desc"
    ): PagedResponse<PostDto>

    @POST("api/v1/neighborhood/posts")
    suspend fun createPost(@Body request: CreatePostRequest): PostDto

    @GET("api/v1/posts/{postId}/comments")
    suspend fun comments(
        @Path("postId") postId: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 50,
        @Query("sort") sort: String = "createdAt,asc"
    ): PagedResponse<PostCommentDto>

    @POST("api/v1/posts/{postId}/comments")
    suspend fun createComment(
        @Path("postId") postId: String,
        @Body request: CreateCommentRequest
    ): PostCommentDto

    @POST("api/v1/posts/{postId}/reactions")
    suspend fun react(@Path("postId") postId: String)

    @DELETE("api/v1/posts/{postId}/reactions")
    suspend fun removeReaction(@Path("postId") postId: String)

    @POST("api/v1/auth/register")
    suspend fun register(@Body request: RegisterRequest): RegisteredUserDto

    @POST("api/v1/media/uploads")
    suspend fun requestMediaUpload(@Body request: RequestMediaUpload): MediaUploadDto

    @POST("api/v1/media/{mediaId}/complete")
    suspend fun completeMediaUpload(@Path("mediaId") mediaId: String)
}

class MarketplaceApiClient(
    baseUrl: String,
    accessToken: () -> String?
) {
    private val authenticatedClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val original = chain.request()
            val builder = original.newBuilder()
                .header("Accept", "application/json")
                .header("X-API-Version", "1.0")
            accessToken()?.takeIf(String::isNotBlank)?.let {
                builder.header("Authorization", "Bearer $it")
            }
            chain.proceed(builder.build())
        })
        .build()

    val api: MarketplaceApi = Retrofit.Builder()
        .baseUrl(baseUrl.trimEnd('/') + "/")
        .client(authenticatedClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(MarketplaceApi::class.java)

    // Intentionally separate from authenticatedClient: a presigned object-store
    // URL must never receive the marketplace Bearer token or API headers.
    private val objectStoreClient = OkHttpClient()

    suspend fun putPresignedObject(url: String, uriRequestBody: RequestBody) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .put(uriRequestBody)
            .build()
        objectStoreClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Object storage rejected the upload (code ${response.code})")
            }
        }
    }
}

fun streamingBody(
    contentType: String,
    contentLength: Long,
    openStream: () -> java.io.InputStream?
): RequestBody = object : RequestBody() {
    override fun contentType() = contentType.toMediaTypeSafe()

    override fun contentLength(): Long = contentLength

    override fun writeTo(sink: BufferedSink) {
        val input = openStream() ?: throw IOException("The selected photo is no longer readable")
        input.use { stream ->
            stream.source().use { source ->
                sink.writeAll(source)
            }
        }
    }
}

private fun String.toMediaTypeSafe() = okhttp3.MediaType.parse(this)

object ApiFailureMessage {
    fun from(error: Throwable): String {
        if (error is HttpException) {
            val payload = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
            val json = payload?.let { runCatching { JSONObject(it) }.getOrNull() }
            listOf("userMessage", "detail", "title").forEach { key ->
                val value = json?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }
                if (value != null) return value
            }
            return when (error.code()) {
                400 -> "راجع البيانات المدخلة."
                401 -> "انتهت جلسة الدخول. سجّل الدخول مجددًا."
                403 -> "لا تملك صلاحية تنفيذ هذا الإجراء في هذا الحي."
                404 -> "لم يعد هذا المحتوى متاحًا."
                409 -> "تعارضت العملية مع حالة المحتوى الحالية."
                429 -> "تكررت الطلبات بسرعة. انتظر قليلًا ثم أعد المحاولة."
                else -> "تعذر إكمال الطلب (${error.code()})."
            }
        }
        if (error is IOException) return "تعذر الاتصال بالخادم. تحقّق من الاتصال ثم أعد المحاولة."
        return error.message?.takeIf { it.isNotBlank() } ?: "حدث خطأ غير متوقع."
    }
}
