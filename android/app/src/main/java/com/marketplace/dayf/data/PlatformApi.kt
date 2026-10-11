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

    // A separate client for presigned storage URLs. Never attach the API bearer token to this client.
    private val storageClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun myProfile(): UserProfile = withContext(Dispatchers.IO) {
        request("/api/v1/users/me", "GET", null, UserProfile.serializer())
    }

    suspend fun geoTree(): GeoNode = withContext(Dispatchers.IO) {
        request("/api/v1/geo/tree", "GET", null, GeoNode.serializer())
    }

    suspend fun myNeighborhood(): NeighborhoodMembership = withContext(Dispatchers.IO) {
        request("/api/v1/me/neighborhood", "GET", null, NeighborhoodMembership.serializer())
    }

    suspend fun joinNeighborhood(locationId: String): NeighborhoodMembership = withContext(Dispatchers.IO) {
        request("/api/v1/me/neighborhood", "PUT",
            json.encodeToString(JoinNeighborhoodRequest(locationId)), NeighborhoodMembership.serializer())
    }

    suspend fun requestNeighborhoodVerification(): NeighborhoodMembership = withContext(Dispatchers.IO) {
        request("/api/v1/me/neighborhood/verification-requests", "POST", null, NeighborhoodMembership.serializer())
    }

    suspend fun feed(page: Int = 0, size: Int = 20): PagedResponse<NeighborhoodPost> =
        withContext(Dispatchers.IO) {
            request("/api/v1/neighborhood/posts?page=$page&size=$size", "GET", null,
                PagedResponse.serializer(NeighborhoodPost.serializer()))
        }

    suspend fun searchPosts(query: String, page: Int = 0, size: Int = 20): PagedResponse<NeighborhoodPost> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
            request("/api/v1/neighborhood/posts/search?q=$encoded&page=$page&size=$size", "GET", null,
                PagedResponse.serializer(NeighborhoodPost.serializer()))
        }

    suspend fun createPost(body: CreatePostRequest): NeighborhoodPost = withContext(Dispatchers.IO) {
        request("/api/v1/neighborhood/posts", "POST", json.encodeToString(body), NeighborhoodPost.serializer())
    }

    suspend fun comments(postId: String, page: Int = 0, size: Int = 50): PagedResponse<PostComment> =
        withContext(Dispatchers.IO) {
            request("/api/v1/posts/$postId/comments?page=$page&size=$size", "GET", null,
                PagedResponse.serializer(PostComment.serializer()))
        }

    suspend fun addComment(postId: String, body: String): PostComment = withContext(Dispatchers.IO) {
        request("/api/v1/posts/$postId/comments", "POST", json.encodeToString(CreateCommentRequest(body)),
            PostComment.serializer())
    }

    suspend fun react(postId: String) = withContext(Dispatchers.IO) {
        requestWithoutBody("/api/v1/posts/$postId/reactions", "POST")
    }

    suspend fun removeReaction(postId: String) = withContext(Dispatchers.IO) {
        requestWithoutBody("/api/v1/posts/$postId/reactions", "DELETE")
    }

    suspend fun market(
        category: String? = null,
        query: String? = null,
        mine: Boolean = false,
        page: Int = 0,
        size: Int = 20
    ): PagedResponse<NeighborhoodMarketItem> = withContext(Dispatchers.IO) {
        val params = mutableListOf("page=$page", "size=$size")
        if (!category.isNullOrBlank()) params += "category=" + encode(category)
        if (!query.isNullOrBlank()) params += "q=" + encode(query.trim())
        if (mine) params += "mine=true"
        request("/api/v1/neighborhood/market?${params.joinToString("&")}", "GET", null,
            PagedResponse.serializer(NeighborhoodMarketItem.serializer()))
    }

    suspend fun createMarketItem(body: CreateMarketItemRequest): NeighborhoodMarketItem =
        withContext(Dispatchers.IO) {
            request("/api/v1/neighborhood/market", "POST", json.encodeToString(body),
                NeighborhoodMarketItem.serializer())
        }

    suspend fun withdrawMarketItem(itemId: String) = withContext(Dispatchers.IO) {
        requestWithoutBody("/api/v1/neighborhood/market/$itemId", "DELETE")
    }

    suspend fun uploadPostPhoto(postId: String, contentType: String, bytes: ByteArray): MediaAssetResponse =
        withContext(Dispatchers.IO) {
            require(contentType.startsWith("image/")) { "Only image files can be attached to a post." }
            require(bytes.isNotEmpty()) { "The selected image is empty." }
            val declaration = MediaUploadRequest(
                postId = postId,
                contentType = contentType,
                sizeBytes = bytes.size.toLong()
            )
            val upload = request(
                "/api/v1/media/uploads",
                "POST",
                json.encodeToString(declaration),
                MediaUploadResponse.serializer()
            )

            // This request goes only to the signed URL. It deliberately uses storageClient,
            // which has no Authorization interceptor; the bearer token never leaves the API origin.
            val mediaType = contentType.toMediaType()
            val uploadRequest = Request.Builder()
                .url(upload.uploadUrl)
                .header("Content-Type", contentType)
                .put(bytes.toRequestBody(mediaType))
                .build()
            storageClient.newCall(uploadRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ApiException(
                        response.code,
                        "تعذّر رفع الصورة إلى التخزين. تحقّق من الاتصال ثم أعد المحاولة."
                    )
                }
            }

            request(
                "/api/v1/media/${upload.mediaId}/complete",
                "POST",
                null,
                MediaAssetResponse.serializer()
            )
        }

    suspend fun searchListings(query: String? = null, page: Int = 0, size: Int = 20): PagedResponse<ListingSummary> =
        withContext(Dispatchers.IO) {
            val params = mutableListOf("page=$page", "size=$size")
            if (!query.isNullOrBlank()) params += "q=" + encode(query.trim())
            request("/api/v1/search?${params.joinToString("&")}", "GET", null,
                PagedResponse.serializer(ListingSummary.serializer()))
        }

    suspend fun listingDetail(id: String): ListingDetail = withContext(Dispatchers.IO) {
        request("/api/v1/listings/$id", "GET", null, ListingDetail.serializer())
    }

    suspend fun createEvent(body: CreateEventRequest): NeighborhoodEvent = withContext(Dispatchers.IO) {
        request(
            "/api/v1/neighborhood/events",
            "POST",
            json.encodeToString(body),
            NeighborhoodEvent.serializer()
        )
    }

    suspend fun events(category: String? = null, page: Int = 0, size: Int = 20): PagedResponse<NeighborhoodEvent> =
        withContext(Dispatchers.IO) {
            val params = mutableListOf("page=$page", "size=$size")
            if (!category.isNullOrBlank()) params += "category=" + encode(category)
            request("/api/v1/neighborhood/events?${params.joinToString("&")}", "GET", null,
                PagedResponse.serializer(NeighborhoodEvent.serializer()))
        }

    suspend fun rsvp(eventId: String) = withContext(Dispatchers.IO) {
        requestWithoutBody("/api/v1/events/$eventId/rsvp", "POST")
    }

    suspend fun unrsvp(eventId: String) = withContext(Dispatchers.IO) {
        requestWithoutBody("/api/v1/events/$eventId/rsvp", "DELETE")
    }

    suspend fun groups(page: Int = 0, size: Int = 50): PagedResponse<NeighborhoodGroup> =
        withContext(Dispatchers.IO) {
            request("/api/v1/neighborhood/groups?page=$page&size=$size", "GET", null,
                PagedResponse.serializer(NeighborhoodGroup.serializer()))
        }

    suspend fun joinGroup(groupId: String) = withContext(Dispatchers.IO) {
        requestWithoutBody("/api/v1/neighborhood/groups/$groupId/membership", "POST")
    }

    suspend fun leaveGroup(groupId: String) = withContext(Dispatchers.IO) {
        requestWithoutBody("/api/v1/neighborhood/groups/$groupId/membership", "DELETE")
    }

    suspend fun createPoll(body: CreatePollRequest): NeighborhoodPoll = withContext(Dispatchers.IO) {
        request(
            "/api/v1/neighborhood/polls",
            "POST",
            json.encodeToString(body),
            NeighborhoodPoll.serializer()
        )
    }

    suspend fun polls(page: Int = 0, size: Int = 50): PagedResponse<NeighborhoodPoll> =
        withContext(Dispatchers.IO) {
            request("/api/v1/neighborhood/polls?page=$page&size=$size", "GET", null,
                PagedResponse.serializer(NeighborhoodPoll.serializer()))
        }

    suspend fun vote(pollId: String, optionId: String) = withContext(Dispatchers.IO) {
        requestWithoutBody("/api/v1/polls/$pollId/vote", "POST",
            json.encodeToString(PollVoteRequest(optionId)))
    }

    suspend fun withdrawVote(pollId: String) = withContext(Dispatchers.IO) {
        requestWithoutBody("/api/v1/polls/$pollId/vote", "DELETE")
    }

    suspend fun notifications(page: Int = 0, size: Int = 30): PagedResponse<NotificationItem> =
        withContext(Dispatchers.IO) {
            request("/api/v1/notifications?page=$page&size=$size", "GET", null,
                PagedResponse.serializer(NotificationItem.serializer()))
        }

    suspend fun unreadNotificationCount(): UnreadCountResponse = withContext(Dispatchers.IO) {
        request("/api/v1/notifications/unread-count", "GET", null, UnreadCountResponse.serializer())
    }

    suspend fun markNotificationRead(id: String) = withContext(Dispatchers.IO) {
        requestWithoutBody("/api/v1/notifications/$id/read", "POST")
    }

    suspend fun institutions(page: Int = 0, size: Int = 30): PagedResponse<InstitutionEntry> =
        withContext(Dispatchers.IO) {
            request("/api/v1/institutions?page=$page&size=$size", "GET", null,
                PagedResponse.serializer(InstitutionEntry.serializer()))
        }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun <T> request(
        path: String,
        method: String,
        body: String?,
        deserializer: DeserializationStrategy<T>
    ): T {
        val builder = Request.Builder().url(baseUrl + path)
        when (method) {
            "GET" -> builder.get()
            else -> builder.method(method, body?.toRequestBody(JSON_MEDIA_TYPE) ?: ByteArray(0).toRequestBody(null))
        }
        client.newCall(builder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw apiError(response.code, responseBody)
            if (responseBody.isBlank()) throw ApiException(response.code, "الخادم أعاد استجابة فارغة غير متوقعة.")
            return json.decodeFromString(deserializer, responseBody)
        }
    }

    private fun requestWithoutBody(path: String, method: String, body: String? = null) {
        val request = Request.Builder()
            .url(baseUrl + path)
            .method(
                method,
                when {
                    body != null -> body.toRequestBody(JSON_MEDIA_TYPE)
                    method == "POST" -> ByteArray(0).toRequestBody(null)
                    else -> null
                }
            )
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw apiError(response.code, response.body?.string().orEmpty())
        }
    }

    private fun apiError(code: Int, responseBody: String): ApiException {
        val detail = runCatching {
            val problem = json.parseToJsonElement(responseBody).jsonObject
            problem["detail"]?.jsonPrimitive?.contentOrNull ?: problem["title"]?.jsonPrimitive?.contentOrNull
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
    suspend fun myProfile() = api.myProfile()
    suspend fun geoTree() = api.geoTree()
    suspend fun myNeighborhood() = api.myNeighborhood()
    suspend fun joinNeighborhood(locationId: String) = api.joinNeighborhood(locationId)
    suspend fun requestNeighborhoodVerification() = api.requestNeighborhoodVerification()
    suspend fun feed(page: Int = 0, size: Int = 20) = api.feed(page, size)
    suspend fun searchPosts(query: String, page: Int = 0, size: Int = 20) = api.searchPosts(query, page, size)
    suspend fun createPost(request: CreatePostRequest) = api.createPost(request)
    suspend fun comments(postId: String) = api.comments(postId)
    suspend fun addComment(postId: String, body: String) = api.addComment(postId, body)
    suspend fun react(postId: String) = api.react(postId)
    suspend fun removeReaction(postId: String) = api.removeReaction(postId)
    suspend fun market(category: String? = null, query: String? = null, mine: Boolean = false) =
        api.market(category, query, mine)
    suspend fun createMarketItem(request: CreateMarketItemRequest) = api.createMarketItem(request)
    suspend fun withdrawMarketItem(itemId: String) = api.withdrawMarketItem(itemId)
    suspend fun searchListings(query: String? = null) = api.searchListings(query)
    suspend fun listingDetail(id: String) = api.listingDetail(id)
    suspend fun uploadPostPhoto(postId: String, contentType: String, bytes: ByteArray) =
        api.uploadPostPhoto(postId, contentType, bytes)
    suspend fun events(category: String? = null) = api.events(category)
    suspend fun createEvent(request: CreateEventRequest) = api.createEvent(request)
    suspend fun rsvp(eventId: String) = api.rsvp(eventId)
    suspend fun unrsvp(eventId: String) = api.unrsvp(eventId)
    suspend fun groups() = api.groups()
    suspend fun joinGroup(groupId: String) = api.joinGroup(groupId)
    suspend fun leaveGroup(groupId: String) = api.leaveGroup(groupId)
    suspend fun polls() = api.polls()
    suspend fun createPoll(request: CreatePollRequest) = api.createPoll(request)
    suspend fun vote(pollId: String, optionId: String) = api.vote(pollId, optionId)
    suspend fun withdrawVote(pollId: String) = api.withdrawVote(pollId)
    suspend fun notifications() = api.notifications()
    suspend fun unreadNotificationCount() = api.unreadNotificationCount()
    suspend fun markNotificationRead(id: String) = api.markNotificationRead(id)
    suspend fun institutions() = api.institutions()
}
