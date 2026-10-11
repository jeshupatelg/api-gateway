package com.jpg.apigateway.controller;

import com.jpg.apigateway.config.DynamicRouteDefinitionRepositoryService;
import com.jpg.apigateway.mapper.GatewayRouteMapper;
import com.jpg.apigateway.security.config.GatewaySecurityProperties;
import com.jpg.apigateway.security.service.DynamicSecurityCacheService;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;
import com.jpg.apigateway.security.session.GatewaySessionDto;
import com.jpg.apigateway.security.session.GatewaySessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * REST controller for the Gateway Launchpad / Portal.
 * Provides current user context and dynamic, role-filtered route discovery.
 */
@RestController
@RequestMapping("/api/v1/portal")
public class PortalController {

    private final DynamicRouteDefinitionRepositoryService dynamicRouteDefinitionRepositoryService;
    private final DynamicSecurityCacheService dynamicSecurityCacheService;
    private final GatewaySecurityProperties staticProperties;
    private final GatewaySessionService gatewaySessionService;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    public PortalController(DynamicRouteDefinitionRepositoryService dynamicRouteDefinitionRepositoryService,
                            DynamicSecurityCacheService dynamicSecurityCacheService,
                            GatewaySecurityProperties staticProperties,
                            GatewaySessionService gatewaySessionService) {
        this.dynamicRouteDefinitionRepositoryService = dynamicRouteDefinitionRepositoryService;
        this.dynamicSecurityCacheService = dynamicSecurityCacheService;
        this.staticProperties = staticProperties;
        this.gatewaySessionService = gatewaySessionService;
    }

    public record PortalUserDto(
            String username,
            String displayName,
            String email,
            List<String> roles,
            boolean isAdmin
    ) {}

    public record PortalRouteDto(
            String id,
            String title,
            String description,
            String category,
            String icon,
            String pathPattern,
            String launchUrl,
            String targetUri,
            boolean enabled
    ) {}

