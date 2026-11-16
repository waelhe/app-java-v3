package com.marketplace.delivery;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
/** Uses Boot's application-wide scheduling infrastructure; properties bind through Boot. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeliveryProperties.class)
public class DeliveryConfiguration { }
