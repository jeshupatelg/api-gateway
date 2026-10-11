package com.jpg.apigateway.security.session;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Filter that captures client metadata (real remote IP, User-Agent, Keycloak session ID)
 * and stores it on the active WebSession for administration, audit, and UI visibility.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class GatewaySessionActivityFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange).then(Mono.defer(() -> {
            return exchange.getSession().flatMap(session -> {
                if (session.isStarted()) {
                    // 1. Client IP (respecting X-Forwarded-For & X-Real-IP)
                    if (!session.getAttributes().containsKey("clientIp")) {
                        String ip = extractClientIp(exchange.getRequest());
                        session.getAttributes().put("clientIp", ip);
                    }
                    // 2. User Agent
                    if (!session.getAttributes().containsKey("userAgent")) {
                        String ua = exchange.getRequest().getHeaders().getFirst(HttpHeaders.USER_AGENT);
                        session.getAttributes().put("userAgent", ua != null && !ua.isBlank() ? ua : "Unknown");
                    }
                    // 3. Login timestamp
                    if (!session.getAttributes().containsKey("loginTime")) {
                        session.getAttributes().put("loginTime", session.getCreationTime());
                    }

                    // 4. Capture Keycloak SID if authenticated
                    if (!session.getAttributes().containsKey("keycloakSid")) {
                        return ReactiveSecurityContextHolder.getContext()
                                .doOnNext(secCtx -> {
                                    if (secCtx.getAuthentication() instanceof OAuth2AuthenticationToken token
                                            && token.getPrincipal() instanceof OidcUser oidcUser) {
                                        String sid = (String) oidcUser.getClaims().get("sid");
                                        if (sid != null && !sid.isBlank()) {
                                            session.getAttributes().put("keycloakSid", sid);
                                        }
                                    }
                                })
                                .then();
                    }
                }
                return Mono.empty();
            });
        }));
    }

    /**
     * Extracts client IP address, prioritizing proxy headers (X-Forwarded-For, X-Real-IP)
     * before falling back to the direct socket address.
     */
    public static String extractClientIp(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            if (parts.length > 0 && !parts[0].isBlank()) {
                return parts[0].trim();
            }
        }
        String xRealIp = request.getHeaders().getFirst("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        if (request.getRemoteAddress() != null && request.getRemoteAddress().getAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }
        return "Unknown";
    }
}
