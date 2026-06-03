# API Gateway - Agent Rules

When assisting with this project, an AI agent should adhere to the following guidelines:

## 1. Tech Stack & Architecture
- **Framework**: Spring Boot, Spring Cloud Gateway.
- **Paradigm**: Reactive programming using Project Reactor (`Mono`, `Flux`).
- **Non-blocking**: Do not use blocking code (e.g., `Thread.sleep()`, synchronous I/O, `RestTemplate`) in the reactive chain. Use `WebClient` for HTTP calls if needed.
- **Security**: OAuth2 / OIDC with Keycloak. The gateway acts as an OAuth2 client (`spring-boot-starter-oauth2-client`). Role-based path access is configured in `RolePathReactiveAuthorizationManager`.

## 2. Build Tool
- Always use the provided Maven wrapper (`./mvnw` on Linux/Mac, `.\mvnw.cmd` on Windows) rather than a globally installed `mvn` command. This ensures consistency across environments.

## 3. Configuration Management
- **Routing Rules**: Externalized to `docker/routes.yaml`.
- **Security Paths**: Public paths and role-based access control are externalized to `docker/security.yaml`.
- Do not hardcode routing or security path rules in Java code. Always use properties/yaml configuration.

## 4. Docker Environment
- The project is containerized using Docker Compose. All compose files are located in the `docker/` directory.
- Container networking uses an external network named `gateway_net`.

## 5. Code Style
- Follow standard Java conventions.
- Include proper Javadoc on classes and public methods, especially those related to security or routing filter logic.

## 6. Documentation Workflows
- **Trigger**: Every time a concrete implementation, feature, or workflow is added or significantly changed, the **Documentation Agent** persona must be invoked.
- **Action**: Add high-level details of the new feature/workflow to the `README.md` file (including diagrams if applicable).
- **Exclusions**: Skip documentation updates for small bug fixes, minor debugging changes, and overly deep technical details. Keep documentation focused on capabilities and architecture.
