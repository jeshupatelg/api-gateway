package com.jpg.apigateway.repository;

import com.jpg.apigateway.domain.GatewayRouteEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * Spring Data R2DBC repository for {@link GatewayRouteEntity}.
 * The primary key string is the route identifier itself.
 */
@Repository
public interface GatewayRouteR2dbcRepository extends ReactiveCrudRepository<GatewayRouteEntity, String> {

    /**
     * Finds a route entity by route ID (same as primary key ID).
     *
     * @param routeId route identifier
     * @return Mono emitting the entity if found
     */
    default Mono<GatewayRouteEntity> findByRouteId(String routeId) {
        return findById(routeId);
    }

    /**
     * Deletes a route entity by route ID (same as primary key ID).
     *
     * @param routeId route identifier
     * @return Mono completing when deleted
     */
    default Mono<Void> deleteByRouteId(String routeId) {
        return deleteById(routeId);
    }
}
