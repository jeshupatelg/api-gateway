package com.jpg.apigateway.controller;

import com.jpg.apigateway.api.ConfigImportExportApi;
import com.jpg.apigateway.config.DynamicRouteDefinitionRepositoryService;
import com.jpg.apigateway.config.DynamicSecurityRepositoryService;
import com.jpg.apigateway.mapper.GatewayRouteMapper;
import com.jpg.apigateway.model.GatewayConfigExportDto;
import com.jpg.apigateway.model.GatewaySecurityConfigDto;
import com.jpg.apigateway.model.RouteDefinitionDto;
import org.jspecify.annotations.NonNull;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Controller for exporting and importing API Gateway configuration as POJO data structures.
 * Supports format choices (YAML or JSON) and import mode selection ('override' or 'merge').
 * Route persistence is entirely delegated to {@link DynamicRouteDefinitionRepositoryService} and mappings to {@link GatewayRouteMapper}.
 * Security persistence and caching is entirely delegated to {@link DynamicSecurityRepositoryService}.
 */
@RestController
public class ConfigImportExportController implements ConfigImportExportApi {

    private final DynamicRouteDefinitionRepositoryService dynamicRouteRepositoryService;
    private final DynamicSecurityRepositoryService dynamicSecurityRepositoryService;
    private final GatewayRouteMapper routeMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Constructs the ConfigImportExportController with required persistence services, mappers, and event publishers.
     *
     * @param dynamicRouteRepositoryService in-memory gateway route definition repository and route persistence manager
     * @param dynamicSecurityRepositoryService gateway security persistence and cache manager
     * @param routeMapper mapper bean for route transformations
     * @param eventPublisher application event publisher for route refresh events
     */
    public ConfigImportExportController(DynamicRouteDefinitionRepositoryService dynamicRouteRepositoryService,
                                        DynamicSecurityRepositoryService dynamicSecurityRepositoryService,
                                        GatewayRouteMapper routeMapper,
                                        ApplicationEventPublisher eventPublisher) {
        this.dynamicRouteRepositoryService = dynamicRouteRepositoryService;
        this.dynamicSecurityRepositoryService = dynamicSecurityRepositoryService;
        this.routeMapper = routeMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Exports the active gateway configuration as a POJO (GatewayConfigExportDto).
     * Routes are retrieved from {@link DynamicRouteDefinitionRepositoryService} and mapped to DTOs.
     * Spring WebFlux handles HTTP content negotiation for JSON or YAML based on request parameters.
     *
     * @param format target format ('yaml' or 'json')
     * @param exchange current server web exchange
     * @return Mono containing ResponseEntity with GatewayConfigExportDto POJO
     */
    @Override
    public Mono<ResponseEntity<GatewayConfigExportDto>> exportConfig(String format, ServerWebExchange exchange) {
        return dynamicRouteRepositoryService.getAllRoutes()
                .map(routeMapper::toDto)
                .collectList()
                .map(routes -> {
                    GatewayConfigExportDto dto = new GatewayConfigExportDto();
                    dto.setRoutes(routes);

                    GatewaySecurityConfigDto sec = new GatewaySecurityConfigDto();
                    sec.setPublicPaths(dynamicSecurityRepositoryService.getPublicPaths());
                    sec.setRolePathAccess(dynamicSecurityRepositoryService.getRolePathAccess());
                    dto.setSecurity(sec);
                    return ResponseEntity.ok(dto);
                });
    }

    /**
     * Imports configuration from a POJO payload (GatewayConfigExportDto).
     * Supports 'override' mode (clears existing configuration before inserting) and 'merge' mode (upserts without removing existing rules).
     * Security config is no-op when null
     * Routes empty in override will clear all routes
     *
     * @param body Mono containing imported GatewayConfigExportDto POJO
     * @param mode import mode ('override' or 'merge')
     * @param format input format ('yaml' or 'json')
     * @param exchange current server web exchange
     * @return Mono containing ResponseEntity with the processed GatewayConfigExportDto POJO
     */
    @Override
    public Mono<ResponseEntity<GatewayConfigExportDto>> importConfig(Mono<GatewayConfigExportDto> body, String mode, String format, ServerWebExchange exchange) {
        boolean isMerge = "merge".equalsIgnoreCase(mode);
        return body.flatMap(dto -> {
            List<RouteDefinitionDto> routes = dto.getRoutes() != null ? dto.getRoutes() : List.of();
            GatewaySecurityConfigDto sec = dto.getSecurity();

            Mono<Void> importRoutesMono = importRoutes(isMerge, routes);

            Mono<Void> importSecurityMono = Mono.empty();
            // security config is no-op when null
            if (sec != null) {
                List<String> importedPubPaths = sec.getPublicPaths() != null ? sec.getPublicPaths() : List.of();
                Map<String, List<String>> importedRoleAccess = sec.getRolePathAccess() != null ? sec.getRolePathAccess() : Map.of();

                if (isMerge) {
                    // Merge Mode: Union public paths and merge role-path maps
                    importSecurityMono = importMergeSecurity(importedPubPaths, importedRoleAccess);

                } else {
                    // Override Mode: Replace public paths and role-path maps completely
                    importSecurityMono = importOverrideSecurity(importedPubPaths, importedRoleAccess);
                }
            }

            return Mono.when(importRoutesMono, importSecurityMono)
                    .thenReturn(ResponseEntity.ok(dto));
        });
    }

    private @NonNull Mono<Void> importOverrideSecurity(List<String> importedPubPaths, Map<String, List<String>> importedRoleAccess) {
        return dynamicSecurityRepositoryService.overrideSecurity(importedPubPaths, importedRoleAccess);
    }

    private @NonNull Mono<Void> importMergeSecurity(List<String> importedPubPaths, Map<String, List<String>> importedRoleAccess) {
        return dynamicSecurityRepositoryService.mergeSecurity(importedPubPaths, importedRoleAccess);
    }

    private @NonNull Mono<Void> importRoutes(boolean isMerge, List<RouteDefinitionDto> routes) {
        Mono<Void> importRoutesMono;
        if (isMerge) {
            // Merge Mode: Upsert each imported route via DynamicRouteDefinitionRepositoryService
            importRoutesMono = Flux.fromIterable(routes)
                    .map(routeMapper::toRouteDefinition)
                    .flatMap(dynamicRouteRepositoryService::saveRoute)
                    .then()
                    .doOnSuccess(v -> eventPublisher.publishEvent(new RefreshRoutesEvent(this)));
        } else {
            // Override Mode: Clear all existing routes and save new imported routes via DynamicRouteDefinitionRepositoryService
            // should execute unconditionally for override
            importRoutesMono = dynamicRouteRepositoryService.deleteAllRoutes()
                    .then(Mono.defer(() -> {
                        List<RouteDefinition> definitions = new ArrayList<>();
                        for (RouteDefinitionDto rDto : routes) {
                            RouteDefinition rd = routeMapper.toRouteDefinition(rDto);
                            if (rd.getId() == null || rd.getId().isBlank()) {
                                rd.setId(UUID.randomUUID().toString());
                            }
                            definitions.add(rd);
                        }
                        return dynamicRouteRepositoryService.saveAllRoutes(definitions).then();
                    }))
                    .doOnSuccess(v -> eventPublisher.publishEvent(new RefreshRoutesEvent(this)));
        }
        return importRoutesMono;
    }
}
