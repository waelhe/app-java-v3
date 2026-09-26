package com.marketplace.messaging;

import com.marketplace.shared.config.MarketplaceProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final MarketplaceProperties properties;
    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;

    public WebSocketConfig(MarketplaceProperties properties,
                           JwtDecoder jwtDecoder,
                           JwtAuthenticationConverter jwtAuthenticationConverter) {
        this.properties = properties;
        this.jwtDecoder = jwtDecoder;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOrigins(properties.cors().allowedOrigins().toArray(String[]::new));
    }

    /**
     * S4/N3 root fix (comprehensive repair plan §10/2.1): the documented
     * registration point for token-based STOMP authentication — "Process
     * the authentication headers with a ChannelInterceptor" (Spring
     * Framework Reference › STOMP › Token Authentication, verbatim, saved
     * at {@code scripts/doc-verify/ws/framework-stomp-token-based.html}).
     * The interceptor only lifts a supplied CONNECT-frame bearer token;
     * handshake-level and session authentications pass it untouched, and
     * the message authorization manager stays the authorization boundary.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(
                new JwtChannelAuthenticationInterceptor(jwtDecoder, jwtAuthenticationConverter));
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(128 * 1024);
        registry.setSendBufferSizeLimit(512 * 1024);
        registry.setSendTimeLimit(15 * 1000);
        registry.setTimeToFirstMessage(30000);
    }
}
