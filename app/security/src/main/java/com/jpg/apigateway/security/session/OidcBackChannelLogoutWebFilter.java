package com.jpg.apigateway.security.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * High-precedence WebFilter that intercepts OIDC Back-Channel Logout requests from Keycloak,
 * extracts the session identifiers ('sid' and 'sub') from the logout token,
 * immediately invalidates corresponding Gateway WebSessions, and returns HTTP 200 OK.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class OidcBackChannelLogoutWebFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(OidcBackChannelLogoutWebFilter.class);

    private final GatewaySessionService gatewaySessionService;

    public OidcBackChannelLogoutWebFilter(GatewaySessionService gatewaySessionService) {
        this.gatewaySessionService = gatewaySessionService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        HttpMethod method = exchange.getRequest().getMethod();

        boolean isBackchannel = HttpMethod.POST.equals(method) &&
                (path.startsWith("/logout/connect/back-channel") || "/api/v1/portal/backchannel-logout".equals(path));

        if (!isBackchannel) {
            return chain.filter(exchange);
        }

        log.info("[BackChannelLogout] Received back-channel logout request on path '{}'", path);

        return exchange.getFormData()
                .flatMap(formData -> {
                    String logoutToken = formData.getFirst("logout_token");
                    if (logoutToken == null || logoutToken.isBlank()) {
                        log.warn("[BackChannelLogout] Missing 'logout_token' parameter in request");
                        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
                        return exchange.getResponse().setComplete();
                    }

                    String sid = extractClaim(logoutToken, "sid");
                    String sub = extractClaim(logoutToken, "sub");
                    log.info("[BackChannelLogout] Extracted claims from logout_token: sid='{}', sub='{}'", sid, sub);

                    Mono<Void> invalidateTask;
                    if (sid != null && !sid.isBlank()) {
                        invalidateTask = gatewaySessionService.invalidateByKeycloakSid(sid);
                    } else if (sub != null && !sub.isBlank()) {
                        invalidateTask = gatewaySessionService.invalidateBySubject(sub);
                    } else {
                        invalidateTask = Mono.empty();
                    }

                    return invalidateTask.then(Mono.defer(() -> {
                        exchange.getResponse().setStatusCode(HttpStatus.OK);
                        exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-cache, no-store");
                        exchange.getResponse().getHeaders().set(HttpHeaders.PRAGMA, "no-cache");
                        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        log.info("[BackChannelLogout] Successfully processed and responded HTTP 200 OK to Keycloak");
                        return exchange.getResponse().setComplete();
                    }));
                })
                .onErrorResume(e -> {
                    log.error("[BackChannelLogout] Error processing back-channel logout: {}", e.getMessage(), e);
                    // Return 200 OK so Keycloak doesn't continually retry or flag client
                    exchange.getResponse().setStatusCode(HttpStatus.OK);
                    return exchange.getResponse().setComplete();
                });
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

    private static String extractClaim(String jwt, String claimKey) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length >= 2) {
                byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
                com.fasterxml.jackson.databind.JsonNode root = OBJECT_MAPPER.readTree(decoded);
                com.fasterxml.jackson.databind.JsonNode node = root.get(claimKey);
                if (node != null && !node.isNull()) {
                    return node.asText();
                }
            }
        } catch (Exception e) {
            log.warn("[BackChannelLogout] Failed to extract claim '{}' from JWT: {}", claimKey, e.getMessage());
        }
        return null;
    }
}
