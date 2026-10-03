package com.jpg.apigateway.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpg.apigateway.domain.GatewayRouteEntity;
import com.jpg.apigateway.model.FilterDefinitionDto;
import com.jpg.apigateway.model.PredicateDefinitionDto;
import com.jpg.apigateway.model.RouteDefinitionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Component providing unified mapping and conversion between Spring Cloud Gateway {@link RouteDefinition},
 * OpenAPI generated {@link RouteDefinitionDto}, and database persistence entity {@link GatewayRouteEntity}.
 */
@Component
public class GatewayRouteMapper {

    private static final Logger log = LoggerFactory.getLogger(GatewayRouteMapper.class);

    public static final String METADATA_ENABLED = "enabled";
    public static final String METADATA_VERSION = "version";
    public static final String METADATA_CREATED_AT = "createdAt";
    public static final String METADATA_UPDATED_AT = "updatedAt";

    private final ObjectMapper objectMapper;

    /**
     * Constructs the GatewayRouteMapper.
     *
     * @param objectMapper JSON object mapper for JSON serialization and deserialization
     */
    public GatewayRouteMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Converts an OpenAPI {@link RouteDefinitionDto} into a Spring Cloud Gateway {@link RouteDefinition}.
     *
     * @param dto input route DTO
     * @return converted RouteDefinition
     */
    public RouteDefinition toRouteDefinition(RouteDefinitionDto dto) {
        if (dto == null) {
            return null;
        }
        RouteDefinition rd = new RouteDefinition();
        rd.setId(dto.getId());
        try {
            rd.setUri(new URI(dto.getUri()));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URI: " + dto.getUri(), e);
        }
        rd.setOrder(dto.getOrder() != null ? dto.getOrder() : 0);

        Map<String, Object> metadata = dto.getMetadata() != null ? new HashMap<>(dto.getMetadata()) : new HashMap<>();
        metadata.put(METADATA_ENABLED, !Boolean.FALSE.equals(dto.getEnabled()));
        rd.setMetadata(metadata);

        List<PredicateDefinition> predicates = new ArrayList<>();
        if (dto.getPredicates() != null) {
            for (PredicateDefinitionDto pd : dto.getPredicates()) {
                PredicateDefinition p = new PredicateDefinition();
                p.setName(pd.getName());
                if (pd.getArgs() != null) {
                    p.setArgs(new HashMap<>(pd.getArgs()));
                }
                predicates.add(p);
            }
        }
        rd.setPredicates(predicates);

        List<FilterDefinition> filters = new ArrayList<>();
        if (dto.getFilters() != null) {
            for (FilterDefinitionDto fd : dto.getFilters()) {
                FilterDefinition f = new FilterDefinition();
                f.setName(fd.getName());
                if (fd.getArgs() != null) {
                    f.setArgs(new HashMap<>(fd.getArgs()));
                }
                filters.add(f);
            }
        }
        rd.setFilters(filters);
        return rd;
    }

    /**
     * Converts a Spring Cloud Gateway {@link RouteDefinition} into an OpenAPI {@link RouteDefinitionDto}.
     *
     * @param rd spring cloud route definition
     * @return converted RouteDefinitionDto
     */
    public RouteDefinitionDto toDto(RouteDefinition rd) {
        if (rd == null) {
            return null;
        }
        RouteDefinitionDto dto = new RouteDefinitionDto();
        dto.setId(rd.getId());
        dto.setUri(rd.getUri() != null ? rd.getUri().toString() : "");
        dto.setOrder(rd.getOrder());

        Map<String, Object> metadata = rd.getMetadata() != null ? new HashMap<>(rd.getMetadata()) : new HashMap<>();
        Object enabledObj = metadata.remove(METADATA_ENABLED);
        boolean enabled = enabledObj == null || Boolean.TRUE.equals(enabledObj) || "true".equalsIgnoreCase(String.valueOf(enabledObj));
        dto.setEnabled(enabled);

        // Remove internal persistence metadata before exposing in DTO
        metadata.remove(METADATA_VERSION);
        metadata.remove(METADATA_CREATED_AT);
        metadata.remove(METADATA_UPDATED_AT);
        dto.setMetadata(metadata);

        List<PredicateDefinitionDto> predicates = new ArrayList<>();
        if (rd.getPredicates() != null) {
            for (PredicateDefinition p : rd.getPredicates()) {
                PredicateDefinitionDto pd = new PredicateDefinitionDto();
                pd.setName(p.getName());
                if (p.getArgs() != null) {
                    pd.setArgs(new HashMap<>(p.getArgs()));
                }
                predicates.add(pd);
            }
        }
        dto.setPredicates(predicates);

        List<FilterDefinitionDto> filters = new ArrayList<>();
        if (rd.getFilters() != null) {
            for (FilterDefinition f : rd.getFilters()) {
                FilterDefinitionDto fd = new FilterDefinitionDto();
                fd.setName(f.getName());
                if (f.getArgs() != null) {
                    fd.setArgs(new HashMap<>(f.getArgs()));
                }
                filters.add(fd);
            }
        }
        dto.setFilters(filters);
        return dto;
    }

