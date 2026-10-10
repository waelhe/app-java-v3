package com.marketplace.dayf.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiException(
    val statusCode: Int,
    override val message: String
) : IOException(message)

/** Single HTTP transport: token injection, timeouts, and error normalization live here. */
class PlatformApi(
    baseUrl: String,
    private val accessToken: () -> String?
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
                .header("Accept", "application/json")
                .header("User-Agent", "Dayf-Android/0.1.0")
            accessToken()?.let { builder.header("Authorization", "Bearer $it") }
            chain.proceed(builder.build())
        }
        .build()

    suspend fun geoTree(): GeoNode = withContext(Dispatchers.IO) {
        request("/api/v1/geo/tree", "GET", null, GeoNode.serializer())
    }

    suspend fun myNeighborhood(): NeighborhoodMembership = withContext(Dispatchers.IO) {
        request("/api/v1/me/neighborhood", "GET", null, NeighborhoodMembership.serializer())
    }

    suspend fun joinNeighborhood(locationId: String): NeighborhoodMembership = withContext(Dispatchers.IO) {
        request(
            "/api/v1/me/neighborhood",
            "PUT",
            json.encodeToString(JoinNeighborhoodRequest(locationId)),
            NeighborhoodMembership.serializer()
        )
    }

    suspend fun requestNeighborhoodVerification(): NeighborhoodMembership = withContext(Dispatchers.IO) {
        request(
            "/api/v1/me/neighborhood/verification-requests",
            "POST",
            null,
            NeighborhoodMembership.serializer()
        )
    }

    suspend fun feed(page: Int = 0, size: Int = 20): PagedResponse<NeighborhoodPost> =
        withContext(Dispatchers.IO) {
            request(
                "/api/v1/neighborhood/posts?page=$page&size=$size",
                "GET",
                null,
                PagedResponse.serializer(NeighborhoodPost.serializer())
            )
        }

    suspend fun searchPosts(query: String, page: Int = 0, size: Int = 20): PagedResponse<NeighborhoodPost> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
            request(
                "/api/v1/neighborhood/posts/search?q=$encoded&page=$page&size=$size",
                "GET",
                null,
                PagedResponse.serializer(NeighborhoodPost.serializer())
            )
        }

    suspend fun createPost(body: CreatePostRequest): NeighborhoodPost = withContext(Dispatchers.IO) {
        request(
            "/api/v1/neighborhood/posts",
            "POST",
            json.encodeToString(body),
            NeighborhoodPost.serializer()
        )
    }

    suspend fun comments(postId: String, page: Int = 0, size: Int = 50): PagedResponse<PostComment> =
        withContext(Dispatchers.IO) {
            request(
                "/api/v1/posts/$postId/comments?page=$page&size=$size",
                "GET",
                null,
                PagedResponse.serializer(PostComment.serializer())
            )
        }

    suspend fun addComment(postId: String, body: String): PostComment = withContext(Dispatchers.IO) {
        request(
            "/api/v1/posts/$postId/comments",
            "POST",
            json.encodeToString(CreateCommentRequest(body)),
            PostComment.serializer()
        )
    }

    suspend fun react(postId: String) = withContext(Dispatchers.IO) {
        requestWithoutBody("/api/v1/posts/$postId/reactions", "POST")
    }

    suspend fun removeReaction(postId: String) = withContext(Dispatchers.IO) {
        requestWithoutBody("/api/v1/posts/$postId/reactions", "DELETE")
    }

    private fun <T> request(
        path: String,
        method: String,
        body: String?,
        deserializer: DeserializationStrategy<T>
    ): T {
        val builder = Request.Builder().url(baseUrl + path)
        when (method) {
            "GET" -> builder.get()
            else -> builder.method(
                method,
                body?.toRequestBody(JSON_MEDIA_TYPE) ?: ByteArray(0).toRequestBody(null)
            )
        }

        client.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw apiError(response.code, responseBody)
            if (responseBody.isBlank()) {
                throw ApiException(response.code, "الخادم أعاد استجابة فارغة غير متوقعة.")
            }
            return json.decodeFromString(deserializer, responseBody)
        }
    }

    private fun requestWithoutBody(path: String, method: String) {
        val request = Request.Builder()
            .url(baseUrl + path)
            .method(method, if (method == "POST") ByteArray(0).toRequestBody(null) else null)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw apiError(response.code, response.body?.string().orEmpty())
            }
        }
    }

    private fun apiError(code: Int, responseBody: String): ApiException {
        val detail = runCatching {
            val problem = json.parseToJsonElement(responseBody).jsonObject
            problem["detail"]?.jsonPrimitive?.contentOrNull
                ?: problem["title"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        val message = detail ?: when (code) {
            401 -> "انتهت جلسة الدخول. سجّل الدخول من جديد."
            403 -> "هذه العملية تحتاج إلى عضوية فعّالة في هذا الحي."
            404 -> "لم يُعثر على العنصر المطلوب."
            409 -> "تعذّر التنفيذ بسبب تعارض في حالة العنصر."
            429 -> "تجاوزت حد الطلبات المسموح؛ حاول بعد قليل."
            in 500..599 -> "الخادم غير متاح حاليًا. حاول مرة أخرى."
            else -> "تعذّر إكمال الطلب (HTTP $code)."
        }
        return ApiException(code, message)
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class PlatformRepository(private val api: PlatformApi) {
    suspend fun geoTree() = api.geoTree()
    suspend fun myNeighborhood() = api.myNeighborhood()
    suspend fun joinNeighborhood(locationId: String) = api.joinNeighborhood(locationId)
    suspend fun requestNeighborhoodVerification() = api.requestNeighborhoodVerification()
    suspend fun feed(page: Int = 0, size: Int = 20) = api.feed(page, size)
    suspend fun searchPosts(query: String, page: Int = 0, size: Int = 20) =
        api.searchPosts(query, page, size)
    suspend fun createPost(request: CreatePostRequest) = api.createPost(request)
    suspend fun comments(postId: String) = api.comments(postId)
    suspend fun addComment(postId: String, body: String) = api.addComment(postId, body)
    suspend fun react(postId: String) = api.react(postId)
    suspend fun removeReaction(postId: String) = api.removeReaction(postId)
}
