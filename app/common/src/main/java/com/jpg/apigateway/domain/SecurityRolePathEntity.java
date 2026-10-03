package com.jpg.apigateway.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("security_role_paths")
public class SecurityRolePathEntity {

    @Id
    private Long id;

    @Column("role_name")
    private String roleName;

    @Column("path_pattern")
    private String pathPattern;

    @Column("created_at")
    private Instant createdAt;

    public SecurityRolePathEntity() {
    }

    public SecurityRolePathEntity(String roleName, String pathPattern) {
        this.roleName = roleName;
        this.pathPattern = pathPattern;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRoleName() {
        return roleName;
    }

    public void setRoleName(String roleName) {
        this.roleName = roleName;
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
