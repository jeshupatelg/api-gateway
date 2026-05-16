# api-gateway

Spring Cloud Gateway service that forwards requests to internal services
based on route rules defined in a file on the server.

## Request Flow Architecture

```mermaid
graph TD
    Client([Public User]) -->|HTTPS Request| RP(Reverse Proxy)
    RP -->|Forward to Gateway Port| APIGW(Spring Cloud API Gateway)
    
    subgraph Docker Network [Gateway Docker Network]
        APIGW
        Keycloak(Keycloak IAM)
        Jenkins(Jenkins CI/CD)
    end
    
    %% Routing Flow
    APIGW -->|Route /keycloak/**| Keycloak
    APIGW -->|Route /jenkins/**| Jenkins
    
    %% Authentication Flow
    Jenkins -.->|Native Jenkins OIDC Auth| Keycloak
```

## Security Chain Flow

```mermaid
flowchart TD
    Req([Incoming Request]) --> PathMatch{Matches Public Path?<br/>e.g. /jenkins/**}
    
    PathMatch -->|Yes| PermitAll[Permit All - Bypass Gateway Auth]
    PermitAll --> Forward[Forward to Target Service]
    
    PathMatch -->|No| IsAuth{Is Authenticated?}
    
    IsAuth -->|No| Oauth2Login[OAuth2 Login Filter]
    Oauth2Login -.->|Redirect| Login(Keycloak Login Page)
    
    IsAuth -->|Yes| RolePathAuth[Role-Path Authorization]
    RolePathAuth --> CheckRoles{Does User Role<br/>match Path Pattern?}
    
    CheckRoles -->|Yes| AccessGranted[Access Granted]
    AccessGranted --> Forward
    
    CheckRoles -->|No| AccessDenied[403 Forbidden]
```

## Build

```bash
./mvnw clean package -DskipTests
```

On Windows PowerShell:

```powershell
.\mvnw.cmd clean package -DskipTests
```

## Configure Routing Rules

Gateway rules are loaded from `/config/routes.yaml` inside the container.
Mount a file from your server to this path.

Example route rules file:

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: users-service
          uri: http://users-service:8081
          predicates:
            - Path=/users/**
          filters:
            - StripPrefix=1
```

## Docker

From the `docker` directory:

```bash
docker compose up --build -d
```

This will:
- Build the image from the root project context.
- Expose gateway on port `8080`.
- Mount `docker/routes.yaml` to `/config/routes.yaml` in the container.
