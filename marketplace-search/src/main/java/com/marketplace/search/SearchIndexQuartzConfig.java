package com.marketplace.search;

import org.quartz.JobDetail;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.JobDetailFactoryBean;
import org.springframework.scheduling.quartz.SimpleTriggerFactoryBean;

@Configuration
public class SearchIndexQuartzConfig {

    @Bean
    public JobDetailFactoryBean searchIndexRefreshJobDetail() {
        JobDetailFactoryBean factoryBean = new JobDetailFactoryBean();
        factoryBean.setJobClass(SearchIndexRefresher.class);
        factoryBean.setName("searchIndexRefreshJob");
        factoryBean.setDescription("Refresh mv_listing_search materialized view");
        factoryBean.setDurability(true);
        return factoryBean;
    }

    @Bean
    public SimpleTriggerFactoryBean searchIndexRefreshTrigger(JobDetail searchIndexRefreshJobDetail) {
        SimpleTriggerFactoryBean trigger = new SimpleTriggerFactoryBean();
        trigger.setJobDetail(searchIndexRefreshJobDetail);
        trigger.setName("searchIndexRefreshTrigger");
        trigger.setStartDelay(60_000L);
        trigger.setRepeatInterval(300_000L);
        trigger.setRepeatCount(org.quartz.SimpleTrigger.REPEAT_INDEFINITELY);
        trigger.setMisfireInstruction(org.quartz.SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT);
        return trigger;
    }
}
