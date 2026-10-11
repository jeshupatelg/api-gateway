package com.jpg.apigateway.security.session;

import java.time.Instant;
import java.util.List;

/**
 * Data transfer object representing an active user session in the API Gateway.
 */
public record GatewaySessionDto(
        String sessionId,
        String keycloakSessionId,
        String username,
        String displayName,
        String email,
        List<String> roles,
        String clientIp,
        String userAgent,
        Instant loginTime,
        Instant lastAccessTime,
        boolean current
) {}
