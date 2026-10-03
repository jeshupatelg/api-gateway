package com.jpg.apigateway.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("security_public_paths")
public class SecurityPublicPathEntity {

    @Id
    private Long id;

    @Column("path_pattern")
    private String pathPattern;

    @Column("created_at")
    private Instant createdAt;

    public SecurityPublicPathEntity() {
    }

    public SecurityPublicPathEntity(String pathPattern) {
        this.pathPattern = pathPattern;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPathPattern() {
        return pathPattern;
    }

    public void setPathPattern(String pathPattern) {
        this.pathPattern = pathPattern;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
