package com.ulp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-WebSocket configuration for real-time messaging (Epic #13, ULP-8.3).
 *
 * <p>The handshake at {@code /ws} rides the existing form-login HTTP session, so
 * the Spring Security principal is already present — no separate WS token is
 * needed (design decision D4). {@code SecurityConfig} requires authentication on
 * {@code /ws/**}. Messages are pushed to a specific user via
 * {@code SimpMessagingTemplate.convertAndSendToUser(email, "/queue/messages", ...)},
 * which resolves through the {@code /user} destination prefix registered here.
 *
 * <p>Uses the in-memory simple broker ({@code /topic}, {@code /queue}). This is
 * single-instance only; a full broker relay is a later scaling step (out of scope).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // SockJS fallback for browsers without native WebSocket support.
        registry.addEndpoint("/ws").withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        // convertAndSendToUser targets /user/{username}/queue/messages under this prefix.
        config.setUserDestinationPrefix("/user");
    }
}
