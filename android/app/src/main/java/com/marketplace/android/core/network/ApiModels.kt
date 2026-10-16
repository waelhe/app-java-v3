package com.marketplace.android.core.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = false)
data class ApiPage<T>(
    val content: List<T> = emptyList(),
    val pageNumber: Int = 0,
    val pageSize: Int = 20,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
    val last: Boolean = true
)

@JsonClass(generateAdapter = false)
data class DiscoveryRowDto(
    val row: String = "",
    val cards: List<DiscoveryCardDto> = emptyList(),
    val totalEligible: Long = 0
)

@JsonClass(generateAdapter = false)
data class DiscoveryCardDto(
    val sourceType: String = "",
    val sourceId: String = "",
    val updatedAt: String? = null,
    val title: String? = null,
    val snippet: String? = null,
    val state: String? = null,
    val scopeLocationId: String? = null,
    val reason: String? = null,
    val paid: Boolean? = null
)

@JsonClass(generateAdapter = false)
data class PostDto(
    val id: String = "",
    val authorId: String = "",
    val locationId: String = "",
    val category: String = "GENERAL",
    val title: String = "",
    val body: String = "",
    val status: String = "VISIBLE",
    val lostFoundState: String? = null,
    val reactionsCount: Long = 0,
    val reactedByMe: Boolean = false,
    val media: List<PostMediaDto> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = ""
)

@JsonClass(generateAdapter = false)
data class PostMediaDto(
    val mediaId: String = "",
    val url: String = "",
    val thumbUrl: String? = null,
    val contentType: String = "",
    val position: Int = 0
)

@JsonClass(generateAdapter = false)
data class CommentDto(
    val id: String = "",
    val postId: String = "",
    val authorId: String = "",
    val body: String = "",
    val createdAt: String = "",
    val updatedAt: String = ""
)

@JsonClass(generateAdapter = false)
data class ReactionDto(
    val id: String = "",
    val postId: String = "",
    val memberId: String = "",
    val createdAt: String = "",
    val updatedAt: String = ""
)

@JsonClass(generateAdapter = false)
data class MembershipDto(
    val id: String = "",
    val userId: String = "",
    val locationId: String = "",
    val verificationState: String = "UNVERIFIED",
    val memberSince: String = "",
    val createdAt: String = "",
    val updatedAt: String = ""
)

@JsonClass(generateAdapter = false)
data class GeoNodeDto(
    val id: String = "",
    val parentId: String? = null,
    val level: Int = -1,
    val nameAr: String = "",
    val nameEn: String? = null,
    val slug: String = "",
    val children: List<GeoNodeDto> = emptyList()
)

@JsonClass(generateAdapter = false)
data class MarketItemDto(
    val id: String = "",
    val authorId: String = "",
    val locationId: String = "",
    val category: String = "OTHER",
    val title: String = "",
    val condition: String = "GOOD",
    val priceCents: Int? = null,
    val priceCurrency: String? = null,
    val status: String = "ACTIVE",
    val locationLabel: String = "",
    val sellerVerified: Boolean = false,
    val mine: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = ""
)

@JsonClass(generateAdapter = false)
data class NotificationDto(
    val id: String = "",
    val recipientId: String = "",
    val type: String = "",
    val message: String = "",
    val read: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = ""
)

@JsonClass(generateAdapter = false)
data class ListingSummaryDto(
    val id: String = "",
    val title: String = "",
    val category: String = "",
    val price: java.math.BigDecimal? = null,
    val currency: String? = null,
    val providerName: String? = null,
    val providerRating: Double? = null,
    val providerReviewCount: Long = 0
)

data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String
)

data class EmailOnlyRequest(val email: String)

@JsonClass(generateAdapter = false)
data class PasskeyChallengeDto(
    val challengeId: String = "",
    val optionsJson: String = ""
)

@JsonClass(generateAdapter = false)
data class PasskeyRegistrationRequest(
    val challengeId: String,
    val credentialJson: String,
    val label: String = "Android device"
)

@JsonClass(generateAdapter = false)
data class PasskeyTokenResponse(
    @Json(name = "access_token") val accessToken: String = "",
    @Json(name = "token_type") val tokenType: String = "Bearer",
    @Json(name = "expires_in") val expiresIn: Long? = null,
    val scope: String? = null
)

data class JoinNeighborhoodRequest(val locationId: String)
data class CreatePostRequest(val locationId: String, val category: String, val title: String, val body: String)
data class CreateCommentRequest(val body: String)

data class RequestPostMediaUpload(
    val postId: String,
    val contentType: String,
    val sizeBytes: Long
)

data class MediaUploadDto(
    val mediaId: String = "",
    val objectKey: String = "",
    val uploadUrl: String = "",
    val urlLifetime: String? = null
)
data class CreateMarketItemRequest(
    val locationId: String,
    val category: String,
    val title: String,
    val condition: String,
    val priceCents: Int?,
    val priceCurrency: String?,
    val locationLabel: String
)


data class EventDto(
    val id: String = "",
    val authorId: String = "",
    val locationId: String = "",
    val category: String = "SOCIAL",
    val title: String = "",
    val description: String = "",
    val startsAt: String = "",
    val endsAt: String? = null,
    val locationLabel: String = "",
    val organizerLabel: String = "",
    val capacity: Int? = null,
    val registration: String = "OPEN",
    // Empty means the server omitted its lifecycle state; UI actions fail closed.
    val status: String = "",
    val featured: Boolean = false,
    val attending: Long = 0,
    val rsvpedByMe: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = ""
)

data class GroupDto(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val members: Long = 0,
    val joinedByMe: Boolean = false
)

data class PollOptionDto(val id: String = "", val label: String = "", val position: Int = 0, val votes: Long = 0)

data class PollDto(
    val id: String = "",
    val question: String = "",
    val author: String = "",
    val options: List<PollOptionDto> = emptyList(),
    val votedByMe: String? = null,
    val createdAt: String = ""
)

data class CreateEventRequest(
    val locationId: String,
    val category: String,
    val title: String,
    val description: String,
    val startsAt: String,
    val endsAt: String?,
    val locationLabel: String,
    val organizerLabel: String,
    val capacity: Int?,
    val registration: String
)

data class CreatePollRequest(val locationId: String, val question: String, val authorLabel: String, val options: List<String>)
data class VoteRequest(val optionId: String)
data class UpdateLostFoundStateRequest(val state: String)
