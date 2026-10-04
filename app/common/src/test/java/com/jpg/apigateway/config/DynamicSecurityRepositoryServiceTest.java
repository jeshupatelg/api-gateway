package com.jpg.apigateway.config;

import com.jpg.apigateway.domain.SecurityPublicPathEntity;
import com.jpg.apigateway.domain.SecurityRolePathEntity;
import com.jpg.apigateway.repository.SecurityPublicPathR2dbcRepository;
import com.jpg.apigateway.repository.SecurityRolePathR2dbcRepository;
import com.jpg.apigateway.security.service.DynamicSecurityCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DynamicSecurityRepositoryService} verifying persistence operations
 * and cache synchronization for dynamic gateway security configurations.
 */
@ExtendWith(MockitoExtension.class)
class DynamicSecurityRepositoryServiceTest {

    @Mock
    private SecurityPublicPathR2dbcRepository publicPathRepository;

    @Mock
    private SecurityRolePathR2dbcRepository rolePathRepository;

    @Mock
    private DynamicSecurityCacheService dynamicSecurityCacheService;

    private DynamicSecurityRepositoryService service;

    /**
     * Sets up test instances before each test execution.
     */
    @BeforeEach
    void setUp() {
        service = new DynamicSecurityRepositoryService(
                publicPathRepository,
                rolePathRepository,
                dynamicSecurityCacheService
        );
    }

    /**
     * Verifies retrieving public paths delegates to dynamicSecurityCacheService.
     */
    @Test
    void testGetPublicPaths() {
        List<String> expected = List.of("/api/v1/public/**", "/health");
        when(dynamicSecurityCacheService.getPublicPaths()).thenReturn(expected);

        List<String> actual = service.getPublicPaths();

        assertEquals(expected, actual);
        verify(dynamicSecurityCacheService).getPublicPaths();
    }

    /**
     * Verifies retrieving role path access delegates to dynamicSecurityCacheService.
     */
    @Test
    void testGetRolePathAccess() {
        Map<String, List<String>> expected = Map.of("ROLE_ADMIN", List.of("/api/v1/admin/**"));
        when(dynamicSecurityCacheService.getRolePathAccess()).thenReturn(expected);

        Map<String, List<String>> actual = service.getRolePathAccess();

        assertEquals(expected, actual);
        verify(dynamicSecurityCacheService).getRolePathAccess();
    }

    /**
     * Verifies updatePublicPaths deletes old paths, saves new ones, and refreshes the cache.
     */
    @Test
    void testUpdatePublicPaths() {
        List<String> paths = List.of("/api/v1/auth/**", "/public/**");

        when(publicPathRepository.deleteAll()).thenReturn(Mono.empty());
        when(publicPathRepository.saveAll(anyIterable())).thenAnswer(inv -> {
            Iterable<SecurityPublicPathEntity> iterable = inv.getArgument(0);
            return Flux.fromIterable(iterable);
        });

        StepVerifier.create(service.updatePublicPaths(paths))
                .expectNext(paths)
                .verifyComplete();

        verify(publicPathRepository).deleteAll();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<SecurityPublicPathEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(publicPathRepository).saveAll(captor.capture());

        List<SecurityPublicPathEntity> saved = new ArrayList<>();
        captor.getValue().forEach(saved::add);
        assertEquals(2, saved.size());
        assertEquals("/api/v1/auth/**", saved.get(0).getPathPattern());
        assertEquals("/public/**", saved.get(1).getPathPattern());

        verify(dynamicSecurityCacheService).setPublicPaths(paths);
    }

    /**
     * Verifies addPublicPath merges a new path when it does not already exist.
     */
    @Test
    void testAddPublicPath_WhenNewPath() {
        when(dynamicSecurityCacheService.getPublicPaths()).thenReturn(List.of("/health"));
        when(publicPathRepository.deleteAll()).thenReturn(Mono.empty());
        when(publicPathRepository.saveAll(anyIterable())).thenAnswer(inv -> {
            Iterable<SecurityPublicPathEntity> iterable = inv.getArgument(0);
            return Flux.fromIterable(iterable);
        });

        StepVerifier.create(service.addPublicPath("/api/v1/auth/**"))
                .expectNext(List.of("/health", "/api/v1/auth/**"))
                .verifyComplete();

        verify(publicPathRepository).deleteAll();
        verify(dynamicSecurityCacheService).setPublicPaths(List.of("/health", "/api/v1/auth/**"));
    }

    /**
     * Verifies addPublicPath returns existing paths without saving when path already exists.
     */
    @Test
    void testAddPublicPath_WhenExistingPath() {
        when(dynamicSecurityCacheService.getPublicPaths()).thenReturn(List.of("/health"));

        StepVerifier.create(service.addPublicPath("/health"))
                .expectNext(List.of("/health"))
                .verifyComplete();

        verify(publicPathRepository, never()).deleteAll();
        verify(publicPathRepository, never()).saveAll(anyIterable());
    }

