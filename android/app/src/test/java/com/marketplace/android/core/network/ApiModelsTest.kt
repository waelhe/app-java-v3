package com.marketplace.android.core.network

import com.marketplace.android.feature.canChangeEventRsvp
import com.marketplace.android.feature.mergeLostFoundLifecycleState
import com.marketplace.android.feature.parsePriceCents
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
    fun lostFoundPostParsesLifecycleStateExposedByBackendView() {
        val post = moshi.adapter(PostDto::class.java).fromJson(
            """{"id":"post-2","category":"LOST_FOUND","status":"VISIBLE","lostFoundState":"ACTIVE","media":[]}"""
        )!!

        assertEquals("LOST_FOUND", post.category)
        assertEquals("VISIBLE", post.status)
        assertEquals("ACTIVE", post.lostFoundState)
        assertTrue(post.media.isEmpty())
    }

    @Test
    fun eventResponseMatchesCurrentBackendView() {
        val event = moshi.adapter(EventDto::class.java).fromJson(
            """
            {
              "id": "event-1",
              "authorId": "member-1",
              "locationId": "neighborhood-1",
              "category": "SOCIAL",
              "title": "لقاء الحي",
              "description": "لقاء الجيران",
              "startsAt": "2026-10-12T17:00:00Z",
              "endsAt": null,
              "locationLabel": "الساحة",
              "organizerLabel": "لجنة الحي",
              "capacity": 10,
              "registration": "OPEN",
              "status": "ACTIVE",
              "featured": false,
              "attending": 3,
              "rsvpedByMe": true,
              "createdAt": "2026-10-11T09:00:00Z",
              "updatedAt": "2026-10-11T09:00:00Z"
            }
            """.trimIndent()
        )!!

        assertEquals("لقاء الحي", event.title)
        assertEquals("OPEN", event.registration)
        assertEquals("ACTIVE", event.status)
        assertEquals(3L, event.attending)
        assertTrue(event.rsvpedByMe)
    }

    @Test
    fun marketplacePriceConversionRejectsOverflowAndRoundsMinorUnits() {
        assertEquals(1234, parsePriceCents("12.34"))
        assertEquals(1234, parsePriceCents("12,34"))
        assertEquals(556, parsePriceCents("5.555"))
        assertEquals(null, parsePriceCents("42949672.97"))
        assertEquals(null, parsePriceCents("not-a-price"))
    }

    @Test
    fun lifecycleResponseMergePreservesFeedAggregates() {
        val feedPost = PostDto(
            id = "post-3",
            category = "LOST_FOUND",
            title = "محفظة مفقودة",
            lostFoundState = "ACTIVE",
            reactionsCount = 7,
            reactedByMe = true,
            media = listOf(
                PostMediaDto(
                    mediaId = "asset-3",
                    url = "https://media.example.test/wallet.jpg",
                    contentType = "image/jpeg",
                    position = 0
                )
            )
        )
        // The backend's command/write projection contains state but not the
        // grouped feed aggregates; its absent fields map to their DTO defaults.
        val commandResponse = PostDto(
            id = "post-3",
            category = "LOST_FOUND",
            lostFoundState = "FOUND"
        )

        val merged = mergeLostFoundLifecycleState(feedPost, commandResponse)

        assertEquals("FOUND", merged.lostFoundState)
        assertEquals(7L, merged.reactionsCount)
        assertTrue(merged.reactedByMe)
        assertEquals("asset-3", merged.media.single().mediaId)
        assertEquals("محفظة مفقودة", merged.title)
    }

    @Test
    fun cancelledOrUnknownEventsMayOnlyFreeAnExistingRsvp() {
        assertTrue(canChangeEventRsvp(EventDto(status = "ACTIVE", rsvpedByMe = false)))
        assertTrue(canChangeEventRsvp(EventDto(status = "CANCELLED", rsvpedByMe = true)))
        assertTrue(canChangeEventRsvp(EventDto(status = "POSTPONED", rsvpedByMe = true)))
        assertFalse(canChangeEventRsvp(EventDto(status = "CANCELLED", rsvpedByMe = false)))
        assertFalse(canChangeEventRsvp(EventDto(status = "", rsvpedByMe = false)))
    }

    @Test
    fun lostFoundStateCommandMatchesBackendRequestShape() {
        val json = moshi.adapter(UpdateLostFoundStateRequest::class.java)
            .toJson(UpdateLostFoundStateRequest(state = "FOUND"))

        assertEquals("{\"state\":\"FOUND\"}", json)
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
