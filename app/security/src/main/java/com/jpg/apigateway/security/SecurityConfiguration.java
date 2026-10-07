package com.jpg.apigateway.security;

import com.jpg.apigateway.security.config.GatewaySecurityProperties;
import com.jpg.apigateway.security.service.DynamicSecurityCacheService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.util.AntPathMatcher;

import java.util.List;

/**
 * Reactive security for Spring Cloud Gateway: OAuth2/OIDC login with Keycloak
 * and path-based authorization driven dynamically by {@link DynamicSecurityCacheService}
 * (falling back to {@link GatewaySecurityProperties}).
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(GatewaySecurityProperties.class)
public class SecurityConfiguration {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(
            ServerHttpSecurity http,
            ReactiveClientRegistrationRepository clientRegistrationRepository,
            GatewaySecurityProperties gatewaySecurityProperties,
            DynamicSecurityCacheService dynamicSecurityCacheService,
            RolePathReactiveAuthorizationManager rolePathReactiveAuthorizationManager) {

        ServerWebExchangeMatcher dynamicPublicPathMatcher = exchange -> {
            String path = exchange.getRequest().getPath().pathWithinApplication().value();
            List<String> publicPaths = dynamicSecurityCacheService.getPublicPaths();
            if (publicPaths.isEmpty()) {
                publicPaths = gatewaySecurityProperties.getPublicPaths();
            }
            AntPathMatcher antPathMatcher = new AntPathMatcher();
            for (String pattern : publicPaths) {
                if (pattern != null && antPathMatcher.match(pattern, path)) {
                    return ServerWebExchangeMatcher.MatchResult.match();
                }
            }
            return ServerWebExchangeMatcher.MatchResult.notMatch();
        };

        http.authorizeExchange(exchanges -> exchanges
                .matchers(dynamicPublicPathMatcher).permitAll()
                .pathMatchers("/", "/index.html", "/favicon.ico", "/css/**", "/js/**", "/images/**", "/static/**", "/api/v1/portal/**").authenticated()
                .anyExchange().access(rolePathReactiveAuthorizationManager));

        http.oauth2Login(Customizer.withDefaults());

        OidcClientInitiatedServerLogoutSuccessHandler logoutSuccessHandler =
                new OidcClientInitiatedServerLogoutSuccessHandler(clientRegistrationRepository);
        logoutSuccessHandler.setPostLogoutRedirectUri("{baseUrl}/");

        http.logout(logout -> logout
                .requiresLogout(ServerWebExchangeMatchers.pathMatchers("/logout"))
                .logoutSuccessHandler(logoutSuccessHandler)
        );

        http.csrf(ServerHttpSecurity.CsrfSpec::disable);
        return http.build();
    }
}
