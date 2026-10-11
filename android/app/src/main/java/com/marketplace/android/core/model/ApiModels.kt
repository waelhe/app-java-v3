package com.marketplace.android.core.model

data class GeoNodeDto(
    val id: String? = null,
    val parentId: String? = null,
    val level: Int = 0,
    val nameAr: String? = null,
    val nameEn: String? = null,
    val slug: String? = null,
    val children: List<GeoNodeDto>? = null
) {
    fun displayName(): String = nameAr?.takeIf { it.isNotBlank() }
        ?: nameEn?.takeIf { it.isNotBlank() }
        ?: slug.orEmpty()
}

data class NeighborhoodJoinRequest(val locationId: String)

data class NeighborhoodMembershipDto(
    val id: String? = null,
    val userId: String? = null,
    val locationId: String? = null,
    val verificationState: String? = null,
    val memberSince: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

data class PagedResponse<T>(
    val content: List<T> = emptyList(),
    val pageNumber: Int = 0,
    val pageSize: Int = 20,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
    val first: Boolean = true,
    val last: Boolean = true
)

data class PostDto(
    val id: String,
    val authorId: String? = null,
    val locationId: String? = null,
    val category: String = "GENERAL",
    val title: String = "",
    val body: String = "",
    val status: String = "VISIBLE",
    val reactionsCount: Long = 0,
    val reactedByMe: Boolean = false,
    val media: List<PostMediaDto> = emptyList(),
    val createdAt: String? = null,
    val updatedAt: String? = null
)

data class PostMediaDto(
    val mediaId: String? = null,
    val url: String? = null,
    val thumbUrl: String? = null,
    val contentType: String? = null,
    val position: Int = 0
)

data class CreatePostRequest(
    val locationId: String,
    val category: String,
    val title: String,
    val body: String
)

data class CreateCommentRequest(val body: String)

data class PostCommentDto(
    val id: String? = null,
    val postId: String? = null,
    val authorId: String? = null,
    val body: String = "",
    val createdAt: String? = null
)

data class UserProfileDto(
    val id: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

data class EmailOnlyRequest(val email: String)

data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String
)

data class RegisteredUserDto(
    val id: String? = null,
    val email: String? = null,
    val displayName: String? = null
)

data class RequestMediaUpload(
    val listingId: String? = null,
    val postId: String? = null,
    val productId: String? = null,
    val contentType: String,
    val sizeBytes: Long
)

data class MediaUploadDto(
    val mediaId: String,
    val objectKey: String? = null,
    val uploadUrl: String,
    val urlLifetime: String? = null
)