    /**
     * Returns current authenticated user information and granted realm roles.
     */
    @GetMapping("/me")
    public Mono<PortalUserDto> getCurrentUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return Mono.just(new PortalUserDto("anonymous", "Anonymous", null, List.of(), false));
        }

        Map<String, Object> attributes = extractAttributes(auth.getPrincipal());

        String username = extractClaim(attributes, "preferred_username");
        if (username == null) {
            username = extractClaim(attributes, "username");
        }
        if (username == null) {
            username = auth.getName();
        }

        String displayName = extractClaim(attributes, "name");
        if (displayName == null) {
            String given = extractClaim(attributes, "given_name");
            String family = extractClaim(attributes, "family_name");
            if (given != null || family != null) {
                displayName = ((given != null ? given : "") + " " + (family != null ? family : "")).trim();
            }
        }
        if (displayName == null || displayName.isBlank()) {
            displayName = username;
        }

        String email = extractClaim(attributes, "email");

        List<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()).toLowerCase(Locale.ROOT))
                .toList();

        boolean isAdmin = roles.contains("admin");
        return Mono.just(new PortalUserDto(username, displayName, email, roles, isAdmin));
    }

    private Map<String, Object> extractAttributes(Object principal) {
        if (principal instanceof OAuth2User oauth2User) {
            return oauth2User.getAttributes();
        } else if (principal != null) {
            try {
                var method = principal.getClass().getMethod("getClaims");
                Object result = method.invoke(principal);
                if (result instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> casted = (Map<String, Object>) m;
                    return casted;
                }
            } catch (Exception ignored) {
                try {
                    var method = principal.getClass().getMethod("getAttributes");
                    Object result = method.invoke(principal);
                    if (result instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> casted = (Map<String, Object>) m;
                        return casted;
                    }
                } catch (Exception ignored2) {}
            }
        }
        return Map.of();
    }

    private String extractClaim(Map<String, Object> claims, String key) {
        if (claims != null && claims.containsKey(key)) {
            Object val = claims.get(key);
            if (val != null && !String.valueOf(val).isBlank()) {
                return String.valueOf(val).trim();
            }
        }
        return null;
    }

    /**
     * Returns all enabled routes that the authenticated user has permissions to access based on their roles.
     */
    @GetMapping(value = "/routes", produces = MediaType.APPLICATION_JSON_VALUE)
    public Flux<PortalRouteDto> getAccessibleRoutes(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return Flux.empty();
        }

        List<String> userRoles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .map(a -> a.startsWith("ROLE_") ? a.substring("ROLE_".length()) : a)
                .map(a -> a.toLowerCase(Locale.ROOT))
                .toList();
        boolean isAdmin = userRoles.contains("admin");

        Map<String, List<String>> rolePaths = dynamicSecurityCacheService.getRolePathAccess();
        if (rolePaths == null || rolePaths.isEmpty()) {
            rolePaths = staticProperties.getRolePathAccess();
        }
        final Map<String, List<String>> effectiveRolePaths = rolePaths != null ? rolePaths : Map.of();

        List<String> publicPaths = dynamicSecurityCacheService.getPublicPaths();
        if (publicPaths.isEmpty()) {
            publicPaths = staticProperties.getPublicPaths();
        }
        final List<String> effectivePublicPaths = publicPaths != null ? publicPaths : List.of();

        return dynamicRouteDefinitionRepositoryService.getAllRoutes()
                .filter(this::isRouteEnabled)
                .filter(rd -> isAuthorized(rd, userRoles, isAdmin, effectiveRolePaths, effectivePublicPaths))
                .map(this::toPortalDto);
    }

    private boolean isAuthorized(RouteDefinition rd, List<String> userRoles, boolean isAdmin,
                                 Map<String, List<String>> rolePaths, List<String> publicPaths) {
        if (isAdmin) {
            return true;
        }

        String pathPattern = extractPathPattern(rd);
        if (pathPattern == null) {
            return false;
        }

        for (String pub : publicPaths) {
            if (pub != null && (antPathMatcher.match(pub, pathPattern) || antPathMatcher.match(pathPattern, pub))) {
                return true;
            }
        }

        for (String userRole : userRoles) {
            List<String> allowedPatterns = rolePaths.get(userRole.toLowerCase(Locale.ROOT));
            if (allowedPatterns == null) {
                allowedPatterns = rolePaths.get("role_" + userRole.toLowerCase(Locale.ROOT));
            }
            if (allowedPatterns == null) {
                for (Map.Entry<String, List<String>> entry : rolePaths.entrySet()) {
                    String k = entry.getKey();
                    if (k != null) {
                        String cleanK = k.startsWith("ROLE_") || k.startsWith("role_") ? k.substring(5) : k;
                        if (cleanK.equalsIgnoreCase(userRole)) {
                            allowedPatterns = entry.getValue();
                            break;
                        }
                    }
                }
            }
            if (allowedPatterns != null) {
                for (String allowed : allowedPatterns) {
                    if (allowed != null && (antPathMatcher.match(allowed, pathPattern) || antPathMatcher.match(pathPattern, allowed))) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private String extractPathPattern(RouteDefinition rd) {
        if (rd.getPredicates() != null) {
            for (PredicateDefinition pd : rd.getPredicates()) {
                if ("Path".equalsIgnoreCase(pd.getName()) && pd.getArgs() != null && !pd.getArgs().isEmpty()) {
                    if (pd.getArgs().containsKey("pattern")) {
                        return pd.getArgs().get("pattern");
                    }
                    if (pd.getArgs().containsKey("patterns")) {
                        return pd.getArgs().get("patterns");
                    }
                    if (pd.getArgs().containsKey("_genkey_0")) {
                        return pd.getArgs().get("_genkey_0");
                    }
                    return pd.getArgs().values().stream().findFirst().orElse(null);
                }
            }
        }
        if (rd.getMetadata() != null) {
            if (rd.getMetadata().containsKey("pathPattern") && rd.getMetadata().get("pathPattern") != null) {
                return String.valueOf(rd.getMetadata().get("pathPattern"));
            }
            if (rd.getMetadata().containsKey("path") && rd.getMetadata().get("path") != null) {
                return String.valueOf(rd.getMetadata().get("path"));
            }
        }
        return null;
    }

    private PortalRouteDto toPortalDto(RouteDefinition rd) {
        Map<String, Object> meta = rd.getMetadata() != null ? rd.getMetadata() : Map.of();
        String pathPattern = extractPathPattern(rd);
        String title = meta.get("title") instanceof String s && !s.isBlank() ? s : rd.getId();
        String desc = meta.get("description") instanceof String s && !s.isBlank() ? s : "Gateway service route for " + rd.getId();
        String category = meta.get("category") instanceof String s && !s.isBlank() ? s : "Applications";
        
        String icon = meta.get("icon") instanceof String s && !s.isBlank() ? s : null;
        if (icon == null && rd.getId() != null) {
            String lowerId = rd.getId().toLowerCase(Locale.ROOT);
            if (lowerId.contains("ticket")) {
                icon = "ticketing";
            } else if (lowerId.contains("keycloak") || lowerId.contains("auth")) {
                icon = "keycloak";
            } else if (lowerId.contains("jenkins") || lowerId.contains("ci")) {
                icon = "jenkins";
            } else {
                icon = "service";
            }
        } else if (icon == null) {
            icon = "service";
        }

        String launchUrl = meta.get("launchUrl") instanceof String s && !s.isBlank() ? s : null;
        if (launchUrl == null && pathPattern != null && !pathPattern.isBlank()) {
            launchUrl = pathPattern.replace("/**", "/").replace("/*", "/");
        } else if (launchUrl == null) {
            launchUrl = "/" + rd.getId() + "/";
        }

        return new PortalRouteDto(
                rd.getId(),
                title,
                desc,
                category,
                icon,
                pathPattern != null ? pathPattern : "",
                launchUrl,
                rd.getUri() != null ? rd.getUri().toString() : "",
                true
        );
    }

    private boolean isRouteEnabled(RouteDefinition rd) {
        if (rd == null) {
            return false;
        }
        if (rd.getMetadata() == null) {
            return true;
        }
        Object enabledObj = rd.getMetadata().get(GatewayRouteMapper.METADATA_ENABLED);
        if (enabledObj == null) {
            return true;
        }
        if (enabledObj instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(enabledObj));
    }

    /**
     * Returns a list of all active user sessions (Admin only).
     */
    @GetMapping(value = "/admin/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<List<GatewaySessionDto>>> getActiveSessions(Authentication auth, ServerWebExchange exchange) {
        if (!isUserAdmin(auth)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }
        return exchange.getSession()
                .map(WebSession::getId)
                .defaultIfEmpty("")
                .map(currentSessionId -> ResponseEntity.ok(gatewaySessionService.getActiveSessions(currentSessionId)));
    }

    /**
     * Revokes a specific active session by ID (Admin only).
     */
    @DeleteMapping(value = "/admin/sessions/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> revokeSession(@PathVariable("sessionId") String sessionId, Authentication auth) {
        if (!isUserAdmin(auth)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }
        return gatewaySessionService.revokeSession(sessionId)
                .map(res -> {
                    Map<String, Object> body = new HashMap<>();
                    body.put("success", res.success());
                    body.put("keycloakRevoked", res.keycloakRevoked());
                    body.put("message", res.message());
                    return ResponseEntity.ok(body);
                });
    }

    /**
     * Revokes all active sessions other than the current session (Admin only).
     */
    @PostMapping(value = "/admin/sessions/revoke-all-others", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> revokeAllOtherSessions(Authentication auth, ServerWebExchange exchange) {
        if (!isUserAdmin(auth)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }
        return exchange.getSession()
                .map(WebSession::getId)
                .defaultIfEmpty("")
                .flatMap(gatewaySessionService::revokeAllOtherSessions)
                .map(ResponseEntity::ok);
    }

    /**
     * Optional custom back-channel logout receiver: parses logout_token and revokes local session.
     */
    @PostMapping(value = "/backchannel-logout", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Mono<ResponseEntity<Void>> handleBackchannelLogout(ServerWebExchange exchange) {
        return exchange.getFormData()
                .flatMap(formData -> {
                    String logoutToken = formData.getFirst("logout_token");
                    if (logoutToken != null && !logoutToken.isBlank()) {
                        try {
                            String[] parts = logoutToken.split("\\.");
                            if (parts.length >= 2) {
                                String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
                                if (payloadJson.contains("\"sid\"")) {
                                    int sidIdx = payloadJson.indexOf("\"sid\"");
                                    int colonIdx = payloadJson.indexOf(":", sidIdx);
                                    int quote1 = payloadJson.indexOf("\"", colonIdx);
                                    int quote2 = payloadJson.indexOf("\"", quote1 + 1);
                                    if (quote1 > 0 && quote2 > quote1) {
                                        String sid = payloadJson.substring(quote1 + 1, quote2);
                                        return gatewaySessionService.invalidateByKeycloakSid(sid)
                                                .thenReturn(ResponseEntity.ok().<Void>build());
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    return Mono.just(ResponseEntity.ok().<Void>build());
                });
    }

    private boolean isUserAdmin(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .map(a -> a.startsWith("ROLE_") ? a.substring("ROLE_".length()) : a)
                .anyMatch(a -> a.equalsIgnoreCase("admin"));
    }
}
