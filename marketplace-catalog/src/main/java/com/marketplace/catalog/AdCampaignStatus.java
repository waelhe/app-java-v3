package com.marketplace.catalog;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * W5 (yelp-level plan §5 — the ads & billing wave, G24): the paid
 * promotion's lifecycle — the plan's «حملات (ad_campaigns: ميزانية، تسعير
 * نقرة/ظهور، مدة)».
 */
public enum AdCampaignStatus {
    /** Spending: the listing carries the paid boost, impressions and clicks accrue and bill. */
    ACTIVE,
    /** The owner's hold: no boost, no accrual — the paused gap never bills (see {@code AdBillingBatchExecutor}). */
    PAUSED,
    /** Terminal: the budget ran out («حملة بميزانية تنتهي بنفادها») or the duration ({@code ends_at}) passed. */
    ENDED
}
