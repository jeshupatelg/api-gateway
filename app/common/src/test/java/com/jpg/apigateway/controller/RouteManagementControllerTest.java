package com.jpg.apigateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpg.apigateway.config.DynamicRouteDefinitionRepositoryService;
import com.jpg.apigateway.mapper.GatewayRouteMapper;
import com.jpg.apigateway.model.RouteDefinitionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteManagementControllerTest {

    @Mock
    private DynamicRouteDefinitionRepositoryService dynamicRouteRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private GatewayRouteMapper routeMapper;
    private RouteManagementController controller;

    @BeforeEach
    void setUp() {
        ObjectMapper jsonMapper = new ObjectMapper();
        routeMapper = new GatewayRouteMapper(jsonMapper);
        controller = new RouteManagementController(dynamicRouteRepository, routeMapper, eventPublisher);
    }

    @Test
    void testGetRoutes() {
        RouteDefinition rd = new RouteDefinition();
        rd.setId("service-a");
        rd.setUri(URI.create("http://localhost:8081"));
        Map<String, Object> meta = new HashMap<>();
        meta.put("enabled", true);
        rd.setMetadata(meta);

        when(dynamicRouteRepository.getAllRoutes()).thenReturn(Flux.just(rd));

        StepVerifier.create(controller.getRoutes(null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    StepVerifier.create(response.getBody())
                            .assertNext(dto -> {
                                assertEquals("service-a", dto.getId());
                                assertTrue(dto.getEnabled());
                            })
                            .verifyComplete();
                })
                .verifyComplete();
    }

    @Test
    void testSaveRoute() {
        RouteDefinitionDto inputDto = new RouteDefinitionDto();
        inputDto.setId("service-save");
        inputDto.setUri("http://localhost:8082");
        inputDto.setEnabled(true);

        RouteDefinition rd = routeMapper.toRouteDefinition(inputDto);
        when(dynamicRouteRepository.saveRoute(any(RouteDefinition.class))).thenReturn(Mono.just(rd));

        StepVerifier.create(controller.saveRoute(Mono.just(inputDto), null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    RouteDefinitionDto body = response.getBody();
                    assertNotNull(body);
                    assertEquals("service-save", body.getId());
                })
                .verifyComplete();

        verify(eventPublisher, times(1)).publishEvent(any());
    }

    @Test
    void testDeleteRouteSuccess() {
        when(dynamicRouteRepository.deleteRoute("service-delete")).thenReturn(Mono.just(true));

        StepVerifier.create(controller.deleteRoute("service-delete", null))
                .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode()))
                .verifyComplete();

        verify(eventPublisher, times(1)).publishEvent(any());
    }

    @Test
    void testDeleteRouteNotFound() {
        when(dynamicRouteRepository.deleteRoute("non-existing")).thenReturn(Mono.just(false));

        StepVerifier.create(controller.deleteRoute("non-existing", null))
                .assertNext(response -> assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode()))
                .verifyComplete();

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void testEnableRouteSuccess() {
        RouteDefinition rd = new RouteDefinition();
        rd.setId("service-a");
        rd.setUri(URI.create("http://localhost:8080"));
        Map<String, Object> meta = new HashMap<>();
        meta.put("enabled", true);
        rd.setMetadata(meta);

        when(dynamicRouteRepository.enableRoute("service-a")).thenReturn(Mono.just(rd));

        StepVerifier.create(controller.enableRoute("service-a", null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    RouteDefinitionDto dto = response.getBody();
                    assertNotNull(dto);
                    assertTrue(dto.getEnabled());
                })
                .verifyComplete();

        verify(eventPublisher, times(1)).publishEvent(any());
    }

    @Test
    void testEnableRouteNotFound() {
        when(dynamicRouteRepository.enableRoute("non-existing")).thenReturn(Mono.empty());

        StepVerifier.create(controller.enableRoute("non-existing", null))
                .assertNext(response -> assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode()))
                .verifyComplete();
    }

    @Test
    void testDisableRouteSuccess() {
        RouteDefinition rd = new RouteDefinition();
        rd.setId("service-b");
        rd.setUri(URI.create("http://localhost:8080"));
        Map<String, Object> meta = new HashMap<>();
        meta.put("enabled", false);
        rd.setMetadata(meta);

        when(dynamicRouteRepository.disableRoute("service-b")).thenReturn(Mono.just(rd));

        StepVerifier.create(controller.disableRoute("service-b", null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    RouteDefinitionDto dto = response.getBody();
                    assertNotNull(dto);
                    assertFalse(dto.getEnabled());
                })
                .verifyComplete();

        verify(eventPublisher, times(1)).publishEvent(any());
    }

    @Test
    void testDisableRouteNotFound() {
        when(dynamicRouteRepository.disableRoute("non-existing")).thenReturn(Mono.empty());

        StepVerifier.create(controller.disableRoute("non-existing", null))
                .assertNext(response -> assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode()))
                .verifyComplete();
    }
}
