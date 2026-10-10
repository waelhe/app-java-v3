package com.marketplace.android.core.network

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
data class PostDto(
    val id: String = "",
    val authorId: String = "",
    val locationId: String = "",
    val category: String = "GENERAL",
    val title: String = "",
    val body: String = "",
    val status: String = "VISIBLE",
    val reactionsCount: Long = 0,
    val reactedByMe: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = ""
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

data class JoinNeighborhoodRequest(val locationId: String)
data class CreatePostRequest(val locationId: String, val category: String, val title: String, val body: String)
data class CreateCommentRequest(val body: String)
data class CreateMarketItemRequest(
    val locationId: String,
    val category: String,
    val title: String,
    val condition: String,
    val priceCents: Int?,
    val priceCurrency: String?,
    val locationLabel: String
)
