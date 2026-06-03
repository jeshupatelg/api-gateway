package com.jpg.apigateway.security.config;

import com.jpg.apigateway.security.RolePathReactiveAuthorizationManager;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binds {@code gateway.security.*} from Spring configuration (for example {@code application.yaml}
 * or an imported {@code /config/security.yaml}).
 * <p>
 * Public paths are permitted without authentication. All other paths require an authenticated
 * user whose realm roles satisfy {@link #getRolePathAccess()} via {@link RolePathReactiveAuthorizationManager}.
 */
@ConfigurationProperties(prefix = "gateway.security")
public class GatewaySecurityProperties {

    /**
     * Ant-style path patterns that skip OAuth2 login and role checks (for example Keycloak UI,
     * OAuth2 callbacks, and selected actuator endpoints). Must stay consistent with exposed routes.
     */
    private List<String> publicPaths = defaultPublicPaths();

    /**
     * Maps Keycloak realm role names to Ant-style path patterns. Role names are compared without
     * regard to case. If the user has any configured role whose patterns match the request path,
     * access is granted (union across roles).
     */
    private Map<String, List<String>> rolePathAccess = new LinkedHashMap<>();

    private static List<String> defaultPublicPaths() {
        List<String> paths = new ArrayList<>();
        paths.add("/keycloak/**");
        paths.add("/oauth2/**");
        paths.add("/login/oauth2/code/**");
        paths.add("/actuator/health");
        paths.add("/actuator/info");
        paths.add("/favicon.ico");
        return paths;
    }

    /**
     * @return path patterns that do not require authentication
     */
    public List<String> getPublicPaths() {
        return publicPaths;
    }

    /**
     * @param publicPaths path patterns that do not require authentication
     */
    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }

    /**
     * @return map from realm role name to list of allowed Ant path patterns
     */
    public Map<String, List<String>> getRolePathAccess() {
        return rolePathAccess;
    }

    /**
     * @param rolePathAccess map from realm role name to list of allowed Ant path patterns
     */
    public void setRolePathAccess(Map<String, List<String>> rolePathAccess) {
        this.rolePathAccess = rolePathAccess;
    }
}
