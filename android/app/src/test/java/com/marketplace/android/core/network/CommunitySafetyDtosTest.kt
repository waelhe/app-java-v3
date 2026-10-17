package com.marketplace.android.core.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Android wire models to the existing community report and
 * delegated urgent-alert contracts. These are contract-shape tests, not
 * proof of live server delivery or moderation outcomes.
 */
class CommunitySafetyDtosTest {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun reportRequestMatchesTheSupportedPostAndCommentContract() {
        val request = CreateContentReportRequest(
            targetType = "COMMENT",
            targetId = "22222222-2222-4222-8222-222222222222",
            reason = "HARASSMENT"
        )

        val json = moshi.adapter(CreateContentReportRequest::class.java).toJson(request)

        assertEquals(
            """{"targetType":"COMMENT","targetId":"22222222-2222-4222-8222-222222222222","reason":"HARASSMENT"}""",
            json
        )
    }

    @Test
    fun reportResponseMapsTheBackendQueueProjectionWithoutClaimingAnOutcome() {
        val report = moshi.adapter(ContentReportDto::class.java).fromJson(
            """
            {
              "id": "11111111-1111-4111-8111-111111111111",
              "reporterId": "22222222-2222-4222-8222-222222222222",
              "targetType": "POST",
              "targetId": "33333333-3333-4333-8333-333333333333",
              "reason": "SPAM",
              "status": "OPEN",
              "resolutionNote": null,
              "resolvedBy": null,
              "resolvedAt": null,
              "createdAt": "2026-10-16T10:00:00Z",
              "updatedAt": "2026-10-16T10:00:00Z"
            }
            """.trimIndent()
        )!!

        assertEquals("POST", report.targetType)
        assertEquals("SPAM", report.reason)
        assertEquals("OPEN", report.status)
        assertNull(report.resolutionNote)
        assertNull(report.resolvedBy)
        assertNull(report.resolvedAt)
        assertTrue(report.id.isNotBlank())
    }

    @Test
    fun urgentAlertResponsePreservesVerifiedSourceAndNeighborhoodScopeFields() {
        val json = """
            [{
              "alertId": "11111111-1111-4111-8111-111111111111",
              "sourceId": "22222222-2222-4222-8222-222222222222",
              "sourceName": "الدفاع المدني",
              "sourceType": "CIVIL_DEFENSE",
              "level": "SEVERE",
              "title": "تنبيه للحي",
              "body": "اتبع التعليمات الرسمية.",
              "locationId": "33333333-3333-4333-8333-333333333333",
              "validFrom": "2026-10-16T10:00:00Z",
              "validUntil": "2026-10-16T14:00:00Z",
              "updatedAt": "2026-10-16T10:05:00Z",
              "withdrawn": false,
              "withdrawnAt": null
            }]
        """.trimIndent()
        val listType = Types.newParameterizedType(List::class.java, UrgentAlertDto::class.java)
        val alerts = moshi.adapter<List<UrgentAlertDto>>(listType).fromJson(json)!!

        val alert = alerts.single()
        assertEquals("CIVIL_DEFENSE", alert.sourceType)
        assertEquals("SEVERE", alert.level)
        assertEquals("الدفاع المدني", alert.sourceName)
        assertEquals("33333333-3333-4333-8333-333333333333", alert.locationId)
        assertFalse(alert.withdrawn)
        assertNull(alert.withdrawnAt)
    }
}
