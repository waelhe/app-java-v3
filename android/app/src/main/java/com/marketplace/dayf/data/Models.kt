package com.marketplace.dayf.data

import kotlinx.serialization.Serializable

@Serializable
data class GeoNode(
    val id: String,
    val parentId: String? = null,
    val level: Int,
    val nameAr: String,
    val nameEn: String? = null,
    val slug: String,
    val children: List<GeoNode> = emptyList()
)

@Serializable
data class PagedResponse<T>(
    val content: List<T> = emptyList(),
    val pageNumber: Int = 0,
    val pageSize: Int = 20,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
    val last: Boolean = true
)

@Serializable
data class UserProfile(
    val id: String,
    val email: String? = null,
    val displayName: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class NeighborhoodMembership(
    val id: String,
    val userId: String,
    val locationId: String,
    val verificationState: String,
    val memberSince: String,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class PostMedia(
    val mediaId: String,
    val url: String,
    val thumbUrl: String? = null,
    val contentType: String? = null,
    val position: Int = 0
)

@Serializable
data class NeighborhoodPost(
    val id: String,
    val authorId: String,
    val locationId: String,
    val category: String,
    val title: String,
    val body: String,
    val status: String,
    val reactionsCount: Long = 0,
    val reactedByMe: Boolean = false,
    val media: List<PostMedia> = emptyList(),
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class PostComment(
    val id: String,
    val postId: String,
    val authorId: String,
    val body: String,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class NeighborhoodMarketItem(
    val id: String,
    val authorId: String,
    val locationId: String,
    val category: String,
    val title: String,
    val condition: String,
    val priceCents: Int? = null,
    val priceCurrency: String? = null,
    val status: String,
    val locationLabel: String,
    val sellerVerified: Boolean = false,
    val mine: Boolean = false,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class ListingSummary(
    val id: String,
    val title: String,
    val category: String,
    val price: Double? = null,
    val currency: String? = null,
    val providerName: String? = null,
    val providerRating: Double? = null,
    val providerReviewCount: Long = 0
)

@Serializable
data class ListingDetail(
    val id: String,
    val title: String,
    val description: String? = null,
    val category: String,
    val price: Double? = null,
    val currency: String? = null,
    val maxGuests: Int? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val property: PropertyDetails? = null,
    val expiresAt: String? = null,
    val pausedReason: String? = null
)

@Serializable
data class PropertyDetails(
    val listingId: String,
    val purpose: String? = null,
    val propertyType: String? = null,
    val areaM2: Int? = null,
    val rooms: Int? = null,
    val bathrooms: Int? = null,
    val floorNumber: Int? = null,
    val totalFloors: Int? = null,
    val buildingYear: Int? = null,
    val furnished: Boolean? = null,
    val amenities: List<String> = emptyList(),
    val availableFrom: String? = null,
    val locationId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

@Serializable
data class CreateEventRequest(
    val locationId: String,
    val category: String,
    val title: String,
    val description: String,
    val startsAt: String,
    val endsAt: String? = null,
    val locationLabel: String,
    val organizerLabel: String,
    val capacity: Int? = null,
    val registration: String = "OPEN"
)

@Serializable
data class CreatePollRequest(
    val locationId: String,
    val question: String,
    val authorLabel: String,
    val options: List<String>
)

@Serializable
data class MediaUploadRequest(
    val listingId: String? = null,
    val postId: String? = null,
    val productId: String? = null,
    val contentType: String,
    val sizeBytes: Long
)

@Serializable
data class MediaUploadResponse(
    val mediaId: String,
    val objectKey: String,
    val uploadUrl: String,
    val urlLifetime: String
)

@Serializable
data class MediaAssetResponse(
    val id: String,
    val listingId: String? = null,
    val postId: String? = null,
    val productId: String? = null,
    val contentType: String,
    val sizeBytes: Long,
    val status: String,
    val position: Int,
    val downloadUrl: String,
    val thumbUrl: String? = null,
    val createdAt: String
)

@Serializable
data class NeighborhoodEvent(
    val id: String,
    val authorId: String,
    val locationId: String,
    val category: String,
    val title: String,
    val description: String,
    val startsAt: String,
    val endsAt: String? = null,
    val locationLabel: String,
    val organizerLabel: String,
    val capacity: Int? = null,
    val registration: String,
    val featured: Boolean = false,
    val attending: Long = 0,
    val rsvpedByMe: Boolean = false,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class NeighborhoodGroup(
    val id: String,
    val name: String,
    val description: String,
    val members: Long = 0,
    val joinedByMe: Boolean = false
)

@Serializable
data class NeighborhoodPollOption(
    val id: String,
    val label: String,
    val position: Int,
    val votes: Long = 0
)

@Serializable
data class NeighborhoodPoll(
    val id: String,
    val question: String,
    val author: String,
    val options: List<NeighborhoodPollOption> = emptyList(),
    val votedByMe: String? = null,
    val createdAt: String
)

@Serializable
data class PollVoteRequest(val optionId: String)

@Serializable
data class NotificationItem(
    val id: String,
    val recipientId: String,
    val type: String,
    val message: String,
    val read: Boolean,
    val version: Long? = null,
    val createdBy: String? = null,
    val createdAt: String,
    val updatedBy: String? = null,
    val updatedAt: String
)

@Serializable
data class UnreadCountResponse(val unreadCount: Long)

@Serializable
data class InstitutionEntry(
    val id: String,
    val name: String,
    val type: String,
    val representativeId: String,
    val locationId: String,
    val address: String? = null,
    val phone: String? = null,
    val website: String? = null,
    val description: String? = null,
    val verificationState: String,
    val registeredAt: String,
    val createdAt: String,
    val updatedAt: String,
    val jsonLd: kotlinx.serialization.json.JsonElement? = null
)

@Serializable
data class JoinNeighborhoodRequest(val locationId: String)

@Serializable
data class CreatePostRequest(
    val locationId: String,
    val category: String,
    val title: String,
    val body: String
)

@Serializable
data class CreateCommentRequest(val body: String)

@Serializable
data class CreateMarketItemRequest(
    val locationId: String,
    val category: String,
    val title: String,
    val condition: String,
    val priceCents: Int? = null,
    val priceCurrency: String? = null,
    val locationLabel: String
)

object PostCategoryLabels {
    fun arabic(category: String): String = when (category) {
        "GENERAL" -> "حديث الحي"
        "QUESTION" -> "سؤال"
        "REQUEST" -> "طلب مساعدة"
        "RECOMMENDATION" -> "توصية"
        "LOST_FOUND" -> "مفقودات"
        "CLASSIFIED" -> "إعلان"
        else -> "منشور"
    }
}

object MarketCategoryLabels {
    fun arabic(category: String): String = when (category) {
        "FREE" -> "إهداء مجاني"
        "FURNITURE" -> "أثاث"
        "ELECTRONICS" -> "إلكترونيات"
        "TOOLS" -> "أدوات"
        "OTHER" -> "أغراض أخرى"
        else -> "غرض"
    }
}
