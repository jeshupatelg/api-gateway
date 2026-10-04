package com.jpg.apigateway.controller;

import com.jpg.apigateway.config.DynamicSecurityRepositoryService;
import com.jpg.apigateway.model.GatewaySecurityConfigDto;
import com.jpg.apigateway.model.PublicPathsRequestDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SecurityManagementController} verifying that endpoint operations
 * delegate directly to {@link DynamicSecurityRepositoryService}.
 */
@ExtendWith(MockitoExtension.class)
class SecurityManagementControllerTest {

    @Mock
    private DynamicSecurityRepositoryService dynamicSecurityRepositoryService;

    private SecurityManagementController controller;
    private MockServerWebExchange exchange;

    /**
     * Initializes test fixtures before each test.
     */
    @BeforeEach
    void setUp() {
        controller = new SecurityManagementController(dynamicSecurityRepositoryService);
        exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/").build());
    }

    /**
     * Verifies getSecurityConfig retrieves public paths and role-path rules from service.
     */
    @Test
    void testGetSecurityConfig() {
        List<String> expectedPublicPaths = List.of("/health", "/api/v1/public/**");
        Map<String, List<String>> expectedRolePaths = Map.of("ROLE_ADMIN", List.of("/api/v1/admin/**"));

        when(dynamicSecurityRepositoryService.getPublicPaths()).thenReturn(expectedPublicPaths);
        when(dynamicSecurityRepositoryService.getRolePathAccess()).thenReturn(expectedRolePaths);

        StepVerifier.create(controller.getSecurityConfig(exchange))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    GatewaySecurityConfigDto body = response.getBody();
                    assertNotNull(body);
                    assertEquals(expectedPublicPaths, body.getPublicPaths());
                    assertEquals(expectedRolePaths, body.getRolePathAccess());
                })
                .verifyComplete();

        verify(dynamicSecurityRepositoryService).getPublicPaths();
        verify(dynamicSecurityRepositoryService).getRolePathAccess();
    }

    /**
     * Verifies updatePublicPaths delegates list of paths from DTO to service and returns updated flux.
     */
    @Test
    void testUpdatePublicPaths() {
        List<String> paths = List.of("/api/v1/auth/**", "/public/**");
        PublicPathsRequestDto dto = new PublicPathsRequestDto().paths(paths);
        when(dynamicSecurityRepositoryService.updatePublicPaths(paths)).thenReturn(Mono.just(paths));

        StepVerifier.create(controller.updatePublicPaths(Mono.just(dto), exchange))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    Flux<String> bodyFlux = response.getBody();
                    assertNotNull(bodyFlux);
                    StepVerifier.create(bodyFlux)
                            .expectNext("/api/v1/auth/**", "/public/**")
                            .verifyComplete();
                })
                .verifyComplete();

        verify(dynamicSecurityRepositoryService).updatePublicPaths(paths);
    }

    /**
     * Verifies addPublicPath delegates to service and returns updated flux of paths.
     */
    @Test
    void testAddPublicPath() {
        String newPath = "/api/v1/auth/**";
        List<String> updatedPaths = List.of("/health", "/api/v1/auth/**");
        when(dynamicSecurityRepositoryService.addPublicPath(newPath)).thenReturn(Mono.just(updatedPaths));

        StepVerifier.create(controller.addPublicPath(Mono.just(newPath), exchange))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    Flux<String> bodyFlux = response.getBody();
                    assertNotNull(bodyFlux);
                    StepVerifier.create(bodyFlux)
                            .expectNext("/health", "/api/v1/auth/**")
                            .verifyComplete();
                })
                .verifyComplete();

        verify(dynamicSecurityRepositoryService).addPublicPath(newPath);
    }

    /**
     * Verifies updateRolePathAccess delegates role-path mapping to service and returns updated map.
     */
    @Test
    void testUpdateRolePathAccess() {
        Map<String, List<String>> roleMap = Map.of("ROLE_USER", List.of("/api/v1/users/**"));
        when(dynamicSecurityRepositoryService.updateRolePathAccess(roleMap)).thenReturn(Mono.just(roleMap));

        StepVerifier.create(controller.updateRolePathAccess(Mono.just(roleMap), exchange))
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals(roleMap, response.getBody());
                })
                .verifyComplete();

        verify(dynamicSecurityRepositoryService).updateRolePathAccess(roleMap);
    }
}
