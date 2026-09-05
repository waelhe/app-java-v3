package com.marketplace.media;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link MediaProperties} and — only when every storage credential is
 * bound — the single {@link S3MediaStorage} bean. Spring owns the bean lifecycle:
 * the {@code destroyMethod} close hook shuts the presigner and client down with
 * the context.
 */
@Configuration
@EnableConfigurationProperties(MediaProperties.class)
class MediaConfig {

    @Bean(destroyMethod = "close")
    @Conditional(MediaStorageConfiguredCondition.class)
    S3MediaStorage s3MediaStorage(MediaProperties properties) {
        return new S3MediaStorage(
                properties.storage(),
                properties.limits().presignTtl()
        );
    }
}
