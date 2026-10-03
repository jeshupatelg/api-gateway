package com.jpg.apigateway.config;

import com.jpg.apigateway.domain.GatewayRouteEntity;
import com.jpg.apigateway.mapper.GatewayRouteMapper;
import com.jpg.apigateway.repository.GatewayRouteR2dbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DB-backed dynamic implementation of Spring Cloud Gateway's {@link RouteDefinitionRepository}.
 * Maintains a single in-memory route cache ({@link RouteDefinition}) for zero-latency routing and single-query persistence.
 * Exclusively accepts and returns Spring Cloud Gateway {@link RouteDefinition} objects;
 * all entity mapping is delegated to {@link GatewayRouteMapper}.
 */
@Component
public class DynamicRouteDefinitionRepositoryService implements RouteDefinitionRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamicRouteDefinitionRepositoryService.class);

    private final GatewayRouteR2dbcRepository repository;
    private final GatewayRouteMapper routeMapper;
    private final Map<String, RouteDefinition> routesCache = new ConcurrentHashMap<>();

    /**
     * Constructs the DynamicRouteDefinitionRepositoryService.
     *
     * @param repository database R2DBC repository for gateway routes
     * @param routeMapper mapper bean for converting between RouteDefinition and GatewayRouteEntity
     */
    public DynamicRouteDefinitionRepositoryService(GatewayRouteR2dbcRepository repository, GatewayRouteMapper routeMapper) {
        this.repository = repository;
        this.routeMapper = routeMapper;
    }

    /**
     * Retrieves all active, enabled route definitions from the single in-memory route cache or database.
     * Filtered so disabled routes are excluded from Spring Cloud Gateway routing.
     *
     * @return Flux emitting active RouteDefinition objects
     */
    @Override
    public Flux<RouteDefinition> getRouteDefinitions() {
        if (!routesCache.isEmpty()) {
            return Flux.fromIterable(routesCache.values())
                    .filter(this::isRouteEnabled);
        }
        return repository.findAll()
                .map(routeMapper::toRouteDefinition)
                .doOnNext(this::cacheRoute)
                .filter(this::isRouteEnabled);
    }

    /**
     * Retrieves all configured gateway routes (both enabled and disabled) from the single in-memory cache.
     * Served with zero DB round-trips when cache is warm.
     *
     * @return Flux emitting all RouteDefinition objects
     */
    public Flux<RouteDefinition> getAllRoutes() {
        if (!routesCache.isEmpty()) {
            return Flux.fromIterable(routesCache.values());
        }
        return repository.findAll()
                .map(routeMapper::toRouteDefinition)
                .doOnNext(this::cacheRoute);
    }

    /**
     * Retrieves a single route definition by its ID from the single in-memory cache or database.
     *
     * @param id route identifier
     * @return Mono emitting RouteDefinition or empty if not found
     */
    public Mono<RouteDefinition> getRoute(String id) {
        if (routesCache.containsKey(id)) {
            return Mono.just(routesCache.get(id));
        }
        if (!routesCache.isEmpty()) {
            return Mono.empty();
        }
        return repository.findById(id)
                .map(routeMapper::toRouteDefinition)
                .doOnNext(this::cacheRoute);
    }

    /**
     * Checks whether a route exists in the in-memory cache without hitting the database.
     *
     * @param routeId business route identifier
     * @return true if route exists in cache, false otherwise
     */
    public boolean hasRoute(String routeId) {
        return routesCache.containsKey(routeId);
    }

    /**
     * Saves a route definition to the database and synchronizes the single in-memory route cache.
     * Preserves optimistic locking @Version and timestamps to execute exactly 1 DB query without prior read.
     *
     * @param route Spring Cloud Gateway route definition to save
     * @return Mono emitting the saved RouteDefinition
     */
    public Mono<RouteDefinition> saveRoute(RouteDefinition route) {
        if (route.getMetadata() == null) {
            route.setMetadata(new ConcurrentHashMap<>());
        }

        if (routesCache.containsKey(route.getId())) {
            RouteDefinition existing = routesCache.get(route.getId());
            if (existing.getMetadata() != null) {
                if (existing.getMetadata().containsKey(GatewayRouteMapper.METADATA_VERSION)) {
                    route.getMetadata().putIfAbsent(GatewayRouteMapper.METADATA_VERSION,
                            existing.getMetadata().get(GatewayRouteMapper.METADATA_VERSION));
                }
                if (existing.getMetadata().containsKey(GatewayRouteMapper.METADATA_CREATED_AT)) {
                    route.getMetadata().putIfAbsent(GatewayRouteMapper.METADATA_CREATED_AT,
                            existing.getMetadata().get(GatewayRouteMapper.METADATA_CREATED_AT));
                }
            }
            route.getMetadata().put(GatewayRouteMapper.METADATA_UPDATED_AT, Instant.now());
            GatewayRouteEntity entity = routeMapper.toEntity(route);
            return repository.save(entity)
                    .map(routeMapper::toRouteDefinition)
                    .doOnSuccess(this::cacheRoute);
        }

        if (!routesCache.isEmpty()) {
            // Definite new route insert
            route.getMetadata().remove(GatewayRouteMapper.METADATA_VERSION);
            route.getMetadata().put(GatewayRouteMapper.METADATA_CREATED_AT, Instant.now());
            route.getMetadata().put(GatewayRouteMapper.METADATA_UPDATED_AT, Instant.now());
            GatewayRouteEntity entity = routeMapper.toEntity(route);
            return repository.save(entity)
                    .map(routeMapper::toRouteDefinition)
                    .doOnSuccess(this::cacheRoute);
        }

        // Fallback for cold cache
        return repository.findById(route.getId())
                .flatMap(existingEntity -> {
                    RouteDefinition existing = routeMapper.toRouteDefinition(existingEntity);
                    if (existing.getMetadata() != null) {
                        route.getMetadata().putIfAbsent(GatewayRouteMapper.METADATA_VERSION,
                                existing.getMetadata().get(GatewayRouteMapper.METADATA_VERSION));
                        route.getMetadata().putIfAbsent(GatewayRouteMapper.METADATA_CREATED_AT,
                                existing.getMetadata().get(GatewayRouteMapper.METADATA_CREATED_AT));
                    }
                    route.getMetadata().put(GatewayRouteMapper.METADATA_UPDATED_AT, Instant.now());
                    return repository.save(routeMapper.toEntity(route));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    route.getMetadata().remove(GatewayRouteMapper.METADATA_VERSION);
                    route.getMetadata().put(GatewayRouteMapper.METADATA_CREATED_AT, Instant.now());
                    route.getMetadata().put(GatewayRouteMapper.METADATA_UPDATED_AT, Instant.now());
                    return repository.save(routeMapper.toEntity(route));
                }))
                .map(routeMapper::toRouteDefinition)
                .doOnSuccess(this::cacheRoute);
    }

    /**
     * Spring Cloud Gateway RouteDefinitionRepository interface method.
     * Saves a RouteDefinition and updates cache.
     *
     * @param route Mono containing the RouteDefinition to save
     * @return Mono completing when save finishes
     */
    @Override
    public Mono<Void> save(Mono<RouteDefinition> route) {
        return route.flatMap(rd -> saveRoute(rd).then());
    }

    /**
     * Deletes a route by its ID from the database and evicts it from the single in-memory route cache.
     * If the route does not exist in cache, returns false with 0 DB queries.
     * If the route exists, performs a single DELETE DB call.
     *
     * @param routeId route identifier to delete
     * @return Mono emitting true if route was deleted, false if route was not found
     */
    public Mono<Boolean> deleteRoute(String routeId) {
        if (routesCache.containsKey(routeId)) {
            return repository.deleteById(routeId)
                    .then(Mono.fromRunnable(() -> routesCache.remove(routeId)))
                    .thenReturn(true);
        }
        if (!routesCache.isEmpty()) {
            return Mono.just(false);
        }
        // Fallback if cache is cold
        return repository.findById(routeId)
                .flatMap(existing -> repository.deleteById(routeId)
                        .then(Mono.fromRunnable(() -> routesCache.remove(routeId)))
                        .thenReturn(true))
                .defaultIfEmpty(false);
    }

    /**
     * Spring Cloud Gateway RouteDefinitionRepository interface method.
     * Deletes a route by route ID.
     *
     * @param routeId Mono containing the route ID to delete
     * @return Mono completing when deletion finishes
     */
    @Override
    public Mono<Void> delete(Mono<String> routeId) {
        return routeId.flatMap(id -> deleteRoute(id).then());
    }

    /**
     * Enables a route by its ID. Updates the database and in-memory cache.
     * If found in cache, updates the record in exactly 1 DB query without a prior read.
     * If the route does not exist, returns empty with 0 DB queries.
     *
     * @param routeId route identifier to enable
     * @return Mono emitting updated RouteDefinition or empty if not found
     */
    public Mono<RouteDefinition> enableRoute(String routeId) {
        if (routesCache.containsKey(routeId)) {
            RouteDefinition rd = routesCache.get(routeId);
            rd.getMetadata().put(GatewayRouteMapper.METADATA_ENABLED, true);
            rd.getMetadata().put(GatewayRouteMapper.METADATA_UPDATED_AT, Instant.now());
            GatewayRouteEntity entity = routeMapper.toEntity(rd);
            return repository.save(entity)
                    .map(routeMapper::toRouteDefinition)
                    .doOnSuccess(this::cacheRoute);
        }
        if (!routesCache.isEmpty()) {
            return Mono.empty();
        }
        return repository.findById(routeId)
                .flatMap(entity -> {
                    entity.setEnabled(true);
                    entity.setUpdatedAt(Instant.now());
                    return repository.save(entity);
                })
                .map(routeMapper::toRouteDefinition)
                .doOnSuccess(this::cacheRoute);
    }

    /**
     * Disables a route by its ID. Updates the database and in-memory cache.
     * If found in cache, updates the record in exactly 1 DB query without a prior read.
     * If the route does not exist, returns empty with 0 DB queries.
     *
     * @param routeId route identifier to disable
     * @return Mono emitting updated RouteDefinition or empty if not found
     */
    public Mono<RouteDefinition> disableRoute(String routeId) {
        if (routesCache.containsKey(routeId)) {
            RouteDefinition rd = routesCache.get(routeId);
            rd.getMetadata().put(GatewayRouteMapper.METADATA_ENABLED, false);
            rd.getMetadata().put(GatewayRouteMapper.METADATA_UPDATED_AT, Instant.now());
            GatewayRouteEntity entity = routeMapper.toEntity(rd);
            return repository.save(entity)
                    .map(routeMapper::toRouteDefinition)
                    .doOnSuccess(this::cacheRoute);
        }
        if (!routesCache.isEmpty()) {
            return Mono.empty();
        }
        return repository.findById(routeId)
                .flatMap(entity -> {
                    entity.setEnabled(false);
                    entity.setUpdatedAt(Instant.now());
                    return repository.save(entity);
                })
                .map(routeMapper::toRouteDefinition)
                .doOnSuccess(this::cacheRoute);
    }

    /**
     * Deletes all route definitions from database and clears the single in-memory route cache.
     *
     * @return Mono completing when deletion finishes
     */
    public Mono<Void> deleteAllRoutes() {
        return repository.deleteAll()
                .doOnSuccess(v -> routesCache.clear());
    }

    /**
     * Saves a collection of route definitions to the database and updates the single in-memory route cache.
     *
     * @param routes list of Spring Cloud Gateway RouteDefinition objects to save
     * @return Flux of saved RouteDefinition objects
     */
    public Flux<RouteDefinition> saveAllRoutes(List<RouteDefinition> routes) {
        List<GatewayRouteEntity> entities = routes.stream().map(routeMapper::toEntity).toList();
        return repository.saveAll(entities)
                .map(routeMapper::toRouteDefinition)
                .doOnNext(this::cacheRoute);
    }

    /**
     * Replaces the in-memory route definitions cache with a new collection of route definitions.
     *
     * @param definitions new route definitions to cache
     */
    public void updateCache(List<RouteDefinition> definitions) {
        routesCache.clear();
        if (definitions != null) {
            for (RouteDefinition rd : definitions) {
                cacheRoute(rd);
            }
        }
    }

    /**
     * Helper to store a route definition in the single route cache.
     *
     * @param rd route definition
     */
    private void cacheRoute(RouteDefinition rd) {
        if (rd != null && rd.getId() != null) {
            routesCache.put(rd.getId(), rd);
        }
    }

    /**
     * Checks if a route definition is marked as enabled in its metadata.
     *
     * @param rd route definition
     * @return true if enabled, false otherwise
     */
    private boolean isRouteEnabled(RouteDefinition rd) {
        if (rd == null || rd.getMetadata() == null) {
            return true;
        }
        Object enabledObj = rd.getMetadata().get(GatewayRouteMapper.METADATA_ENABLED);
        return enabledObj == null || Boolean.TRUE.equals(enabledObj) || "true".equalsIgnoreCase(String.valueOf(enabledObj));
    }
}
