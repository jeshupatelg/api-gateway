package com.jpg.apigateway.security.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Service to interact with Keycloak Admin REST API for remote session revocation.
 */
@Service
public class KeycloakSessionManagementService {

    private static final Logger log = LoggerFactory.getLogger(KeycloakSessionManagementService.class);

    private final WebClient webClient;
    private final String keycloakInternalUrl;
    private final String keycloakIssuerUri;
    private final String keycloakRealm;
    private final String clientId;
    private final String clientSecret;

    public KeycloakSessionManagementService(
            WebClient.Builder webClientBuilder,
            @Value("${KEYCLOAK_INTERNAL_URL:}") String keycloakInternalUrl,
            @Value("${KEYCLOAK_ISSUER_URI:}") String keycloakIssuerUri,
            @Value("${KEYCLOAK_REALM:}") String keycloakRealm,
            @Value("${KEYCLOAK_CLIENT_ID:apigw-client}") String clientId,
            @Value("${KEYCLOAK_CLIENT_SECRET:}") String clientSecret) {
        this.webClient = webClientBuilder.build();
        this.keycloakInternalUrl = keycloakInternalUrl;
        this.keycloakIssuerUri = keycloakIssuerUri;
        this.keycloakRealm = keycloakRealm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /**
     * Attempts to revoke a specific user session in Keycloak via Admin REST API:
     * DELETE /admin/realms/{realm}/sessions/{sessionId}
     *
     * @param keycloakSid Keycloak session ID ('sid' claim)
     * @return Mono with true if successfully deleted in Keycloak, false otherwise
     */
    public Mono<Boolean> revokeKeycloakSession(String keycloakSid) {
        if (keycloakSid == null || keycloakSid.isBlank() || "N/A".equalsIgnoreCase(keycloakSid)) {
            return Mono.just(false);
        }

        String baseUrl = resolveKeycloakBaseUrl();
        String realm = resolveRealm();
        if (baseUrl == null || baseUrl.isBlank() || realm == null || realm.isBlank()) {
            log.warn("Cannot revoke Keycloak session: Keycloak base URL or realm is not configured.");
            return Mono.just(false);
        }

        if (clientSecret == null || clientSecret.isBlank()) {
            log.warn("Cannot revoke Keycloak session: KEYCLOAK_CLIENT_SECRET is missing.");
            return Mono.just(false);
        }

        String tokenUri = baseUrl + "/realms/" + realm + "/protocol/openid-connect/token";
        String deleteSessionUri = baseUrl + "/admin/realms/" + realm + "/sessions/" + keycloakSid;

        return obtainClientAccessToken(tokenUri)
                .flatMap(accessToken -> webClient.delete()
                        .uri(deleteSessionUri)
                        .header("Authorization", "Bearer " + accessToken)
                        .retrieve()
                        .toBodilessEntity()
                        .map(response -> {
                            log.info("Successfully revoked session '{}' in Keycloak (HTTP {}).", keycloakSid, response.getStatusCode());
                            return true;
                        })
                        .onErrorResume(e -> {
                            log.warn("Failed to delete session '{}' in Keycloak: {}. " +
                                    "Ensure client '{}' has Service Accounts Enabled and 'manage-users' (or realm-admin) role in Keycloak.",
                                    keycloakSid, e.getMessage(), clientId);
                            return Mono.just(false);
                        }))
                .onErrorResume(e -> {
                    log.warn("Failed to obtain client credentials token from Keycloak: {}. Local session was still invalidated.", e.getMessage());
                    return Mono.just(false);
                });
    }

    @SuppressWarnings("unchecked")
    private Mono<String> obtainClientAccessToken(String tokenUri) {
        return webClient.post()
                .uri(tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                        .with("client_id", clientId)
                        .with("client_secret", clientSecret))
                .retrieve()
                .bodyToMono(Map.class)
                .map(map -> {
                    Object token = map.get("access_token");
                    if (token != null) {
                        return token.toString();
                    }
                    throw new IllegalStateException("No access_token returned by Keycloak");
                });
    }

    private String resolveKeycloakBaseUrl() {
        if (keycloakInternalUrl != null && !keycloakInternalUrl.isBlank()) {
            return trimSlash(keycloakInternalUrl);
        }
        if (keycloakIssuerUri != null && !keycloakIssuerUri.isBlank()) {
            String uri = trimSlash(keycloakIssuerUri);
            int idx = uri.indexOf("/realms/");
            if (idx > 0) {
                return uri.substring(0, idx);
            }
            return uri;
        }
        return null;
    }

    private String resolveRealm() {
        if (keycloakRealm != null && !keycloakRealm.isBlank()) {
            return keycloakRealm;
        }
        if (keycloakIssuerUri != null && !keycloakIssuerUri.isBlank()) {
            int idx = keycloakIssuerUri.indexOf("/realms/");
            if (idx > 0) {
                String sub = keycloakIssuerUri.substring(idx + "/realms/".length());
                int slash = sub.indexOf("/");
                return slash > 0 ? sub.substring(0, slash) : sub;
            }
        }
        return "master";
    }

    private String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
