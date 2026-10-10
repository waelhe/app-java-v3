package com.marketplace.android.core.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiModelsTest {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

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
                "media": [],
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
}
