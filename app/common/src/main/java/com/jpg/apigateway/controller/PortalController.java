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
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    public PortalController(DynamicRouteDefinitionRepositoryService dynamicRouteDefinitionRepositoryService,
                            DynamicSecurityCacheService dynamicSecurityCacheService,
                            GatewaySecurityProperties staticProperties) {
        this.dynamicRouteDefinitionRepositoryService = dynamicRouteDefinitionRepositoryService;
        this.dynamicSecurityCacheService = dynamicSecurityCacheService;
        this.staticProperties = staticProperties;
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
    @GetMapping("/routes")
    public Flux<PortalRouteDto> getAccessibleRoutes(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return Flux.empty();
        }

        List<String> userRoles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()).toLowerCase(Locale.ROOT))
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
                if ("Path".equalsIgnoreCase(pd.getName()) && pd.getArgs() != null) {
                    return pd.getArgs().values().stream().findFirst().orElse(null);
                }
            }
        }
        if (rd.getMetadata() != null && rd.getMetadata().containsKey("path")) {
            return String.valueOf(rd.getMetadata().get("path"));
        }
        return null;
    }

    private PortalRouteDto toPortalDto(RouteDefinition rd) {
        Map<String, Object> meta = rd.getMetadata() != null ? rd.getMetadata() : Map.of();
        String pathPattern = extractPathPattern(rd);
        String title = meta.get("title") instanceof String s ? s : rd.getId();
        String desc = meta.get("description") instanceof String s ? s : "Gateway service route for " + rd.getId();
        String category = meta.get("category") instanceof String s ? s : "Applications";
        String icon = meta.get("icon") instanceof String s ? s : "service";

        String launchUrl = meta.get("launchUrl") instanceof String s ? s : null;
        if (launchUrl == null && pathPattern != null) {
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
        if (rd == null || rd.getMetadata() == null) {
            return true;
        }
        Object enabledObj = rd.getMetadata().get(GatewayRouteMapper.METADATA_ENABLED);
        return enabledObj == null || Boolean.TRUE.equals(enabledObj) || "true".equalsIgnoreCase(String.valueOf(enabledObj));
    }
}
