package com.jpg.apigateway.controller;

import com.jpg.apigateway.api.RouteManagementApi;
import com.jpg.apigateway.config.DynamicRouteDefinitionRepositoryService;
import com.jpg.apigateway.mapper.GatewayRouteMapper;
import com.jpg.apigateway.model.RouteDefinitionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Controller providing REST API endpoints for managing Spring Cloud Gateway routes dynamically.
 * Implements the OpenAPI generated {@link RouteManagementApi} interface.
 * Delegates route persistence to {@link DynamicRouteDefinitionRepositoryService} and mappings to {@link GatewayRouteMapper}.
 */
@RestController
public class RouteManagementController implements RouteManagementApi {

    private static final Logger log = LoggerFactory.getLogger(RouteManagementController.class);

    private final DynamicRouteDefinitionRepositoryService dynamicRouteDefinitionRepositoryService;
    private final GatewayRouteMapper routeMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Constructs the RouteManagementController with required dependencies.
     *
     * @param dynamicRouteDefinitionRepositoryService in-memory gateway route definition repository and persistence manager
     * @param routeMapper mapper bean for DTO and RouteDefinition transformations
     * @param eventPublisher application event publisher to trigger gateway route refresh events
     */
    public RouteManagementController(DynamicRouteDefinitionRepositoryService dynamicRouteDefinitionRepositoryService,
                                     GatewayRouteMapper routeMapper,
                                     ApplicationEventPublisher eventPublisher) {
        this.dynamicRouteDefinitionRepositoryService = dynamicRouteDefinitionRepositoryService;
        this.routeMapper = routeMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Retrieves all configured gateway routes.
     * Served with zero DB round-trips when in-memory cache is populated.
     *
     * @param exchange current server web exchange
     * @return Mono containing ResponseEntity with a Flux of RouteDefinitionDto objects
     */
    @Override
    public Mono<ResponseEntity<Flux<RouteDefinitionDto>>> getRoutes(ServerWebExchange exchange) {
        Flux<RouteDefinitionDto> routesFlux = dynamicRouteDefinitionRepositoryService.getAllRoutes().map(routeMapper::toDto);
        return Mono.just(ResponseEntity.ok(routesFlux));
    }

    /**
     * Adds or updates a gateway route definition.
     * Converts DTO to Spring RouteDefinition and delegates persistence to repository.
     * Triggers a {@link RefreshRoutesEvent} upon successful save.
     *
     * @param routeDefinitionDto Mono containing the route definition DTO to save
     * @param exchange current server web exchange
     * @return Mono containing ResponseEntity with the saved RouteDefinitionDto
     */
    @Override
    public Mono<ResponseEntity<RouteDefinitionDto>> saveRoute(Mono<RouteDefinitionDto> routeDefinitionDto, ServerWebExchange exchange) {
        return routeDefinitionDto
                .map(routeMapper::toRouteDefinition)
                .flatMap(dynamicRouteDefinitionRepositoryService::saveRoute)
                .map(routeMapper::toDto)
                .doOnSuccess(saved -> eventPublisher.publishEvent(new RefreshRoutesEvent(this)))
                .map(ResponseEntity::ok);
    }

    /**
     * Deletes a gateway route by its ID.
     * If the route is missing from cache, returns 404 with 0 DB calls.
     * If the route exists, performs exactly 1 DB DELETE call and evicts from runtime cache.
     * Triggers a {@link RefreshRoutesEvent} upon successful deletion.
     *
     * @param id route ID to delete
     * @param exchange current server web exchange
     * @return Mono containing ResponseEntity with NO_CONTENT status or 404 NOT_FOUND
     */
    @Override
    public Mono<ResponseEntity<Void>> deleteRoute(String id, ServerWebExchange exchange) {
        return dynamicRouteDefinitionRepositoryService.deleteRoute(id)
                .flatMap(deleted -> {
                    if (Boolean.TRUE.equals(deleted)) {
                        eventPublisher.publishEvent(new RefreshRoutesEvent(this));
                        return Mono.just(new ResponseEntity<Void>(HttpStatus.NO_CONTENT));
                    } else {
                        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
                    }
                });
    }

    /**
     * Enables a route by its ID. Idempotent.
     * If the route is missing from cache, returns 404 with 0 DB calls.
     * If the route exists, performs exactly 1 DB UPDATE call without requiring a prior SELECT query.
     *
     * @param id route ID to enable
     * @param exchange current server web exchange
     * @return Mono containing ResponseEntity with updated RouteDefinitionDto or 404 NOT_FOUND
     */
    @Override
    public Mono<ResponseEntity<RouteDefinitionDto>> enableRoute(String id, ServerWebExchange exchange) {
        return dynamicRouteDefinitionRepositoryService.enableRoute(id)
                .map(routeMapper::toDto)
                .doOnSuccess(saved -> eventPublisher.publishEvent(new RefreshRoutesEvent(this)))
                .map(ResponseEntity::ok)
                .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build()));
    }

    /**
     * Disables a route by its ID. Idempotent.
     * If the route is missing from cache, returns 404 with 0 DB calls.
     * If the route exists, performs exactly 1 DB UPDATE call without requiring a prior SELECT query.
     *
     * @param id route ID to disable
     * @param exchange current server web exchange
     * @return Mono containing ResponseEntity with updated RouteDefinitionDto or 404 NOT_FOUND
     */
    @Override
    public Mono<ResponseEntity<RouteDefinitionDto>> disableRoute(String id, ServerWebExchange exchange) {
        return dynamicRouteDefinitionRepositoryService.disableRoute(id)
                .map(routeMapper::toDto)
                .doOnSuccess(saved -> eventPublisher.publishEvent(new RefreshRoutesEvent(this)))
                .map(ResponseEntity::ok)
                .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND).build()));
    }

    /**
     * Delegation helper converting RouteDefinition to RouteDefinitionDto.
     *
     * @param rd spring cloud route definition
     * @return populated RouteDefinitionDto
     */
    public RouteDefinitionDto toDto(RouteDefinition rd) {
        return routeMapper.toDto(rd);
    }

    /**
     * Delegation helper converting RouteDefinitionDto to RouteDefinition.
     *
     * @param dto input DTO
     * @return populated RouteDefinition
     */
    public RouteDefinition toRouteDefinition(RouteDefinitionDto dto) {
        return routeMapper.toRouteDefinition(dto);
    }
}
