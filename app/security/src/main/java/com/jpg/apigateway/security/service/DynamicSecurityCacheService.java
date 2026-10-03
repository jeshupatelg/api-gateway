package com.jpg.apigateway.security.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory thread-safe cache for Gateway public paths and role-path access rules.
 * Refreshed dynamically from DB upon CRUD updates and import operations.
 */
@Service
public class DynamicSecurityCacheService {

    private static final Logger log = LoggerFactory.getLogger(DynamicSecurityCacheService.class);

    private final List<String> publicPaths = new CopyOnWriteArrayList<>();
    private final Map<String, List<String>> rolePathAccess = new ConcurrentHashMap<>();

    public DynamicSecurityCacheService() {
    }

    public List<String> getPublicPaths() {
        return List.copyOf(publicPaths);
    }

    public void setPublicPaths(Collection<String> paths) {
        publicPaths.clear();
        if (paths != null) {
            publicPaths.addAll(paths);
        }
        log.info("Updated dynamic public paths: {}", publicPaths);
    }

    public Map<String, List<String>> getRolePathAccess() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        rolePathAccess.forEach((k, v) -> copy.put(k, new ArrayList<>(v)));
        return Collections.unmodifiableMap(copy);
    }

    public void setRolePathAccess(Map<String, List<String>> map) {
        rolePathAccess.clear();
        if (map != null) {
            map.forEach((role, paths) -> {
                if (role != null && paths != null) {
                    rolePathAccess.put(role.toLowerCase(Locale.ROOT), new ArrayList<>(paths));
                }
            });
        }
        log.info("Updated dynamic role-path access: {}", rolePathAccess);
    }
}
