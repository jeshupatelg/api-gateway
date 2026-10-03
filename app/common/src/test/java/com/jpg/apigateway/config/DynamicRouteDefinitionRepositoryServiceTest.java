package com.jpg.apigateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpg.apigateway.domain.GatewayRouteEntity;
import com.jpg.apigateway.mapper.GatewayRouteMapper;
import com.jpg.apigateway.repository.GatewayRouteR2dbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.route.RouteDefinition;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DynamicRouteDefinitionRepositoryServiceTest {

    @Mock
    private GatewayRouteR2dbcRepository repository;

    private DynamicRouteDefinitionRepositoryService dynamicRepo;
    private GatewayRouteMapper routeMapper;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        routeMapper = new GatewayRouteMapper(objectMapper);
        dynamicRepo = new DynamicRouteDefinitionRepositoryService(repository, routeMapper);
    }

    @Test
    void testSaveRouteNewRouteExecutesSingleDbCall() {
        // Initialize single route cache with another route so cache is warm
        RouteDefinition existing = new RouteDefinition();
        existing.setId("existing-route");
        existing.setUri(URI.create("http://localhost:8080"));
        dynamicRepo.updateCache(List.of(existing));

        // New route to save
        RouteDefinition newRoute = new RouteDefinition();
        newRoute.setId("new-route");
        newRoute.setUri(URI.create("http://localhost:8081"));

        when(repository.save(any(GatewayRouteEntity.class))).thenAnswer(inv -> {
            GatewayRouteEntity savedEntity = inv.getArgument(0);
            assertNull(savedEntity.getVersion(), "New insert should have null version");
            return Mono.just(savedEntity);
        });

        StepVerifier.create(dynamicRepo.saveRoute(newRoute))
                .assertNext(saved -> {
                    assertEquals("new-route", saved.getId());
                    assertNotNull(saved.getMetadata());
                    assertNotNull(saved.getMetadata().get(GatewayRouteMapper.METADATA_CREATED_AT));
                    assertNotNull(saved.getMetadata().get(GatewayRouteMapper.METADATA_UPDATED_AT));
                })
                .verifyComplete();

        // Exactly 1 DB call (save), 0 findById calls
        verify(repository, times(1)).save(any(GatewayRouteEntity.class));
        verify(repository, never()).findById(anyString());
    }

    @Test
    void testSaveRouteExistingRouteExecutesSingleDbCall() {
        Instant createdTime = Instant.now().minusSeconds(100);
        RouteDefinition existing = new RouteDefinition();
        existing.setId("existing-route");
        existing.setUri(URI.create("http://localhost:8080"));
        Map<String, Object> meta = new HashMap<>();
        meta.put(GatewayRouteMapper.METADATA_VERSION, 2L);
        meta.put(GatewayRouteMapper.METADATA_CREATED_AT, createdTime);
        existing.setMetadata(meta);
        dynamicRepo.updateCache(List.of(existing));

        RouteDefinition updateRoute = new RouteDefinition();
        updateRoute.setId("existing-route");
        updateRoute.setUri(URI.create("http://localhost:9090"));

        when(repository.save(any(GatewayRouteEntity.class))).thenAnswer(inv -> {
            GatewayRouteEntity savedEntity = inv.getArgument(0);
            assertEquals(2L, savedEntity.getVersion(), "Should preserve existing version for update");
            assertEquals(createdTime, savedEntity.getCreatedAt(), "Should preserve original createdAt");
            return Mono.just(savedEntity);
        });

        StepVerifier.create(dynamicRepo.saveRoute(updateRoute))
                .assertNext(saved -> {
                    assertEquals("existing-route", saved.getId());
                    assertNotNull(saved.getMetadata().get(GatewayRouteMapper.METADATA_UPDATED_AT));
                })
                .verifyComplete();

        // Exactly 1 DB call (save), 0 findById calls
        verify(repository, times(1)).save(any(GatewayRouteEntity.class));
        verify(repository, never()).findById(anyString());
    }

    @Test
    void testDeleteRouteWhenExistsExecutesSingleDbCall() {
        RouteDefinition existing = new RouteDefinition();
        existing.setId("to-delete");
        existing.setUri(URI.create("http://localhost:8080"));
        dynamicRepo.updateCache(List.of(existing));

        when(repository.deleteById("to-delete")).thenReturn(Mono.empty());

        StepVerifier.create(dynamicRepo.deleteRoute("to-delete"))
                .assertNext(deleted -> assertTrue(deleted))
                .verifyComplete();

        // Exactly 1 DB call (deleteById), 0 findById calls
        verify(repository, times(1)).deleteById("to-delete");
        verify(repository, never()).findById(anyString());
        assertFalse(dynamicRepo.hasRoute("to-delete"), "Should be evicted from cache");
    }

    @Test
    void testDeleteRouteWhenNotExistsExecutesZeroDbCalls() {
        RouteDefinition existing = new RouteDefinition();
        existing.setId("other-route");
        existing.setUri(URI.create("http://localhost:8080"));
        dynamicRepo.updateCache(List.of(existing));

        StepVerifier.create(dynamicRepo.deleteRoute("non-existing"))
                .assertNext(deleted -> assertFalse(deleted))
                .verifyComplete();

        // Exactly 0 DB calls
        verify(repository, never()).deleteById(anyString());
        verify(repository, never()).findById(anyString());
    }

    @Test
    void testEnableRouteWhenExistsExecutesSingleDbCall() {
        RouteDefinition existing = new RouteDefinition();
        existing.setId("to-enable");
        existing.setUri(URI.create("http://localhost:8080"));
        Map<String, Object> meta = new HashMap<>();
        meta.put(GatewayRouteMapper.METADATA_ENABLED, false);
        meta.put(GatewayRouteMapper.METADATA_VERSION, 1L);
        existing.setMetadata(meta);
        dynamicRepo.updateCache(List.of(existing));

        when(repository.save(any(GatewayRouteEntity.class))).thenAnswer(inv -> {
            GatewayRouteEntity entity = inv.getArgument(0);
            assertTrue(entity.getEnabled());
            return Mono.just(entity);
        });

        StepVerifier.create(dynamicRepo.enableRoute("to-enable"))
                .assertNext(enabled -> {
                    assertEquals("to-enable", enabled.getId());
                    assertTrue((Boolean) enabled.getMetadata().get(GatewayRouteMapper.METADATA_ENABLED));
                })
                .verifyComplete();

        // Exactly 1 DB call (save), 0 findById calls
        verify(repository, times(1)).save(any(GatewayRouteEntity.class));
        verify(repository, never()).findById(anyString());
    }

    @Test
    void testEnableRouteWhenNotExistsExecutesZeroDbCalls() {
        RouteDefinition existing = new RouteDefinition();
        existing.setId("other-route");
        existing.setUri(URI.create("http://localhost:8080"));
        dynamicRepo.updateCache(List.of(existing));

        StepVerifier.create(dynamicRepo.enableRoute("non-existing"))
                .verifyComplete();

        // Exactly 0 DB calls
        verify(repository, never()).save(any(GatewayRouteEntity.class));
        verify(repository, never()).findById(anyString());
    }

    @Test
    void testDisableRouteWhenExistsExecutesSingleDbCall() {
        RouteDefinition existing = new RouteDefinition();
        existing.setId("to-disable");
        existing.setUri(URI.create("http://localhost:8080"));
        Map<String, Object> meta = new HashMap<>();
        meta.put(GatewayRouteMapper.METADATA_ENABLED, true);
        meta.put(GatewayRouteMapper.METADATA_VERSION, 1L);
        existing.setMetadata(meta);
        dynamicRepo.updateCache(List.of(existing));

        when(repository.save(any(GatewayRouteEntity.class))).thenAnswer(inv -> {
            GatewayRouteEntity entity = inv.getArgument(0);
            assertFalse(entity.getEnabled());
            return Mono.just(entity);
        });

        StepVerifier.create(dynamicRepo.disableRoute("to-disable"))
                .assertNext(disabled -> {
                    assertEquals("to-disable", disabled.getId());
                    assertFalse((Boolean) disabled.getMetadata().get(GatewayRouteMapper.METADATA_ENABLED));
                })
                .verifyComplete();

        // Exactly 1 DB call (save), 0 findById calls
        verify(repository, times(1)).save(any(GatewayRouteEntity.class));
        verify(repository, never()).findById(anyString());
    }

    @Test
    void testDisableRouteWhenNotExistsExecutesZeroDbCalls() {
        RouteDefinition existing = new RouteDefinition();
        existing.setId("other-route");
        existing.setUri(URI.create("http://localhost:8080"));
        dynamicRepo.updateCache(List.of(existing));

        StepVerifier.create(dynamicRepo.disableRoute("non-existing"))
                .verifyComplete();

        // Exactly 0 DB calls
        verify(repository, never()).save(any(GatewayRouteEntity.class));
        verify(repository, never()).findById(anyString());
    }
}