    /**
     * Converts a database {@link GatewayRouteEntity} into a Spring Cloud Gateway {@link RouteDefinition}.
     *
     * @param entity database route entity
     * @return converted RouteDefinition
     */
    public RouteDefinition toRouteDefinition(GatewayRouteEntity entity) {
        if (entity == null) {
            return null;
        }
        RouteDefinition rd = new RouteDefinition();
        rd.setId(entity.getId());
        try {
            rd.setUri(new URI(entity.getUri()));
        } catch (Exception e) {
            log.error("Invalid URI for route {}: {}", entity.getId(), entity.getUri());
        }
        rd.setOrder(entity.getRouteOrder() != null ? entity.getRouteOrder() : 0);

        try {
            if (entity.getPredicatesJson() != null && !entity.getPredicatesJson().isBlank()) {
                List<PredicateDefinition> predicates = objectMapper.readValue(
                        entity.getPredicatesJson(), new TypeReference<List<PredicateDefinition>>() {});
                rd.setPredicates(predicates);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse predicates JSON for route {}", entity.getId(), e);
        }

        try {
            if (entity.getFiltersJson() != null && !entity.getFiltersJson().isBlank()) {
                List<FilterDefinition> filters = objectMapper.readValue(
                        entity.getFiltersJson(), new TypeReference<List<FilterDefinition>>() {});
                rd.setFilters(filters);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse filters JSON for route {}", entity.getId(), e);
        }

        Map<String, Object> metadata = new HashMap<>();
        try {
            if (entity.getMetadataJson() != null && !entity.getMetadataJson().isBlank()) {
                Map<String, Object> parsed = objectMapper.readValue(
                        entity.getMetadataJson(), new TypeReference<Map<String, Object>>() {});
                metadata.putAll(parsed);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to parse metadata JSON for route {}", entity.getId(), e);
        }

        metadata.put(METADATA_ENABLED, !Boolean.FALSE.equals(entity.getEnabled()));
        if (entity.getVersion() != null) {
            metadata.put(METADATA_VERSION, entity.getVersion());
        }
        if (entity.getCreatedAt() != null) {
            metadata.put(METADATA_CREATED_AT, entity.getCreatedAt());
        }
        if (entity.getUpdatedAt() != null) {
            metadata.put(METADATA_UPDATED_AT, entity.getUpdatedAt());
        }
        rd.setMetadata(metadata);

        return rd;
    }

    /**
     * Converts a Spring Cloud Gateway {@link RouteDefinition} into a database {@link GatewayRouteEntity}.
     *
     * @param rd spring cloud route definition
     * @return converted GatewayRouteEntity
     */
    public GatewayRouteEntity toEntity(RouteDefinition rd) {
        if (rd == null) {
            return null;
        }
        GatewayRouteEntity entity = new GatewayRouteEntity();
        entity.setId(rd.getId());
        entity.setUri(rd.getUri() != null ? rd.getUri().toString() : "");
        entity.setRouteOrder(rd.getOrder());

        Map<String, Object> metadata = rd.getMetadata() != null ? new HashMap<>(rd.getMetadata()) : new HashMap<>();
        Object enabledObj = metadata.remove(METADATA_ENABLED);
        entity.setEnabled(enabledObj == null || Boolean.TRUE.equals(enabledObj) || "true".equalsIgnoreCase(String.valueOf(enabledObj)));

        Object versionObj = metadata.remove(METADATA_VERSION);
        if (versionObj instanceof Number n) {
            entity.setVersion(n.longValue());
        }

        Object createdObj = metadata.remove(METADATA_CREATED_AT);
        if (createdObj instanceof Instant i) {
            entity.setCreatedAt(i);
        } else if (createdObj instanceof String s) {
            try {
                entity.setCreatedAt(Instant.parse(s));
            } catch (Exception ignored) {}
        }

        Object updatedObj = metadata.remove(METADATA_UPDATED_AT);
        if (updatedObj instanceof Instant i) {
            entity.setUpdatedAt(i);
        } else if (updatedObj instanceof String s) {
            try {
                entity.setUpdatedAt(Instant.parse(s));
            } catch (Exception ignored) {}
        }

        try {
            entity.setPredicatesJson(objectMapper.writeValueAsString(
                    rd.getPredicates() != null ? rd.getPredicates() : List.of()));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize predicates for route {}", rd.getId(), e);
            entity.setPredicatesJson("[]");
        }

        try {
            entity.setFiltersJson(objectMapper.writeValueAsString(
                    rd.getFilters() != null ? rd.getFilters() : List.of()));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize filters for route {}", rd.getId(), e);
            entity.setFiltersJson("[]");
        }

        try {
            if (!metadata.isEmpty()) {
                entity.setMetadataJson(objectMapper.writeValueAsString(metadata));
            } else {
                entity.setMetadataJson("{}");
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize metadata for route {}", rd.getId(), e);
            entity.setMetadataJson("{}");
        }

        return entity;
    }

    /**
     * Converts an OpenAPI {@link RouteDefinitionDto} into a database {@link GatewayRouteEntity}.
     *
     * @param dto input route DTO
     * @return converted GatewayRouteEntity
     */
    public GatewayRouteEntity toEntity(RouteDefinitionDto dto) {
        return toEntity(toRouteDefinition(dto));
    }

    /**
     * Converts a database {@link GatewayRouteEntity} into an OpenAPI {@link RouteDefinitionDto}.
     *
     * @param entity database route entity
     * @return converted RouteDefinitionDto
     */
    public RouteDefinitionDto toDto(GatewayRouteEntity entity) {
        return toDto(toRouteDefinition(entity));
    }
}
