package com.jpg.apigateway.controller;

import com.jpg.apigateway.config.DynamicRouteDefinitionRepositoryService;
import com.jpg.apigateway.security.config.GatewaySecurityProperties;
import com.jpg.apigateway.security.service.DynamicSecurityCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortalControllerTest {

    @Mock
    private DynamicRouteDefinitionRepositoryService dynamicRouteRepository;

    @Mock
    private DynamicSecurityCacheService dynamicSecurityCacheService;

    @Mock
    private GatewaySecurityProperties staticProperties;

    private PortalController controller;

    @BeforeEach
    void setUp() {
        controller = new PortalController(dynamicRouteRepository, dynamicSecurityCacheService, staticProperties);
    }

    @Test
    void testGetCurrentUser_DevopsUser() {
        Authentication auth = new TestingAuthenticationToken("alice", "creds", "ROLE_DEVOPS");

        StepVerifier.create(controller.getCurrentUser(auth))
                .assertNext(user -> {
                    assertEquals("alice", user.username());
                    assertTrue(user.roles().contains("devops"));
                    assertFalse(user.isAdmin());
                })
                .verifyComplete();
    }

    @Test
    void testGetCurrentUser_AdminUser() {
        Authentication auth = new TestingAuthenticationToken("charlie", "creds", "ROLE_ADMIN");

        StepVerifier.create(controller.getCurrentUser(auth))
                .assertNext(user -> {
                    assertEquals("charlie", user.username());
                    assertTrue(user.roles().contains("admin"));
                    assertTrue(user.isAdmin());
                })
                .verifyComplete();
    }

    @Test
    void testGetCurrentUser_WithOAuth2Claims() {
        Map<String, Object> claims = Map.of(
                "preferred_username", "rishu",
                "name", "Rishu Patel",
                "email", "rishupatel924@gmail.com"
        );
        OAuth2User oauth2User = mock(OAuth2User.class);
        when(oauth2User.getAttributes()).thenReturn(claims);
        Authentication auth = new TestingAuthenticationToken(oauth2User, "creds", "ROLE_ADMIN");

        StepVerifier.create(controller.getCurrentUser(auth))
                .assertNext(user -> {
                    assertEquals("rishu", user.username());
                    assertEquals("Rishu Patel", user.displayName());
                    assertEquals("rishupatel924@gmail.com", user.email());
                    assertTrue(user.isAdmin());
                })
                .verifyComplete();
    }

    @Test
    void testGetAccessibleRoutes_RoleFiltered() {
        RouteDefinition r1 = new RouteDefinition();
        r1.setId("jenkins-route");
        r1.setUri(URI.create("http://jenkins:8080"));
        PredicateDefinition p1 = new PredicateDefinition();
        p1.setName("Path");
        p1.addArg("pattern", "/jenkins/**");
        r1.setPredicates(List.of(p1));
        r1.setMetadata(Map.of("enabled", true, "title", "Jenkins CI/CD"));

        RouteDefinition r2 = new RouteDefinition();
        r2.setId("reports-route");
        r2.setUri(URI.create("http://reports:8082"));
        PredicateDefinition p2 = new PredicateDefinition();
        p2.setName("Path");
        p2.addArg("pattern", "/reports/**");
        r2.setPredicates(List.of(p2));
        r2.setMetadata(Map.of("enabled", true, "title", "Reporting Hub"));

        when(dynamicRouteRepository.getAllRoutes()).thenReturn(Flux.just(r1, r2));
        when(dynamicSecurityCacheService.getRolePathAccess()).thenReturn(Map.of(
                "devops", List.of("/jenkins/**"),
                "reporting", List.of("/reports/**")
        ));
        when(dynamicSecurityCacheService.getPublicPaths()).thenReturn(List.of());

        // User has only ROLE_DEVOPS -> should ONLY see jenkins-route
        Authentication authDevops = new TestingAuthenticationToken("alice", "creds", "ROLE_DEVOPS");

        StepVerifier.create(controller.getAccessibleRoutes(authDevops))
                .assertNext(route -> {
                    assertEquals("jenkins-route", route.id());
                    assertEquals("Jenkins CI/CD", route.title());
                })
                .verifyComplete();
    }

    @Test
    void testGetAccessibleRoutes_AdminSeesAllRoutes() {
        RouteDefinition r1 = new RouteDefinition();
        r1.setId("jenkins-route");
        r1.setUri(URI.create("http://jenkins:8080"));
        PredicateDefinition p1 = new PredicateDefinition();
        p1.setName("Path");
        p1.addArg("pattern", "/jenkins/**");
        r1.setPredicates(List.of(p1));
        r1.setMetadata(Map.of("enabled", true, "title", "Jenkins CI/CD"));

        RouteDefinition r2 = new RouteDefinition();
        r2.setId("reports-route");
        r2.setUri(URI.create("http://reports:8082"));
        PredicateDefinition p2 = new PredicateDefinition();
        p2.setName("Path");
        p2.addArg("pattern", "/reports/**");
        r2.setPredicates(List.of(p2));
        r2.setMetadata(Map.of("enabled", true, "title", "Reporting Hub"));

        when(dynamicRouteRepository.getAllRoutes()).thenReturn(Flux.just(r1, r2));

        Authentication authAdmin = new TestingAuthenticationToken("charlie", "creds", "ROLE_ADMIN");

        StepVerifier.create(controller.getAccessibleRoutes(authAdmin))
                .expectNextCount(2)
                .verifyComplete();
    }
}
