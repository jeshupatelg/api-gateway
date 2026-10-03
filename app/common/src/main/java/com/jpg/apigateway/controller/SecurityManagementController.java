package com.jpg.apigateway.controller;

import com.jpg.apigateway.api.SecurityManagementApi;
import com.jpg.apigateway.config.DynamicSecurityRepositoryService;
import com.jpg.apigateway.model.GatewaySecurityConfigDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Controller providing REST API endpoints for managing gateway security configurations dynamically.
 * Implements the OpenAPI generated {@link SecurityManagementApi} interface.
 * Security persistence and caching operations are delegated to {@link DynamicSecurityRepositoryService}.
 */
@RestController
public class SecurityManagementController implements SecurityManagementApi {

    private final DynamicSecurityRepositoryService dynamicSecurityRepositoryService;

    /**
     * Constructs the SecurityManagementController with the dynamic security repository service.
     *
     * @param dynamicSecurityRepositoryService gateway security persistence and cache manager
     */
    public SecurityManagementController(DynamicSecurityRepositoryService dynamicSecurityRepositoryService) {
        this.dynamicSecurityRepositoryService = dynamicSecurityRepositoryService;
    }

    /**
     * Fetches the complete current security configuration rules (public paths and role-path mappings).
     *
     * @param exchange current server web exchange
     * @return Mono containing ResponseEntity with GatewaySecurityConfigDto
     */
    @Override
    public Mono<ResponseEntity<GatewaySecurityConfigDto>> getSecurityConfig(ServerWebExchange exchange) {
        GatewaySecurityConfigDto dto = new GatewaySecurityConfigDto();
        dto.setPublicPaths(dynamicSecurityRepositoryService.getPublicPaths());
        dto.setRolePathAccess(dynamicSecurityRepositoryService.getRolePathAccess());
        return Mono.just(ResponseEntity.ok(dto));
    }

    /**
     * Replaces the public path rules in database and updates runtime security cache.
     *
     * @param requestBody Flux containing new public path patterns
     * @param exchange current server web exchange
     * @return Mono containing ResponseEntity with Flux of updated public paths
     */
    @Override
    public Mono<ResponseEntity<Flux<String>>> updatePublicPaths(Flux<String> requestBody, ServerWebExchange exchange) {
        Flux<String> resultFlux = requestBody.collectList()
                .flatMapMany(paths -> dynamicSecurityRepositoryService.updatePublicPaths(paths)
                        .flatMapMany(Flux::fromIterable));
        return Mono.just(ResponseEntity.ok(resultFlux));
    }

    /**
     * Replaces the role-to-path access mappings in database and updates runtime security cache.
     *
     * @param rolePathAccessMono Mono containing new role-to-path mapping
     * @param exchange current server web exchange
     * @return Mono containing ResponseEntity with updated role-to-path mapping
     */
    @Override
    public Mono<ResponseEntity<Map<String, List<String>>>> updateRolePathAccess(Mono<Map<String, List<String>>> rolePathAccessMono, ServerWebExchange exchange) {
        return rolePathAccessMono.flatMap(map ->
                dynamicSecurityRepositoryService.updateRolePathAccess(map)
                        .map(ResponseEntity::ok)
        );
    }
}
