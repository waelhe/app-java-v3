package com.marketplace.android.core.network

import com.squareup.moshi.JsonClass

/**
 * A discovery card is a bounded projection of its original source record.
 * It is a preview only; it does not grant the client authority to infer
 * source details or execute source-specific actions.
 */
@JsonClass(generateAdapter = false)
data class DiscoveryCardDto(
    val sourceType: String = "",
    val sourceId: String = "",
    val updatedAt: String = "",
    val title: String = "",
    val snippet: String = "",
    val state: String? = null,
    val scopeLocationId: String? = null,
    val reason: String = "",
    val paid: Boolean? = null
)

@JsonClass(generateAdapter = false)
data class DiscoveryRowDto(
    val row: String = "",
    val cards: List<DiscoveryCardDto> = emptyList(),
    val totalEligible: Long = 0
)

@JsonClass(generateAdapter = false)
data class DiscoveryImpressionRequest(
    val row: String,
    val sourceType: String,
    val sourceId: String
)
