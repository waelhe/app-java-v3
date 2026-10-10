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
