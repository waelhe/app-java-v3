package com.marketplace.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * W5 (yelp-level plan §5 — G24): the recorded click's counter — the
 * ListingViewCounter contract verbatim, on the campaign attribution: the
 * first-writer-wins marker, the repeat-click non-count, the unpromoted
 * 404 no-op, and the undercount-never-inflate degradation rules.
 */
class AdClickCounterTest {

    private static final UUID LISTING_ID = UUID.randomUUID();
    private static final String IP = "203.0.113.7";
    private static final Duration WINDOW = Duration.ofDays(1);

    @SuppressWarnings("unchecked")
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final AdClicksDailyService clicksService = mock(AdClicksDailyService.class);
    private final AdCampaignRepository campaignRepository = mock(AdCampaignRepository.class);
    private final CatalogProperties properties = new CatalogProperties(
            new CatalogProperties.Expiry(90, 1),
            new CatalogProperties.Seo("", "/listings/{id}", "/categories/{code}", java.util.List.of()),
            new CatalogProperties.Views("test-key", WINDOW), new CatalogProperties.Ads(java.time.Duration.ofDays(1)));

    private AdClickCounter counter() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        return new AdClickCounter(redisTemplate, clicksService, campaignRepository, properties);
    }

    private AdCampaign activeCampaign() {
        return AdCampaign.start(UUID.randomUUID(), LISTING_ID, 100000L, 100L, 5L, "SAR",
                java.time.Instant.now(), null);
    }

    @Test
    void attributedClick_marksAndCounts() {
        AdCampaign campaign = activeCampaign();
        when(campaignRepository.findFirstByListingIdAndStatusOrderByIdAsc(LISTING_ID, AdCampaignStatus.ACTIVE))
                .thenReturn(Optional.of(campaign));
        when(valueOps.setIfAbsent(anyString(), eq("1"), eq(WINDOW))).thenReturn(true);

        Optional<UUID> attributed = counter().recordClick(LISTING_ID, IP);

        org.junit.jupiter.api.Assertions.assertEquals(Optional.of(campaign.getId()), attributed);
        String expectedKey = AdClickCounter.DEDUP_KEY_PREFIX + campaign.getId() + ":"
                + ListingViewCounter.hashIp("test-key", IP);
        verify(valueOps).setIfAbsent(expectedKey, "1", WINDOW);
        verify(clicksService).addClick(campaign.getId(), LocalDate.now(ZoneOffset.UTC));
    }

    @Test
    void repeatClickWithinTheWindow_isNotCountedAgain() {
        AdCampaign campaign = activeCampaign();
        when(campaignRepository.findFirstByListingIdAndStatusOrderByIdAsc(LISTING_ID, AdCampaignStatus.ACTIVE))
                .thenReturn(Optional.of(campaign));
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        Optional<UUID> attributed = counter().recordClick(LISTING_ID, IP);

        org.junit.jupiter.api.Assertions.assertEquals(Optional.of(campaign.getId()), attributed);
        verify(clicksService, never()).addClick(any(), any());
    }

    @Test
    void unpromotedListing_answersTheHonestEmptyNoOp() {
        // The click on an unpromoted listing is nobody's to bill — the
        // controller's 404 source, and nothing was counted.
        when(campaignRepository.findFirstByListingIdAndStatusOrderByIdAsc(LISTING_ID, AdCampaignStatus.ACTIVE))
                .thenReturn(Optional.empty());

        Optional<UUID> attributed = counter().recordClick(LISTING_ID, IP);

        org.junit.jupiter.api.Assertions.assertTrue(attributed.isEmpty());
        verifyNoInteractions(clicksService);
    }

    @Test
    void exhaustedCampaignIsNotClickable() {
        // «المُروَّج بلا ميزانية لا يتصدر» — and its clicks are nobody's
        // either: an exhausted campaign accrues nothing from the click side.
        AdCampaign exhausted = activeCampaign();
        exhausted.consume(100000L); // budget fully consumed → ENDED
        when(campaignRepository.findFirstByListingIdAndStatusOrderByIdAsc(LISTING_ID, AdCampaignStatus.ACTIVE))
                .thenReturn(Optional.empty()); // ENDED is not ACTIVE — the read says so

        Optional<UUID> attributed = counter().recordClick(LISTING_ID, IP);

        org.junit.jupiter.api.Assertions.assertTrue(attributed.isEmpty());
        verifyNoInteractions(clicksService);
    }

    @Test
    void nullRemoteAddress_isAttributedButUncounted() {
        // The unified unavailability rule: no address = no fingerprint —
        // undercount (skip), never inflate, never break the caller.
        AdCampaign campaign = activeCampaign();
        when(campaignRepository.findFirstByListingIdAndStatusOrderByIdAsc(LISTING_ID, AdCampaignStatus.ACTIVE))
                .thenReturn(Optional.of(campaign));

        Optional<UUID> attributed = counter().recordClick(LISTING_ID, null);

        org.junit.jupiter.api.Assertions.assertEquals(Optional.of(campaign.getId()), attributed);
        verify(clicksService, never()).addClick(any(), any());
    }
}
