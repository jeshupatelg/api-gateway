package com.jpg.apigateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpg.apigateway.domain.SecurityPublicPathEntity;
import com.jpg.apigateway.repository.GatewayRouteR2dbcRepository;
import com.jpg.apigateway.repository.SecurityPublicPathR2dbcRepository;
import com.jpg.apigateway.repository.SecurityRolePathR2dbcRepository;
import com.jpg.apigateway.security.service.DynamicSecurityCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StartupConfigInitializerTest {

    @Mock
    private GatewayRouteR2dbcRepository routeRepository;

    @Mock
    private SecurityPublicPathR2dbcRepository publicPathRepository;

    @Mock
    private SecurityRolePathR2dbcRepository rolePathRepository;

    @Mock
    private DynamicRouteDefinitionRepositoryService dynamicRouteRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private DynamicSecurityCacheService dynamicSecurityCacheService;
    private ObjectMapper jsonMapper;
    private com.jpg.apigateway.mapper.GatewayRouteMapper routeMapper;
    private StartupConfigInitializer initializer;

    @BeforeEach
    void setUp() {
        dynamicSecurityCacheService = new DynamicSecurityCacheService();
        jsonMapper = new ObjectMapper();
        routeMapper = new com.jpg.apigateway.mapper.GatewayRouteMapper(jsonMapper);
        initializer = new StartupConfigInitializer(
                routeRepository,
                publicPathRepository,
                rolePathRepository,
                dynamicRouteRepository,
                dynamicSecurityCacheService,
                routeMapper,
                eventPublisher,
                jsonMapper,
                "http://keycloak:8080"
        );
    }

    @Test
    void testInitializeSecurityConfigWhenDbIsEmptySeedsDefaults() {
        when(publicPathRepository.count()).thenReturn(Mono.just(0L));
        when(rolePathRepository.count()).thenReturn(Mono.just(0L));
        when(publicPathRepository.saveAll(anyIterable())).thenReturn(Flux.empty());
        when(rolePathRepository.saveAll(anyIterable())).thenReturn(Flux.empty());

        StepVerifier.create(initializer.initializeSecurityConfig())
                .verifyComplete();

        List<String> publicPaths = dynamicSecurityCacheService.getPublicPaths();
        assertTrue(publicPaths.contains("/actuator/health"));
        assertTrue(publicPaths.contains("/keycloak/**"));

        Map<String, List<String>> rolePathAccess = dynamicSecurityCacheService.getRolePathAccess();
        assertTrue(rolePathAccess.containsKey("admin"));
        assertTrue(rolePathAccess.get("admin").contains("/swagger-ui.html"));
    }

    @Test
    void testInitializeSecurityConfigWhenDbHasRecordsLoadsFromDb() {
        when(publicPathRepository.count()).thenReturn(Mono.just(1L));
        when(rolePathRepository.count()).thenReturn(Mono.just(1L));
        when(publicPathRepository.findAll()).thenReturn(Flux.just(new SecurityPublicPathEntity("/custom/public/**")));
        when(rolePathRepository.findAll()).thenReturn(Flux.empty());

        StepVerifier.create(initializer.initializeSecurityConfig())
                .verifyComplete();

        List<String> publicPaths = dynamicSecurityCacheService.getPublicPaths();
        assertEquals(1, publicPaths.size());
        assertEquals("/custom/public/**", publicPaths.get(0));
    }

    @Test
    void testInitializeRouteConfigWhenDbIsEmptySeedsKeycloakRoute() {
        when(routeRepository.count()).thenReturn(Mono.just(0L));
        when(routeRepository.saveAll(anyIterable())).thenAnswer(inv -> Flux.fromIterable(inv.getArgument(0)));

        StepVerifier.create(initializer.initializeRouteConfig())
                .verifyComplete();

        org.mockito.ArgumentCaptor<List<org.springframework.cloud.gateway.route.RouteDefinition>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(dynamicRouteRepository).updateCache(captor.capture());

        List<org.springframework.cloud.gateway.route.RouteDefinition> cachedRoutes = captor.getValue();
        assertEquals(1, cachedRoutes.size());
        org.springframework.cloud.gateway.route.RouteDefinition keycloakRoute = cachedRoutes.get(0);
        assertEquals("keycloak", keycloakRoute.getId());
        assertEquals("http://keycloak:8080", keycloakRoute.getUri().toString());
        assertEquals(org.springframework.core.Ordered.HIGHEST_PRECEDENCE, keycloakRoute.getOrder());
        assertEquals("Path", keycloakRoute.getPredicates().get(0).getName());
        assertEquals("/keycloak/**", keycloakRoute.getPredicates().get(0).getArgs().get("_genkey_0"));

        assertEquals(6, keycloakRoute.getFilters().size());
        assertEquals("AddResponseHeader", keycloakRoute.getFilters().get(0).getName());
        assertEquals("Cache-Control", keycloakRoute.getFilters().get(0).getArgs().get("_genkey_0"));
        assertEquals("no-store,no-cache,must-revalidate,max-age=0", keycloakRoute.getFilters().get(0).getArgs().get("_genkey_1"));

        assertEquals("AddResponseHeader", keycloakRoute.getFilters().get(1).getName());
        assertEquals("Pragma", keycloakRoute.getFilters().get(1).getArgs().get("_genkey_0"));
        assertEquals("no-cache", keycloakRoute.getFilters().get(1).getArgs().get("_genkey_1"));

        assertEquals("StripPrefix", keycloakRoute.getFilters().get(2).getName());
        assertEquals("1", keycloakRoute.getFilters().get(2).getArgs().get("_genkey_0"));

        assertEquals("PreserveHostHeader", keycloakRoute.getFilters().get(3).getName());

        assertEquals("AddRequestHeader", keycloakRoute.getFilters().get(4).getName());
        assertEquals("X-Forwarded-Proto", keycloakRoute.getFilters().get(4).getArgs().get("_genkey_0"));
        assertEquals("https", keycloakRoute.getFilters().get(4).getArgs().get("_genkey_1"));

        assertEquals("AddRequestHeader", keycloakRoute.getFilters().get(5).getName());
        assertEquals("X-Forwarded-Port", keycloakRoute.getFilters().get(5).getArgs().get("_genkey_0"));
        assertEquals("443", keycloakRoute.getFilters().get(5).getArgs().get("_genkey_1"));

        org.mockito.Mockito.verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(org.springframework.cloud.gateway.event.RefreshRoutesEvent.class));
    }

    @Test
    void testInitializeRouteConfigWhenDbHasRecordsWithoutKeycloakAddsKeycloakRoute() {
        com.jpg.apigateway.domain.GatewayRouteEntity existing = new com.jpg.apigateway.domain.GatewayRouteEntity();
        existing.setId("my-service");
        existing.setUri("http://my-service:8080");
        existing.setRouteOrder(10);
        existing.setPredicatesJson("[]");
        existing.setFiltersJson("[]");

        when(routeRepository.count()).thenReturn(Mono.just(1L));
        when(routeRepository.findAll()).thenReturn(Flux.just(existing));
        when(routeRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(initializer.initializeRouteConfig())
                .verifyComplete();

        org.mockito.ArgumentCaptor<List<org.springframework.cloud.gateway.route.RouteDefinition>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(dynamicRouteRepository).updateCache(captor.capture());

        List<org.springframework.cloud.gateway.route.RouteDefinition> cachedRoutes = captor.getValue();
        assertEquals(2, cachedRoutes.size());
        assertTrue(cachedRoutes.stream().anyMatch(r -> "keycloak".equals(r.getId())));
        assertTrue(cachedRoutes.stream().anyMatch(r -> "my-service".equals(r.getId())));
        org.mockito.Mockito.verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(org.springframework.cloud.gateway.event.RefreshRoutesEvent.class));
    }

    @Test
    void testGetKeycloakInternalUrlThrowsWhenMissing() {
        StartupConfigInitializer initWithoutUrl = new StartupConfigInitializer(
                routeRepository,
                publicPathRepository,
                rolePathRepository,
                dynamicRouteRepository,
                dynamicSecurityCacheService,
                routeMapper,
                eventPublisher,
                jsonMapper,
                (String) null
        );
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                initWithoutUrl::createKeycloakRouteDefinition
        );
    }
}
