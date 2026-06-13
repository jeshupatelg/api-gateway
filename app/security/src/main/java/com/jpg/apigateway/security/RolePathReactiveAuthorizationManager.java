package com.jpg.apigateway.security;

import com.jpg.apigateway.security.config.GatewaySecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Authorizes authenticated requests by matching the request path against Ant patterns configured
 * per realm role in {@link GatewaySecurityProperties#getRolePathAccess()}.
 * <p>
 * Unauthenticated requests do not reach this manager for non-public paths; the OAuth2 login flow
 * runs first. If no role grants a matching pattern, access is denied.
 */
@Component
public class RolePathReactiveAuthorizationManager implements ReactiveAuthorizationManager<AuthorizationContext> {

    private static final Logger log = LoggerFactory.getLogger(RolePathReactiveAuthorizationManager.class);

    private final GatewaySecurityProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /**
     * @param properties role names and allowed path patterns
     */
    public RolePathReactiveAuthorizationManager(GatewaySecurityProperties properties) {
        this.properties = properties;
    }

    /**
     * Grants access when the authenticated principal has at least one realm role whose configured
     * patterns match the current request path (using {@link AntPathMatcher}).
     *
     * @param authentication current reactive authentication, if any
     * @param context exchange and path context
     * @return {@code true} when a role-pattern match exists; otherwise {@code false} (including when not authenticated)
     */
    @Override
    public Mono<AuthorizationDecision> check(Mono<Authentication> authentication, AuthorizationContext context) {
        String path = context.getExchange().getRequest().getPath().pathWithinApplication().value();
        return authentication
                .filter(Authentication::isAuthenticated)
                .map(auth -> new AuthorizationDecision(isAuthorized(auth, path)))
                .defaultIfEmpty(new AuthorizationDecision(false));
    }

    /**
     * @param auth authenticated principal
     * @param path path within the application (excluding context path when not set)
     * @return true if any configured role of the user matches the path
     */
    private boolean isAuthorized(Authentication auth, String path) {
        Map<String, List<String>> rolePaths = properties.getRolePathAccess();
        if (rolePaths == null || rolePaths.isEmpty()) {
            log.warn("Access denied: No role-path configurations defined in GatewaySecurityProperties.");
            return false;
        }
        for (Map.Entry<String, List<String>> entry : rolePaths.entrySet()) {
            String configuredRole = entry.getKey();
            List<String> patterns = entry.getValue();
            if (patterns == null || patterns.isEmpty()) {
                continue;
            }
            if (!hasRole(auth, configuredRole)) {
                continue;
            }
            for (String pattern : patterns) {
                if (pattern != null && pathMatcher.match(pattern, path)) {
                    return true;
                }
            }
        }
        if (!(auth instanceof AnonymousAuthenticationToken)) {
            log.warn("Access denied for user '{}' (roles: {}): No matched pattern for path '{}'",
                    auth.getName(), auth.getAuthorities(), path);
        }
        return false;
    }

    /**
     * @param auth current authentication
     * @param configuredRole role name from configuration (compared case-insensitively to {@code ROLE_*} authorities)
     * @return whether the principal has that realm role
     */
    private boolean hasRole(Authentication auth, String configuredRole) {
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String authority = ga.getAuthority();
            if (authority != null && authority.startsWith("ROLE_")) {
                authority = authority.substring("ROLE_".length());
            }
            if (authority != null && authority.equalsIgnoreCase(configuredRole)) {
                return true;
            }
        }
        return false;
    }
}
