# Foundry

**Foundry turns a source repository into a deployed, observable, and manageable application.**

Foundry is an educational, production-inspired Internal Developer Platform (IDP). Its purpose is to hide repetitive deployment work behind a consistent developer workflow while keeping the underlying infrastructure understandable and controllable.

The project is **not tied to a single cloud provider**. The first deployment target will be a controlled Docker runtime. Cloud Run, Kubernetes, and other runtimes can be added later behind provider-specific adapters.

## The intended developer experience

1. Register a trusted application repository.
2. Request a deployment.
3. Follow build and deployment status.
4. Inspect logs and health checks.
5. Review previous deployments and eventually roll back.

## Planned architecture

```text
React + TypeScript dashboard
            |
            v
Spring Boot control-plane API ---- PostgreSQL (target)
            |
            v
Deployment job / worker
            |
            v
Runtime adapter
            |
            v
Docker host (first target)
            |
            v
Deployed application + health check + logs
```

This is the target architecture, not a claim that these components are already implemented. Initial local development currently uses H2 instead of PostgreSQL.

## Initial technology choices

- **Frontend:** React + TypeScript
- **Control plane:** Java + Spring Boot
- **Metadata:** H2 for current local development; PostgreSQL is the planned durable database
- **First runtime:** Docker
- **Source control and CI:** GitHub and GitHub Actions
- **Future runtime targets:** Cloud Run and Kubernetes, added only when the core workflow justifies them

## Current implementation

**Working locally:**
- Spring Boot 4.1.1 on Oracle JDK 25.
- `GET /api/v1/system` returns the service name, status, and version.
- `POST /api/v1/projects` validates a registration request, stores the project, and returns `201 Created` with a `Location` header.
- `GET /api/v1/projects` lists registered projects.
- `GET /api/v1/projects/{id}` retrieves a project or returns `404 Not Found`.
- Blank project names are rejected with `400 Bad Request`.
- Maven tests pass, including integration tests for persistence, validation, listing, and not-found behavior.
- `requests/projects.http` provides runnable IntelliJ HTTP Client requests and response assertions.

**Not implemented yet:** deployment records, the deployment worker, runtime adapters, the React dashboard, CI workflows, and application log streaming.

## Build principles

- Build one working vertical slice before adding complexity.
- Explain and understand each infrastructure concept before automating it.
- Use AI to review and accelerate implementation, not to replace engineering understanding.
- Keep the control plane separate from the component that executes deployments.
- Do not run arbitrary untrusted workloads on the control-plane host. Isolation and access control are prerequisites before offering public deployments.
- Add technologies only when they solve a demonstrated problem.
- Track terminology, system-design decisions, experiments, and interview-relevant principles in the [learning log](docs/learning-log.md).

## Next milestone

Model a deployment as a persisted resource with an explicit lifecycle. Define valid states and transitions before implementing the entity, API, and tests. Do not start a deployment worker until the deployment lifecycle is clear.

Track the V1 scope and progress in [Issue #1: Define Foundry v1 scope and architecture](https://github.com/krish-italiya/Foundry/issues/1).

## License

MIT. See [LICENSE](LICENSE).
