package com.marketplace.android.core.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiModelsTest {
    private val moshi = Moshi.Builder()
        .add(java.math.BigDecimal::class.java, BigDecimalJsonAdapter().nullSafe())
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun pagedPostResponseMatchesServerEnvelopeAndKeepsArabicText() {
        val type = Types.newParameterizedType(ApiPage::class.java, PostDto::class.java)
        val json = """
            {
              "content": [{
                "id": "post-1",
                "authorId": "member-1",
                "locationId": "neighborhood-1",
                "category": "QUESTION",
                "title": "من يعرف سباكًا؟",
                "body": "أحتاج توصية من الحي",
                "status": "VISIBLE",
                "reactionsCount": 2,
                "reactedByMe": true,
                "media": [{
                  "mediaId": "asset-1",
                  "url": "https://media.example.test/post-image.jpg",
                  "thumbUrl": null,
                  "contentType": "image/jpeg",
                  "position": 0
                }],
                "createdAt": "2026-10-11T09:00:00Z",
                "updatedAt": "2026-10-11T09:00:00Z"
              }],
              "pageNumber": 1,
              "pageSize": 20,
              "totalElements": 21,
              "totalPages": 2,
              "last": false
            }
        """.trimIndent()

        val page = moshi.adapter<ApiPage<PostDto>>(type).fromJson(json)!!

        assertEquals(1, page.pageNumber)
        assertEquals(21L, page.totalElements)
        assertEquals(2, page.totalPages)
        assertFalse(page.last)
        assertEquals("من يعرف سباكًا؟", page.content.single().title)
        assertTrue(page.content.single().reactedByMe)
        assertEquals("https://media.example.test/post-image.jpg", page.content.single().media.single().url)
    }

    @Test
    fun geoSuggestionParsesFlatNeighborhoodNode() {
        val json = """{"id":"neighborhood-1","parentId":"city-1","level":3,"nameAr":"الميدان","nameEn":"Al Midan","slug":"al-midan"}"""
        val node = moshi.adapter(GeoNodeDto::class.java).fromJson(json)!!

        assertEquals(3, node.level)
        assertEquals("الميدان", node.nameAr)
        assertEquals("city-1", node.parentId)
        assertTrue(node.children.isEmpty())
    }

    @Test
    fun lostFoundStateParsesFromServerResponse() {
        val post = moshi.adapter(PostDto::class.java).fromJson(
            """{"id":"post-2","category":"LOST_FOUND","lostFoundState":"FOUND","media":[]}"""
        )!!

        assertEquals("FOUND", post.lostFoundState)
        assertTrue(post.media.isEmpty())
    }

    @Test
    fun eventStatusIsPreservedFromServerResponse() {
        val postponed = moshi.adapter(EventDto::class.java).fromJson(
            """{"id":"event-1","title":"لقاء الحي","status":"POSTPONED"}"""
        )!!
        val active = moshi.adapter(EventDto::class.java).fromJson(
            """{"id":"event-2","title":"سوق الحي","status":"ACTIVE"}"""
        )!!

        assertEquals("POSTPONED", postponed.status)
        assertEquals("ACTIVE", active.status)
    }

    @Test
    fun postPhotoUploadRequestAndSignedResponseMatchMediaControllerContract() {
        val request = RequestPostMediaUpload(
            postId = "post-1",
            contentType = "image/jpeg",
            sizeBytes = 418381L
        )
        val json = moshi.adapter(RequestPostMediaUpload::class.java).toJson(request)

        assertTrue(json.contains("\"postId\":\"post-1\""))
        assertTrue(json.contains("\"contentType\":\"image/jpeg\""))
        assertTrue(json.contains("\"sizeBytes\":418381"))
        assertFalse(json.contains("listingId"))
        assertFalse(json.contains("productId"))

        val signed = moshi.adapter(MediaUploadDto::class.java).fromJson(
            """{"mediaId":"asset-1","objectKey":"posts/post-1/asset-1.jpg","uploadUrl":"https://storage.example.test/signed","urlLifetime":"PT15M"}"""
        )!!

        assertEquals("asset-1", signed.mediaId)
        assertEquals("posts/post-1/asset-1.jpg", signed.objectKey)
        assertEquals("https://storage.example.test/signed", signed.uploadUrl)
        assertEquals("PT15M", signed.urlLifetime)
    }

    @Test
    fun listingSearchPageParsesPricesAndProviderReputation() {
        val type = Types.newParameterizedType(ApiPage::class.java, ListingSummaryDto::class.java)
        val json = """
            {
              "content": [{
                "id": "listing-1",
                "title": "شقة قريبة من الخدمات",
                "category": "REAL_ESTATE",
                "price": 125000.00,
                "currency": "SYP",
                "providerName": "مكتب الحي",
                "providerRating": 4.5,
                "providerReviewCount": 18
              }],
              "pageNumber": 0,
              "pageSize": 20,
              "totalElements": 1,
              "totalPages": 1,
              "last": true
            }
        """.trimIndent()

        val page = moshi.adapter<ApiPage<ListingSummaryDto>>(type).fromJson(json)!!

        assertEquals(1, page.content.size)
        assertEquals("شقة قريبة من الخدمات", page.content.single().title)
        assertEquals(java.math.BigDecimal("125000.00"), page.content.single().price)
        assertEquals("SYP", page.content.single().currency)
        assertEquals(4.5, page.content.single().providerRating!!, 0.0)
        assertEquals(18L, page.content.single().providerReviewCount)
    }

}
