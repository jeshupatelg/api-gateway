package com.jpg.apigateway.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpg.apigateway.domain.GatewayRouteEntity;
import com.jpg.apigateway.model.FilterDefinitionDto;
import com.jpg.apigateway.model.PredicateDefinitionDto;
import com.jpg.apigateway.model.RouteDefinitionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GatewayRouteMapperTest {

    private GatewayRouteMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new GatewayRouteMapper(new ObjectMapper());
    }

    @Test
    void testDtoToRouteDefinitionAndBack() {
        RouteDefinitionDto dto = new RouteDefinitionDto();
        dto.setId("test-route");
        dto.setUri("http://localhost:8080");
        dto.setOrder(5);
        dto.setEnabled(true);
        dto.setMetadata(Map.of("customKey", "customValue"));

        PredicateDefinitionDto pred = new PredicateDefinitionDto();
        pred.setName("Path");
        pred.setArgs(Map.of("pattern", "/api/**"));
        dto.setPredicates(List.of(pred));

        FilterDefinitionDto filt = new FilterDefinitionDto();
        filt.setName("StripPrefix");
        filt.setArgs(Map.of("parts", "1"));
        dto.setFilters(List.of(filt));

        RouteDefinition rd = mapper.toRouteDefinition(dto);
        assertNotNull(rd);
        assertEquals("test-route", rd.getId());
        assertEquals(URI.create("http://localhost:8080"), rd.getUri());
        assertEquals(5, rd.getOrder());
        assertTrue((Boolean) rd.getMetadata().get("enabled"));
        assertEquals("customValue", rd.getMetadata().get("customKey"));
        assertEquals(1, rd.getPredicates().size());
        assertEquals(1, rd.getFilters().size());

        RouteDefinitionDto convertedBack = mapper.toDto(rd);
        assertNotNull(convertedBack);
        assertEquals("test-route", convertedBack.getId());
        assertEquals("http://localhost:8080", convertedBack.getUri());
        assertEquals(5, convertedBack.getOrder());
        assertTrue(convertedBack.getEnabled());
        assertEquals("customValue", convertedBack.getMetadata().get("customKey"));
    }

    @Test
    void testEntityToRouteDefinitionAndBack() {
        GatewayRouteEntity entity = new GatewayRouteEntity();
        entity.setId("entity-route");
        entity.setUri("http://service-b:8080");
        entity.setRouteOrder(10);
        entity.setEnabled(false);
        entity.setVersion(3L);
        Instant now = Instant.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setPredicatesJson("[{\"name\":\"Path\",\"args\":{\"pattern\":\"/b/**\"}}]");
        entity.setFiltersJson("[]");
        entity.setMetadataJson("{\"rateLimit\":100}");

        RouteDefinition rd = mapper.toRouteDefinition(entity);
        assertNotNull(rd);
        assertEquals("entity-route", rd.getId());
        assertEquals(URI.create("http://service-b:8080"), rd.getUri());
        assertEquals(10, rd.getOrder());
        assertFalse((Boolean) rd.getMetadata().get("enabled"));
        assertEquals(3L, rd.getMetadata().get("version"));
        assertEquals(now, rd.getMetadata().get("createdAt"));
        assertEquals(100, rd.getMetadata().get("rateLimit"));

        GatewayRouteEntity backEntity = mapper.toEntity(rd);
        assertNotNull(backEntity);
        assertEquals("entity-route", backEntity.getId());
        assertEquals("http://service-b:8080", backEntity.getUri());
        assertEquals(10, backEntity.getRouteOrder());
        assertFalse(backEntity.getEnabled());
        assertEquals(3L, backEntity.getVersion());
        assertEquals(now, backEntity.getCreatedAt());
        assertTrue(backEntity.getPredicatesJson().contains("Path"));
    }
}
