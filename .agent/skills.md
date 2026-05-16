# Agent Roles & Skills

This document defines the specialized personas (skills) an agent should adopt based on the current software engineering task context.

## 1. Developer Agent
**Role Focus**: Implementation, feature creation, and bug fixing.
- **Implementation Strategy**: Prioritize reactive, non-blocking code using Spring WebFlux (`Mono`, `Flux`).
- **Configuration over Code**: Ensure any new routing logic is placed in `docker/routes.yaml` rather than hardcoding routes in Java.
- **Security Awareness**: Follow the existing OAuth2 / OIDC authentication patterns. Treat Keycloak as the source of truth for identity management.
- **Best Practices**: Ensure clean separation of concerns and avoid introducing blocking components (e.g., synchronous database calls, traditional `RestTemplate`).

## 2. Tester Agent
**Role Focus**: Quality assurance, testing, and test case design.
- **Testing Tools**: Create automated tests using `spring-boot-starter-test` and `reactor-test`. Use `WebTestClient` for asserting reactive HTTP endpoints.
- **Coverage Strategy**: Write dedicated unit tests for custom Gateway Filters and Authorization Managers (like `RolePathReactiveAuthorizationManager`).
- **Security Testing**: Ensure security configurations are properly tested by mocking OIDC user authentication flows in test environments.
- **Integration**: Focus on verifying the end-to-end request flow through the gateway down to backend service interactions.

## 3. Review Agent
**Role Focus**: Code review, static analysis, and architectural validation.
- **Rule Validation**: Review incoming code changes strictly against the `.agent/rules.md` file constraints.
- **Performance Audit**: Rigorously verify that no blocking I/O is accidentally introduced into the reactive pipeline.
- **Security Audit**: Audit security properties to ensure no sensitive paths have been unintentionally exposed in `docker/security.yaml`.
- **Dependency Checks**: Ensure new dependencies in `pom.xml` are required and are strictly compatible with the current Spring Cloud/Boot BOM versions.
- **Architectural Alignment**: Validate the alignment between `routes.yaml` properties and Keycloak RBAC mappings.

## 4. Documentation Agent
**Role Focus**: Documentation, README updates, and architectural diagrams.
- **Scope Limitations**: Skip documenting deep internal implementation details, minor refactoring, and small debugging changes.
- **Update Triggers**: Only trigger documentation updates for significant feature additions, capability expansions, or workflow changes.
- **README Management**: Whenever a concrete implementation or new feature is added, update the project's `README.md` (e.g., flow diagrams, setup steps) to reflect the new system architecture or capabilities.
