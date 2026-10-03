package com.jpg.apigateway.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.jpg.apigateway.domain.GatewayRouteEntity;
import com.jpg.apigateway.domain.SecurityPublicPathEntity;
import com.jpg.apigateway.domain.SecurityRolePathEntity;
import com.jpg.apigateway.mapper.GatewayRouteMapper;
import com.jpg.apigateway.repository.GatewayRouteR2dbcRepository;
import com.jpg.apigateway.repository.SecurityPublicPathR2dbcRepository;
import com.jpg.apigateway.repository.SecurityRolePathR2dbcRepository;
import com.jpg.apigateway.security.service.DynamicSecurityCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.time.Instant;
import java.util.*;

/**
 * Bootstraps gateway configuration at startup.
 * Checks DB for security rules and routes. If absent in DB, seeds defaults from git YAML files into DB.
 */
@Component
public class StartupConfigInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupConfigInitializer.class);

    private final GatewayRouteR2dbcRepository routeRepository;
    private final SecurityPublicPathR2dbcRepository publicPathRepository;
    private final SecurityRolePathR2dbcRepository rolePathRepository;
    private final DynamicRouteDefinitionRepositoryService dynamicRouteDefinitionRepositoryService;
    private final DynamicSecurityCacheService dynamicSecurityCacheService;
    private final GatewayRouteMapper routeMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;

    /**
     * Constructs the StartupConfigInitializer with required repositories, mappers, and services.
     *
     * @param routeRepository database repository for gateway routes
     * @param publicPathRepository database repository for public paths
     * @param rolePathRepository database repository for role-path mappings
     * @param dynamicRouteDefinitionRepositoryService in-memory gateway route definition repository
     * @param dynamicSecurityCacheService dynamic security cache service
     * @param routeMapper mapper bean for route transformations
     * @param eventPublisher application event publisher for route refresh events
     * @param jsonMapper JSON object mapper
     */
    public StartupConfigInitializer(GatewayRouteR2dbcRepository routeRepository,
                                    SecurityPublicPathR2dbcRepository publicPathRepository,
                                    SecurityRolePathR2dbcRepository rolePathRepository,
                                    DynamicRouteDefinitionRepositoryService dynamicRouteDefinitionRepositoryService,
                                    DynamicSecurityCacheService dynamicSecurityCacheService,
                                    GatewayRouteMapper routeMapper,
                                    ApplicationEventPublisher eventPublisher,
                                    ObjectMapper jsonMapper) {
        this.routeRepository = routeRepository;
        this.publicPathRepository = publicPathRepository;
        this.rolePathRepository = rolePathRepository;
        this.dynamicRouteDefinitionRepositoryService = dynamicRouteDefinitionRepositoryService;
        this.dynamicSecurityCacheService = dynamicSecurityCacheService;
        this.routeMapper = routeMapper;
        this.eventPublisher = eventPublisher;
        this.jsonMapper = jsonMapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    /**
     * Executes configuration bootstrapping when the Spring Boot application starts.
     *
     * @param args application arguments
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("Initializing Hybrid DB+File Gateway Configuration...");
        initializeSecurityConfig()
                .then(initializeRouteConfig())
                .subscribe(
                        null,
                        error -> log.error("Failed to initialize gateway configuration from DB/File", error),
                        () -> log.info("Gateway Configuration initialization completed successfully.")
                );
    }

    /**
     * Checks database for security rules. If empty, seeds defaults from classpath default-security.yaml; otherwise loads DB records into memory.
     *
     * @return Mono completing when security initialization is finished
     */
    public Mono<Void> initializeSecurityConfig() {
        return Mono.zip(publicPathRepository.count(), rolePathRepository.count())
                .flatMap(tuple -> {
                    long pubCount = tuple.getT1();
                    long roleCount = tuple.getT2();
                    if (pubCount == 0 && roleCount == 0) {
                        log.info("DB security configuration is empty. Loading git default-security.yaml...");
                        return seedDefaultSecurityConfig();
                    } else {
                        log.info("Loading security configuration from DB...");
                        return loadSecurityConfigFromDb();
                    }
                });
    }

    /**
     * Reads default-security.yaml from classpath and inserts public paths and role-path rules into database.
     *
     * @return Mono completing when seeding finishes
     */
    private Mono<Void> seedDefaultSecurityConfig() {
        try {
            ClassPathResource resource = new ClassPathResource("config/default-security.yaml");
            if (!resource.exists()) {
                log.warn("default-security.yaml not found on classpath.");
                return Mono.empty();
            }
            try (InputStream is = resource.getInputStream()) {
                Map<String, Object> root = yamlMapper.readValue(is, new TypeReference<>() {});
                Map<String, Object> gateway = (Map<String, Object>) root.getOrDefault("gateway", Map.of());
                Map<String, Object> security = (Map<String, Object>) gateway.getOrDefault("security", Map.of());

                List<String> publicPaths = (List<String>) security.getOrDefault("public-paths", List.of());
                Map<String, List<String>> rolePathAccess = (Map<String, List<String>>) security.getOrDefault("role-path-access", Map.of());

                List<SecurityPublicPathEntity> publicPathEntities = publicPaths.stream()
                        .map(SecurityPublicPathEntity::new)
                        .toList();

                List<SecurityRolePathEntity> rolePathEntities = new ArrayList<>();
                rolePathAccess.forEach((role, paths) -> {
                    if (paths != null) {
                        paths.forEach(p -> rolePathEntities.add(new SecurityRolePathEntity(role, p)));
                    }
                });

                Mono<Void> savePublic = publicPathRepository.saveAll(publicPathEntities).then();
                Mono<Void> saveRoles = rolePathRepository.saveAll(rolePathEntities).then();

                return Mono.when(savePublic, saveRoles)
                        .doOnSuccess(v -> {
                            dynamicSecurityCacheService.setPublicPaths(publicPaths);
                            dynamicSecurityCacheService.setRolePathAccess(rolePathAccess);
                            log.info("Seeded {} public paths and {} role-path rules into DB.", publicPathEntities.size(), rolePathEntities.size());
                        });
            }
        } catch (Exception e) {
            log.error("Error reading default-security.yaml", e);
            return Mono.error(e);
        }
    }

    /**
     * Loads security configuration from database into dynamic security service cache.
     *
     * @return Mono completing when loading finishes
     */
    private Mono<Void> loadSecurityConfigFromDb() {
        Mono<List<String>> publicPathsMono = publicPathRepository.findAll()
                .map(SecurityPublicPathEntity::getPathPattern)
                .collectList();

        Mono<Map<String, List<String>>> rolePathMono = rolePathRepository.findAll()
                .collectMultimap(SecurityRolePathEntity::getRoleName, SecurityRolePathEntity::getPathPattern)
                .map(multimap -> {
                    Map<String, List<String>> map = new LinkedHashMap<>();
                    multimap.forEach((role, paths) -> map.put(role, new ArrayList<>(paths)));
                    return map;
                });

        return Mono.zip(publicPathsMono, rolePathMono)
                .doOnSuccess(tuple -> {
                    dynamicSecurityCacheService.setPublicPaths(tuple.getT1());
                    dynamicSecurityCacheService.setRolePathAccess(tuple.getT2());
                    log.info("Loaded {} public paths and {} role mappings from DB into memory.", tuple.getT1().size(), tuple.getT2().size());
                })
                .then();
    }

    /**
     * Checks database for route definitions. If empty, seeds defaults from classpath default-routes.yaml; otherwise loads DB records into memory.
     *
     * @return Mono completing when route initialization is finished
     */
    public Mono<Void> initializeRouteConfig() {
        return routeRepository.count()
                .flatMap(count -> {
                    if (count == 0) {
                        log.info("DB route configuration is empty. Loading git default-routes.yaml...");
                        return seedDefaultRouteConfig();
                    } else {
                        log.info("Loading route configuration from DB...");
                        return loadRoutesFromDb();
                    }
                });
    }

    /**
     * Reads default-routes.yaml from classpath and inserts route definitions into database.
     *
     * @return Mono completing when seeding finishes
     */
    private Mono<Void> seedDefaultRouteConfig() {
        try {
            ClassPathResource resource = new ClassPathResource("config/default-routes.yaml");
            if (!resource.exists()) {
                log.warn("default-routes.yaml not found on classpath.");
                return Mono.empty();
            }
            try (InputStream is = resource.getInputStream()) {
                Map<String, Object> root = yamlMapper.readValue(is, new TypeReference<>() {});
                Map<String, Object> spring = (Map<String, Object>) root.getOrDefault("spring", Map.of());
                Map<String, Object> cloud = (Map<String, Object>) spring.getOrDefault("cloud", Map.of());
                Map<String, Object> gateway = (Map<String, Object>) cloud.getOrDefault("gateway", Map.of());
                List<Map<String, Object>> routeMaps = (List<Map<String, Object>>) gateway.getOrDefault("routes", List.of());

                if (routeMaps.isEmpty()) {
                    log.info("default-routes.yaml contains 0 routes. Gateway initialized with empty routes.");
                    return Mono.empty();
                }

                List<GatewayRouteEntity> entities = new ArrayList<>();
                List<RouteDefinition> definitions = new ArrayList<>();

                for (Map<String, Object> map : routeMaps) {
                    RouteDefinition rd = jsonMapper.convertValue(map, RouteDefinition.class);
                    definitions.add(rd);
                    GatewayRouteEntity entity = routeMapper.toEntity(rd);
                    if (entity.getId() == null || entity.getId().isBlank()) {
                        entity.setId(UUID.randomUUID().toString());
                    }
                    if (entity.getCreatedAt() == null) {
                        entity.setCreatedAt(Instant.now());
                    }
                    entity.setUpdatedAt(Instant.now());
                    entities.add(entity);
                }

                return routeRepository.saveAll(entities)
                        .collectList()
                        .doOnSuccess(saved -> {
                            dynamicRouteDefinitionRepositoryService.updateCache(definitions);
                            eventPublisher.publishEvent(new RefreshRoutesEvent(this));
                            log.info("Seeded {} default routes into DB and triggered RefreshRoutesEvent.", saved.size());
                        })
                        .then();
            }
        } catch (Exception e) {
            log.error("Error reading default-routes.yaml", e);
            return Mono.error(e);
        }
    }

    /**
     * Loads route definitions from database into dynamic route repository cache and fires RefreshRoutesEvent.
     *
     * @return Mono completing when loading finishes
     */
    private Mono<Void> loadRoutesFromDb() {
        return routeRepository.findAll()
                .map(routeMapper::toRouteDefinition)
                .collectList()
                .doOnSuccess(definitions -> {
                    dynamicRouteDefinitionRepositoryService.updateCache(definitions);
                    eventPublisher.publishEvent(new RefreshRoutesEvent(this));
                    log.info("Loaded {} routes from DB and triggered RefreshRoutesEvent.", definitions.size());
                })
                .then();
    }
}
