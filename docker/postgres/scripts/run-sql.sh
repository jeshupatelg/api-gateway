#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Source .env if it exists in the postgres folder (which is SCRIPT_DIR/..)
if [ -f "${SCRIPT_DIR}/../.env" ]; then
    echo "Sourcing environment from ${SCRIPT_DIR}/../.env"
    export "$(cat "${SCRIPT_DIR}/../.env" | grep -v '^#' | xargs)"
fi

DB_USER="${POSTGRES_USER}"
DB_NAME="${POSTGRES_DB}"

SQL_DIR="${SCRIPT_DIR}/../sql"

echo "Executing SQL scripts from ${SQL_DIR} on container homeserver-pg..."

if [ -d "${SQL_DIR}" ] && ls "${SQL_DIR}"/*.sql >/dev/null 2>&1; then
    for sql_file in $(ls "${SQL_DIR}"/*.sql | sort); do
        echo "--------------------------------------------------"
        echo "Processing ${sql_file}..."
        
        # Perform variable substitution and pipe to psql
        sed -e "s/__KEYCLOAK_DB_USER__/${KEYCLOAK_DB_USER}/g" \
            -e "s/__KEYCLOAK_DB_PASSWORD__/${KEYCLOAK_DB_PASSWORD}/g" \
            "${sql_file}" | docker exec -i homeserver-pg psql -U "${DB_USER}" -d "${DB_NAME}"
            
        echo "Successfully executed ${sql_file}"
    done
else
    echo "No SQL scripts found in ${SQL_DIR}."
fi

echo "All SQL scripts executed successfully!"
