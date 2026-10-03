package com.jpg.apigateway.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Entity representing a Gateway Route definition stored in the database.
 * The primary key {@code id} directly represents the Spring Cloud Gateway route ID.
 */
@Table("gateway_routes")
public class GatewayRouteEntity {

    /**
     * Unique route identifier matching Spring Cloud Gateway's route ID and RouteDefinitionDto id.
     */
    @Id
    private String id;

    /**
     * Version field used for optimistic locking and to enable Spring Data R2DBC
     * to determine entity state (new vs existing) for assigned non-generated String primary keys.
     * When version is null, Spring Data R2DBC performs an SQL INSERT;
     * when version is non-null, it performs an SQL UPDATE with optimistic locking.
     */
    @Version
    @Column("version")
    private Long version;

    @Column("uri")
    private String uri;

    @Column("route_order")
    private Integer routeOrder;

    @Column("predicates_json")
    private String predicatesJson;

    @Column("filters_json")
    private String filtersJson;

    @Column("enabled")
    private Boolean enabled = true;

    @Column("metadata_json")
    private String metadataJson;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    public GatewayRouteEntity() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public Integer getRouteOrder() {
        return routeOrder;
    }

    public void setRouteOrder(Integer routeOrder) {
        this.routeOrder = routeOrder;
    }

    public String getPredicatesJson() {
        return predicatesJson;
    }

    public void setPredicatesJson(String predicatesJson) {
        this.predicatesJson = predicatesJson;
    }

    public String getFiltersJson() {
        return filtersJson;
    }

    public void setFiltersJson(String filtersJson) {
        this.filtersJson = filtersJson;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public void setMetadataJson(String metadataJson) {
        this.metadataJson = metadataJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
