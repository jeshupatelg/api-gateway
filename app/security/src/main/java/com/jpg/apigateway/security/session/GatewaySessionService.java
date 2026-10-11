package com.jpg.apigateway.security.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

/**
 * Service managing active API Gateway user sessions.
 * Provides active session querying, remote Keycloak invalidation, and back-channel eviction.
 */
@Service
public class GatewaySessionService {

    private static final Logger log = LoggerFactory.getLogger(GatewaySessionService.class);

    private final GatewayWebSessionStore webSessionStore;
    private final KeycloakSessionManagementService keycloakSessionManagementService;

    public GatewaySessionService(
            GatewayWebSessionStore webSessionStore,
            KeycloakSessionManagementService keycloakSessionManagementService) {
        this.webSessionStore = webSessionStore;
        this.keycloakSessionManagementService = keycloakSessionManagementService;
    }

    public record RevokeResponse(boolean success, boolean keycloakRevoked, String message) {}

    /**
     * Returns a list of all active, authenticated sessions.
     *
     * @param currentSessionId current requester session ID for marking current session
     * @return list of active session DTOs sorted by last access time descending
     */
    public List<GatewaySessionDto> getActiveSessions(String currentSessionId) {
        Map<String, WebSession> sessionMap = webSessionStore.getSessions();
        List<GatewaySessionDto> activeList = new ArrayList<>();

        for (WebSession session : sessionMap.values()) {
            if (session.isExpired()) {
                continue;
            }

            Object secCtxObj = session.getAttribute("SPRING_SECURITY_CONTEXT");
            if (!(secCtxObj instanceof SecurityContext secCtx)) {
                continue;
            }

            Authentication auth = secCtx.getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                continue;
            }

            String username = auth.getName();
            String displayName = username;
            String email = null;
            String keycloakSid = (String) session.getAttribute("keycloakSid");

            if (auth.getPrincipal() instanceof OidcUser oidcUser) {
                if (keycloakSid == null || keycloakSid.isBlank()) {
                    keycloakSid = (String) oidcUser.getClaims().get("sid");
                }
                String pref = oidcUser.getPreferredUsername();
                if (pref != null && !pref.isBlank()) username = pref;
                String name = (String) oidcUser.getClaims().get("name");
                if (name != null && !name.isBlank()) displayName = name;
                email = oidcUser.getEmail();
            } else if (auth.getPrincipal() instanceof OAuth2User oauth2User) {
                Object pref = oauth2User.getAttributes().get("preferred_username");
                if (pref != null) username = pref.toString();
                Object name = oauth2User.getAttributes().get("name");
                if (name != null) displayName = name.toString();
                Object em = oauth2User.getAttributes().get("email");
                if (em != null) email = em.toString();
            }

            List<String> roles = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(Objects::nonNull)
                    .map(a -> a.startsWith("ROLE_") ? a.substring("ROLE_".length()).toLowerCase(Locale.ROOT) : a.toLowerCase(Locale.ROOT))
                    .toList();

            String clientIp = (String) session.getAttribute("clientIp");
            if (clientIp == null || clientIp.isBlank()) clientIp = "Unknown";

            String userAgent = (String) session.getAttribute("userAgent");
            if (userAgent == null || userAgent.isBlank()) userAgent = "Unknown";

            Instant loginTime = (Instant) session.getAttribute("loginTime");
            if (loginTime == null) loginTime = session.getCreationTime();

            Instant lastAccessTime = session.getLastAccessTime();

            boolean isCurrent = currentSessionId != null && currentSessionId.equals(session.getId());

            activeList.add(new GatewaySessionDto(
                    session.getId(),
                    keycloakSid != null ? keycloakSid : "N/A",
                    username,
                    displayName,
                    email,
                    roles,
                    clientIp,
                    userAgent,
                    loginTime,
                    lastAccessTime,
                    isCurrent
            ));
        }

        activeList.sort((a, b) -> b.lastAccessTime().compareTo(a.lastAccessTime()));
        return activeList;
    }

    /**
     * Revokes a session: invalidates local WebSession and revokes remote Keycloak session.
     */
    public Mono<RevokeResponse> revokeSession(String sessionId) {
        WebSession session = webSessionStore.getSessions().get(sessionId);
        if (session == null) {
            return Mono.just(new RevokeResponse(false, false, "Session not found or already expired."));
        }

        String keycloakSid = (String) session.getAttribute("keycloakSid");
        if (keycloakSid == null) {
            Object secCtxObj = session.getAttribute("SPRING_SECURITY_CONTEXT");
            if (secCtxObj instanceof SecurityContext secCtx && secCtx.getAuthentication() != null
                    && secCtx.getAuthentication().getPrincipal() instanceof OidcUser oidcUser) {
                keycloakSid = (String) oidcUser.getClaims().get("sid");
            }
        }

        final String finalSid = keycloakSid;

        return webSessionStore.removeSession(sessionId)
                .then(session.invalidate())
                .then(Mono.defer(() -> {
                    if (finalSid != null && !finalSid.isBlank() && !"N/A".equalsIgnoreCase(finalSid)) {
                        return keycloakSessionManagementService.revokeKeycloakSession(finalSid)
                                .map(kcRevoked -> {
                                    if (kcRevoked) {
                                        return new RevokeResponse(true, true, "Session successfully revoked in API Gateway and Keycloak.");
                                    } else {
                                        return new RevokeResponse(true, false, "Session revoked in API Gateway. (Keycloak remote revocation requires service account 'manage-users' role).");
                                    }
                                });
                    }
                    return Mono.just(new RevokeResponse(true, false, "Session revoked in API Gateway."));
                }));
    }

    /**
     * Revokes all sessions other than the current session.
     */
    public Mono<Map<String, Object>> revokeAllOtherSessions(String currentSessionId) {
        List<GatewaySessionDto> sessions = getActiveSessions(currentSessionId);
        List<Mono<RevokeResponse>> revokeMonos = new ArrayList<>();

        for (GatewaySessionDto s : sessions) {
            if (!s.current()) {
                revokeMonos.add(revokeSession(s.sessionId()));
            }
        }

        if (revokeMonos.isEmpty()) {
            return Mono.just(Map.of("revokedCount", 0, "message", "No other active sessions to revoke."));
        }

        return Mono.when(revokeMonos)
                .thenReturn(Map.of("revokedCount", revokeMonos.size(), "message", "Successfully revoked " + revokeMonos.size() + " session(s)."));
    }

    /**
     * Invalidates any local session matching the given Keycloak session ID (Back-Channel Logout helper).
     */
    public Mono<Void> invalidateByKeycloakSid(String keycloakSid) {
        if (keycloakSid == null || keycloakSid.isBlank()) {
            return Mono.empty();
        }

        List<Mono<Void>> invalidations = new ArrayList<>();
        for (WebSession session : webSessionStore.getSessions().values()) {
            String sid = (String) session.getAttribute("keycloakSid");
            if (sid == null) {
                Object secCtxObj = session.getAttribute("SPRING_SECURITY_CONTEXT");
                if (secCtxObj instanceof SecurityContext secCtx && secCtx.getAuthentication() != null
                        && secCtx.getAuthentication().getPrincipal() instanceof OidcUser oidcUser) {
                    sid = (String) oidcUser.getClaims().get("sid");
                }
            }

            if (keycloakSid.equals(sid)) {
                log.info("Back-channel invalidating local WebSession '{}' for Keycloak SID '{}'", session.getId(), keycloakSid);
                invalidations.add(webSessionStore.removeSession(session.getId()).then(session.invalidate()));
            }
        }

        return Mono.when(invalidations);
    }
}
