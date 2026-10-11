package com.jpg.apigateway.security.session;

import org.springframework.web.server.WebSession;
import org.springframework.web.server.session.InMemoryWebSessionStore;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Custom in-memory WebSessionStore that applies session timeout (maxIdleTime)
 * to newly created sessions, ensuring alignment with Keycloak SSO session idle timeout.
 */
public class GatewayWebSessionStore extends InMemoryWebSessionStore {

    private final Duration maxIdleTime;

    public GatewayWebSessionStore(Duration maxIdleTime) {
        this.maxIdleTime = maxIdleTime;
    }

    @Override
    public Mono<WebSession> createWebSession() {
        return super.createWebSession().doOnSuccess(session -> {
            if (this.maxIdleTime != null && session != null) {
                session.setMaxIdleTime(this.maxIdleTime);
            }
        });
    }
}
