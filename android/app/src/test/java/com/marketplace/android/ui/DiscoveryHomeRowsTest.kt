package com.marketplace.android.ui

import com.marketplace.android.core.network.DiscoveryRowDto
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryHomeRowsTest {
    private val urgentAlerts = DiscoveryRowDto(
        row = "URGENT_ALERTS",
        cards = emptyList(),
        totalEligible = 1
    )
    private val lostFound = DiscoveryRowDto(
        row = "LOST_FOUND",
        cards = emptyList(),
        totalEligible = 2
    )

    @Test
    fun hidesUrgentAlertsRailWhenAuthoritativeHomeAlertCardIsVisible() {
        val rows = homeDiscoveryRows(
            rows = listOf(urgentAlerts, lostFound),
            hasDetailedOfficialAlert = true
        )

        assertEquals(listOf(lostFound), rows)
        assertEquals(0, rows.count { it.row.equals("URGENT_ALERTS", ignoreCase = true) })
    }

    @Test
    fun keepsUrgentAlertsRailAsFallbackWhenDetailedAlertIsUnavailable() {
        val rows = homeDiscoveryRows(
            rows = listOf(urgentAlerts, lostFound),
            hasDetailedOfficialAlert = false
        )

        assertEquals(listOf(urgentAlerts, lostFound), rows)
        assertEquals(1, rows.count { it.row.equals("URGENT_ALERTS", ignoreCase = true) })
    }
}
