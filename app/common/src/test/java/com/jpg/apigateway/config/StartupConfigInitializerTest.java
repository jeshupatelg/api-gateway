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
    private StartupConfigInitializer initializer;

    @BeforeEach
    void setUp() {
        dynamicSecurityCacheService = new DynamicSecurityCacheService();
        ObjectMapper jsonMapper = new ObjectMapper();
        com.jpg.apigateway.mapper.GatewayRouteMapper routeMapper = new com.jpg.apigateway.mapper.GatewayRouteMapper(jsonMapper);
        initializer = new StartupConfigInitializer(
                routeRepository,
                publicPathRepository,
                rolePathRepository,
                dynamicRouteRepository,
                dynamicSecurityCacheService,
                routeMapper,
                eventPublisher,
                jsonMapper
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
}