    /**
     * Verifies addPublicPath returns current paths directly when path is null or blank.
     */
    @Test
    void testAddPublicPath_WhenNullOrBlank() {
        when(dynamicSecurityCacheService.getPublicPaths()).thenReturn(List.of("/health"));

        StepVerifier.create(service.addPublicPath("   "))
                .expectNext(List.of("/health"))
                .verifyComplete();

        verify(publicPathRepository, never()).deleteAll();
    }

    /**
     * Verifies updateRolePathAccess deletes old role mappings, saves new ones, and refreshes cache.
     */
    @Test
    void testUpdateRolePathAccess() {
        Map<String, List<String>> roleMap = Map.of(
                "ROLE_USER", List.of("/api/v1/user/**"),
                "ROLE_ADMIN", List.of("/api/v1/admin/**")
        );

        when(rolePathRepository.deleteAll()).thenReturn(Mono.empty());
        when(rolePathRepository.saveAll(anyIterable())).thenAnswer(inv -> {
            Iterable<SecurityRolePathEntity> iterable = inv.getArgument(0);
            return Flux.fromIterable(iterable);
        });

        StepVerifier.create(service.updateRolePathAccess(roleMap))
                .expectNext(roleMap)
                .verifyComplete();

        verify(rolePathRepository).deleteAll();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<SecurityRolePathEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(rolePathRepository).saveAll(captor.capture());

        List<SecurityRolePathEntity> saved = new ArrayList<>();
        captor.getValue().forEach(saved::add);
        assertEquals(2, saved.size());

        verify(dynamicSecurityCacheService).setRolePathAccess(roleMap);
    }

    /**
     * Verifies overrideSecurity clears both repositories, persists all new entities, and sets cache.
     */
    @Test
    void testOverrideSecurity() {
        List<String> paths = List.of("/public/**");
        Map<String, List<String>> roles = Map.of("ROLE_ADMIN", List.of("/admin/**"));

        when(publicPathRepository.deleteAll()).thenReturn(Mono.empty());
        when(rolePathRepository.deleteAll()).thenReturn(Mono.empty());
        when(publicPathRepository.saveAll(anyIterable())).thenAnswer(inv -> Flux.fromIterable(inv.getArgument(0)));
        when(rolePathRepository.saveAll(anyIterable())).thenAnswer(inv -> Flux.fromIterable(inv.getArgument(0)));

        StepVerifier.create(service.overrideSecurity(paths, roles))
                .verifyComplete();

        verify(publicPathRepository).deleteAll();
        verify(rolePathRepository).deleteAll();
        verify(publicPathRepository).saveAll(anyIterable());
        verify(rolePathRepository).saveAll(anyIterable());
        verify(dynamicSecurityCacheService).setPublicPaths(paths);
        verify(dynamicSecurityCacheService).setRolePathAccess(roles);
    }

    /**
     * Verifies mergeSecurity combines existing cache rules with imported rules without dropping existing ones.
     */
    @Test
    void testMergeSecurity() {
        when(dynamicSecurityCacheService.getPublicPaths()).thenReturn(List.of("/health"));
        when(dynamicSecurityCacheService.getRolePathAccess()).thenReturn(Map.of("ROLE_ADMIN", List.of("/admin/read/**")));

        when(publicPathRepository.deleteAll()).thenReturn(Mono.empty());
        when(rolePathRepository.deleteAll()).thenReturn(Mono.empty());
        when(publicPathRepository.saveAll(anyIterable())).thenAnswer(inv -> Flux.fromIterable(inv.getArgument(0)));
        when(rolePathRepository.saveAll(anyIterable())).thenAnswer(inv -> Flux.fromIterable(inv.getArgument(0)));

        List<String> importedPaths = List.of("/health", "/swagger/**");
        Map<String, List<String>> importedRoles = Map.of("ROLE_ADMIN", List.of("/admin/write/**"));

        StepVerifier.create(service.mergeSecurity(importedPaths, importedRoles))
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> pathCaptor = ArgumentCaptor.forClass(List.class);
        verify(dynamicSecurityCacheService).setPublicPaths(pathCaptor.capture());
        assertEquals(List.of("/health", "/swagger/**"), pathCaptor.getValue());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> roleCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dynamicSecurityCacheService).setRolePathAccess(roleCaptor.capture());
        Map<String, List<String>> mergedRoles = roleCaptor.getValue();
        assertEquals(List.of("/admin/read/**", "/admin/write/**"), mergedRoles.get("ROLE_ADMIN"));
    }
}
