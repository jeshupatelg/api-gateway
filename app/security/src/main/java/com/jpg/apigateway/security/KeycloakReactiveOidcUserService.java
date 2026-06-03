package com.jpg.apigateway.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcReactiveOAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.ReactiveOAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reactive OIDC user service that delegates to {@link OidcReactiveOAuth2UserService} and augments
 * authorities with Keycloak realm roles from token and user-info claims.
 * <p>
 * Reads {@code realm_access.roles} (standard Keycloak JWT structure) and, if present, the legacy
 * {@code realm_roles} claim. Each role is exposed as {@code ROLE_} plus the uppercased role name for use
 * with Spring Security and {@link RolePathReactiveAuthorizationManager} (for example {@code ROLE_DEVOPS}).
 */
@Component
public class KeycloakReactiveOidcUserService implements ReactiveOAuth2UserService<OidcUserRequest, OidcUser> {

    private final OidcReactiveOAuth2UserService delegate = new OidcReactiveOAuth2UserService();

    /**
     * Loads the OIDC user from the provider and merges Keycloak realm roles into {@link OidcUser#getAuthorities()}.
     *
     * @param userRequest authorization-code flow request containing tokens and client registration
     * @return the augmented {@link OidcUser}
     */
    @Override
    public Mono<OidcUser> loadUser(OidcUserRequest userRequest) {
        return delegate.loadUser(userRequest).map(user -> {
            Set<GrantedAuthority> merged = new LinkedHashSet<>(user.getAuthorities());
            merged.addAll(realmRolesAsAuthorities(user.getAttributes()));
            merged.addAll(realmRolesAsAuthorities(user.getUserInfo().getClaims()));
            return new DefaultOidcUser(merged, user.getIdToken(), user.getUserInfo());
        });
    }

    /**
     * Converts Keycloak realm role claims into {@link SimpleGrantedAuthority} entries.
     *
     * @param claims OIDC ID token or user-info claims map; may be null or empty
     * @return authorities derived from realm roles; never null
     */
    private static Collection<? extends GrantedAuthority> realmRolesAsAuthorities(Map<String, Object> claims) {
        if (claims == null || claims.isEmpty()) {
            return List.of();
        }
        List<String> roles = new ArrayList<>();
        Object realmAccess = claims.get("realm_access");
        if (realmAccess instanceof Map<?, ?> ra) {
            Object rs = ra.get("roles");
            if (rs instanceof Collection<?> coll) {
                for (Object r : coll) {
                    if (r != null) {
                        roles.add(r.toString());
                    }
                }
            }
        }
        List<?> legacy = castList(claims.get("realm_roles"));
        if (legacy != null) {
            for (Object r : legacy) {
                if (r != null) {
                    roles.add(r.toString());
                }
            }
        }
        List<?> rootRoles = castList(claims.get("roles"));
        if (rootRoles != null) {
            for (Object r : rootRoles) {
                if (r != null) {
                    roles.add(r.toString());
                }
            }
        }
        List<GrantedAuthority> authorities = new ArrayList<>(roles.size());
        for (String role : roles) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()));
        }
        return authorities;
    }

    /**
     * @param value claim value expected to be a JSON array of role strings
     * @return the list, or null if not a list
     */
    private static List<?> castList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        return null;
    }
}
