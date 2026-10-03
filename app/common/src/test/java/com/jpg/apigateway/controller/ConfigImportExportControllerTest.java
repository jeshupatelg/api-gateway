package com.jpg.apigateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpg.apigateway.config.DynamicRouteDefinitionRepositoryService;
import com.jpg.apigateway.config.DynamicSecurityRepositoryService;
import com.jpg.apigateway.mapper.GatewayRouteMapper;
import com.jpg.apigateway.model.GatewayConfigExportDto;
import com.jpg.apigateway.model.GatewaySecurityConfigDto;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigImportExportControllerTest {

    @Mock
    private DynamicRouteDefinitionRepositoryService dynamicRouteRepository;

    @Mock
    private DynamicSecurityRepositoryService dynamicSecurityRepositoryService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private GatewayRouteMapper routeMapper;
    private ConfigImportExportController importExportController;

    @BeforeEach
    void setUp() {
        ObjectMapper jsonMapper = new ObjectMapper();
        routeMapper = new GatewayRouteMapper(jsonMapper);
        importExportController = new ConfigImportExportController(
                dynamicRouteRepository,
                dynamicSecurityRepositoryService,
                routeMapper,
                eventPublisher
        );
    }

    @Test
    void testExportConfig() {
        when(dynamicRouteRepository.getAllRoutes()).thenReturn(Flux.empty());
        when(dynamicSecurityRepositoryService.getPublicPaths()).thenReturn(List.of("/health"));
        when(dynamicSecurityRepositoryService.getRolePathAccess()).thenReturn(Map.of("admin", List.of("/**")));

        StepVerifier.create(importExportController.exportConfig("yaml", null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    GatewayConfigExportDto dto = response.getBody();
                    assertNotNull(dto);
                    assertNotNull(dto.getSecurity());
                    assertTrue(dto.getSecurity().getPublicPaths().contains("/health"));
                    assertTrue(dto.getSecurity().getRolePathAccess().containsKey("admin"));
                })
                .verifyComplete();
    }

    @Test
    void testImportConfigOverrideMode() {
        GatewayConfigExportDto dto = new GatewayConfigExportDto();
        RouteDefinitionDto routeDto = new RouteDefinitionDto();
        routeDto.setId("override-service");
        routeDto.setUri("http://localhost:8081");
        dto.setRoutes(List.of(routeDto));

        GatewaySecurityConfigDto sec = new GatewaySecurityConfigDto();
        sec.setPublicPaths(List.of("/public/override"));
        sec.setRolePathAccess(Map.of("admin", List.of("/admin/override/**")));
        dto.setSecurity(sec);

        when(dynamicRouteRepository.deleteAllRoutes()).thenReturn(Mono.empty());
        when(dynamicRouteRepository.saveAllRoutes(any())).thenReturn(Flux.empty());
        when(dynamicSecurityRepositoryService.overrideSecurity(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(importExportController.importConfig(Mono.just(dto), "override", "yaml", null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    GatewayConfigExportDto res = response.getBody();
                    assertNotNull(res);
                    assertEquals(1, res.getRoutes().size());
                    assertEquals("override-service", res.getRoutes().get(0).getId());
                })
                .verifyComplete();

        verify(dynamicSecurityRepositoryService, times(1)).overrideSecurity(any(), any());
    }

    @Test
    void testImportConfigMergeMode() {
        GatewayConfigExportDto dto = new GatewayConfigExportDto();
        RouteDefinitionDto routeDto = new RouteDefinitionDto();
        routeDto.setId("merged-service");
        routeDto.setUri("http://localhost:8082");
        dto.setRoutes(List.of(routeDto));

        GatewaySecurityConfigDto sec = new GatewaySecurityConfigDto();
        sec.setPublicPaths(List.of("/new/path"));
        sec.setRolePathAccess(Map.of("admin", List.of("/new/admin/**")));
        dto.setSecurity(sec);

        when(dynamicRouteRepository.saveRoute(any(RouteDefinition.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(dynamicSecurityRepositoryService.mergeSecurity(any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(importExportController.importConfig(Mono.just(dto), "merge", "yaml", null))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    GatewayConfigExportDto res = response.getBody();
                    assertNotNull(res);
                })
                .verifyComplete();

        verify(dynamicSecurityRepositoryService, times(1)).mergeSecurity(any(), any());
    }
}
