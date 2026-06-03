-- 01-init-keycloak.sql
-- Create role/user if it doesn't exist
DO
$$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '__KEYCLOAK_DB_USER__') THEN
      CREATE ROLE __KEYCLOAK_DB_USER__ WITH LOGIN PASSWORD '__KEYCLOAK_DB_PASSWORD__';
   END IF;
END
$$;

-- Create database if it doesn't exist
SELECT 'CREATE DATABASE keycloak'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak')\gexec

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE keycloak TO __KEYCLOAK_DB_USER__;

-- Connect to keycloak database and grant schema ownership for keycloak schema creation
\c keycloak
ALTER SCHEMA public OWNER TO __KEYCLOAK_DB_USER__;

