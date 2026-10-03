CREATE TABLE IF NOT EXISTS gateway_routes (
    id VARCHAR(128) PRIMARY KEY,
    uri VARCHAR(512) NOT NULL,
    route_order INT DEFAULT 0,
    predicates_json TEXT NOT NULL,
    filters_json TEXT,
    enabled BOOLEAN DEFAULT TRUE NOT NULL,
    metadata_json TEXT,
    version BIGINT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS security_public_paths (
    id BIGSERIAL PRIMARY KEY,
    path_pattern VARCHAR(256) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS security_role_paths (
    id BIGSERIAL PRIMARY KEY,
    role_name VARCHAR(128) NOT NULL,
    path_pattern VARCHAR(256) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_role_path UNIQUE (role_name, path_pattern)
);
