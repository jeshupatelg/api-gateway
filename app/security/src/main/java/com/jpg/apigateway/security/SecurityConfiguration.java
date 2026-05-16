package com.jpg.apigateway.security;

import com.jpg.apigateway.security.config.GatewaySecurityProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Reactive security for Spring Cloud Gateway: OAuth2/OIDC login with Keycloak
 * and path-based
 * authorization driven by {@link GatewaySecurityProperties}.
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class SecurityConfiguration {

        /**
         * Configures public routes, delegates protected routes to
         * {@link RolePathReactiveAuthorizationManager},
         * and enables OAuth2 login with the default Keycloak
         * {@link org.springframework.security.oauth2.client.registration.ClientRegistration}
         * named {@code keycloak}.
         *
         * @param http                                 server security DSL
         * @param gatewaySecurityProperties            public paths and role-to-path
         *                                             rules
         * @param rolePathReactiveAuthorizationManager grants access when the request
         *                                             path matches a pattern for any of
         *                                             the user roles
         * @return the gateway security filter chain
         */
        @Bean
        public SecurityWebFilterChain springSecurityFilterChain(
                        ServerHttpSecurity http,
                        GatewaySecurityProperties gatewaySecurityProperties,
                        RolePathReactiveAuthorizationManager rolePathReactiveAuthorizationManager) {
                String[] publicPaths = gatewaySecurityProperties.getPublicPaths().toArray(String[]::new);
                http.authorizeExchange(exchanges -> exchanges
                                .pathMatchers(publicPaths).permitAll()
                                .anyExchange().access(rolePathReactiveAuthorizationManager));
                http.oauth2Login(Customizer.withDefaults());
                http.csrf(ServerHttpSecurity.CsrfSpec::disable);
                return http.build();
        }
}
