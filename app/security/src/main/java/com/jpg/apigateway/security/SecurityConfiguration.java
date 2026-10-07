package com.jpg.apigateway.security;

import com.jpg.apigateway.security.config.GatewaySecurityProperties;
import com.jpg.apigateway.security.service.DynamicSecurityCacheService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
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
            @Value("${KEYCLOAK_ISSUER_URI:}") String keycloakIssuerUri,
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

        ServerLogoutSuccessHandler logoutSuccessHandler = (exchange, authentication) -> {
            String idToken = null;
            if (authentication instanceof OAuth2AuthenticationToken oauth && oauth.getPrincipal() instanceof OidcUser oidcUser) {
                idToken = oidcUser.getIdToken().getTokenValue();
            }

            ServerHttpRequest request = exchange.getExchange().getRequest();
            String scheme = request.getHeaders().getFirst("X-Forwarded-Proto");
            if (scheme == null || scheme.isBlank()) {
                scheme = request.getURI().getScheme();
            }
            String host = request.getHeaders().getFirst("X-Forwarded-Host");
            if (host == null || host.isBlank()) {
                host = request.getURI().getAuthority();
            }
            String baseUrl = scheme + "://" + host + "/";

            if (idToken == null || keycloakIssuerUri == null || keycloakIssuerUri.isBlank()) {
                ServerHttpResponse response = exchange.getExchange().getResponse();
                response.setStatusCode(HttpStatus.FOUND);
                response.getHeaders().setLocation(URI.create(baseUrl));
                return response.setComplete();
            }

            String baseIssuer = keycloakIssuerUri.endsWith("/")
                    ? keycloakIssuerUri.substring(0, keycloakIssuerUri.length() - 1)
                    : keycloakIssuerUri;

            URI logoutUri = UriComponentsBuilder
                    .fromUriString(baseIssuer + "/protocol/openid-connect/logout")
                    .queryParam("post_logout_redirect_uri", baseUrl)
                    .queryParam("id_token_hint", idToken)
                    .build()
                    .encode()
                    .toUri();

            ServerHttpResponse response = exchange.getExchange().getResponse();
            response.setStatusCode(HttpStatus.FOUND);
            response.getHeaders().setLocation(logoutUri);
            return response.setComplete();
        };

        http.logout(logout -> logout
                .requiresLogout(ServerWebExchangeMatchers.pathMatchers("/logout"))
                .logoutSuccessHandler(logoutSuccessHandler)
        );

        http.csrf(ServerHttpSecurity.CsrfSpec::disable);
        return http.build();
    }
}
