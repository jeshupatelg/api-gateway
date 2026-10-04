package com.jpg.apigateway.config;

import com.jpg.apigateway.domain.SecurityPublicPathEntity;
import com.jpg.apigateway.domain.SecurityRolePathEntity;
import com.jpg.apigateway.repository.SecurityPublicPathR2dbcRepository;
import com.jpg.apigateway.repository.SecurityRolePathR2dbcRepository;
import com.jpg.apigateway.security.service.DynamicSecurityCacheService;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Service managing database persistence and in-memory cache synchronization for gateway security rules
 * (public paths and role-to-path access mappings).
 * Encapsulates all R2DBC repository operations for {@link SecurityPublicPathEntity} and {@link SecurityRolePathEntity},
 * keeping {@link DynamicSecurityCacheService} cache in sync.
 */
@Service
public class DynamicSecurityRepositoryService {

    private static final Logger log = LoggerFactory.getLogger(DynamicSecurityRepositoryService.class);

    private final SecurityPublicPathR2dbcRepository publicPathRepository;
    private final SecurityRolePathR2dbcRepository rolePathRepository;
    private final DynamicSecurityCacheService dynamicSecurityCacheService;

    /**
     * Constructs the DynamicSecurityRepositoryService with required repositories and in-memory security service.
     *
     * @param publicPathRepository database repository for public paths
     * @param rolePathRepository database repository for role-path mappings
     * @param dynamicSecurityCacheService in-memory dynamic security cache service
     */
    public DynamicSecurityRepositoryService(SecurityPublicPathR2dbcRepository publicPathRepository,
                                           SecurityRolePathR2dbcRepository rolePathRepository,
                                           DynamicSecurityCacheService dynamicSecurityCacheService) {
        this.publicPathRepository = publicPathRepository;
        this.rolePathRepository = rolePathRepository;
        this.dynamicSecurityCacheService = dynamicSecurityCacheService;
    }

    /**
     * Retrieves all active public paths from the in-memory cache.
     *
     * @return unmodifiable list of public path patterns
     */
    public List<String> getPublicPaths() {
        return dynamicSecurityCacheService.getPublicPaths();
    }

    /**
     * Retrieves all active role-to-path access rules from the in-memory cache.
     *
     * @return unmodifiable map of role to path patterns
     */
    public Map<String, List<String>> getRolePathAccess() {
        return dynamicSecurityCacheService.getRolePathAccess();
    }

    /**
     * Replaces public path rules in database and updates in-memory cache.
     *
     * @param paths list of public path patterns
     * @return Mono emitting list of saved paths
     */
    public Mono<List<String>> updatePublicPaths(List<String> paths) {
        List<SecurityPublicPathEntity> entities = paths != null
                ? paths.stream().map(SecurityPublicPathEntity::new).toList()
                : List.of();

        return publicPathRepository.deleteAll()
                .thenMany(publicPathRepository.saveAll(entities))
                .collectList()
                .map(saved -> {
                    dynamicSecurityCacheService.setPublicPaths(paths);
                    return paths != null ? paths : List.of();
                });
    }

    /**
     * Adds a single public path pattern and merges it with existing public paths.
     *
     * @param path the public path pattern to add
     * @return Mono emitting the updated list of public paths
     */
    public Mono<List<String>> addPublicPath(String path) {
        if (path == null || path.isBlank()) {
            return Mono.just(getPublicPaths());
        }
        List<String> currentPaths = new ArrayList<>(getPublicPaths());
        if (!currentPaths.contains(path)) {
            currentPaths.add(path);
            return updatePublicPaths(currentPaths);
        }
        return Mono.just(currentPaths);
    }

    /**
     * Replaces role-to-path mappings in database and updates in-memory cache.
     *
     * @param rolePathAccess map of role to path patterns
     * @return Mono emitting saved role-to-path map
     */
    public Mono<Map<String, List<String>>> updateRolePathAccess(Map<String, List<String>> rolePathAccess) {
        List<SecurityRolePathEntity> entities = new ArrayList<>();
        if (rolePathAccess != null) {
            rolePathAccess.forEach((role, paths) -> {
                if (paths != null) {
                    paths.forEach(p -> entities.add(new SecurityRolePathEntity(role, p)));
                }
            });
        }

        return rolePathRepository.deleteAll()
                .thenMany(rolePathRepository.saveAll(entities))
                .collectList()
                .map(saved -> {
                    dynamicSecurityCacheService.setRolePathAccess(rolePathAccess);
                    return rolePathAccess != null ? rolePathAccess : Map.of();
                });
    }

    /**
     * Overrides all security configuration (public paths and role-to-path access mappings).
     * Clears existing records and persists the new rules.
     *
     * @param publicPaths list of public path patterns
     * @param rolePathAccess map of role to path patterns
     * @return Mono completing when override finishes
     */
    public @NonNull Mono<Void> overrideSecurity(List<String> publicPaths, Map<String, List<String>> rolePathAccess) {
        List<SecurityPublicPathEntity> pubEntities = publicPaths != null
                ? publicPaths.stream().map(SecurityPublicPathEntity::new).toList()
                : List.of();

        List<SecurityRolePathEntity> roleEntities = new ArrayList<>();
        if (rolePathAccess != null) {
            rolePathAccess.forEach((role, paths) -> {
                if (paths != null) {
                    paths.forEach(p -> roleEntities.add(new SecurityRolePathEntity(role, p)));
                }
            });
        }

        return publicPathRepository.deleteAll()
                .then(rolePathRepository.deleteAll())
                .then(publicPathRepository.saveAll(pubEntities).then())
                .then(rolePathRepository.saveAll(roleEntities).then())
                .doOnSuccess(v -> {
                    dynamicSecurityCacheService.setPublicPaths(publicPaths != null ? publicPaths : List.of());
                    dynamicSecurityCacheService.setRolePathAccess(rolePathAccess != null ? rolePathAccess : Map.of());
                });
    }

    /**
     * Merges imported security configuration with existing active security rules.
     * Unions public paths and merges role-path lists without removing existing rules.
     *
     * @param importedPubPaths imported public path patterns
     * @param importedRoleAccess imported role-to-path access rules
     * @return Mono completing when merge finishes
     */
    public @NonNull Mono<Void> mergeSecurity(List<String> importedPubPaths, Map<String, List<String>> importedRoleAccess) {
        List<String> combinedPubPaths = new ArrayList<>(dynamicSecurityCacheService.getPublicPaths());
        if (importedPubPaths != null) {
            for (String p : importedPubPaths) {
                if (!combinedPubPaths.contains(p)) {
                    combinedPubPaths.add(p);
                }
            }
        }

        Map<String, List<String>> combinedRoleAccess = new LinkedHashMap<>();
        dynamicSecurityCacheService.getRolePathAccess().forEach((role, paths) ->
                combinedRoleAccess.put(role, paths != null ? new ArrayList<>(paths) : new ArrayList<>()));
        if (importedRoleAccess != null) {
            importedRoleAccess.forEach((role, paths) -> {
                if (paths != null) {
                    List<String> existing = combinedRoleAccess.computeIfAbsent(role, k -> new ArrayList<>());
                    for (String path : paths) {
                        if (!existing.contains(path)) {
                            existing.add(path);
                        }
                    }
                }
            });
        }

        return overrideSecurity(combinedPubPaths, combinedRoleAccess);
    }
}
