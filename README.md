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
- Maven test suite passes locally: **19 tests, 0 failures, 0 errors**.
- `requests/projects.http` provides runnable IntelliJ HTTP Client requests and response assertions.
- Deployment record/lifecycle model and API implemented and pushed to GitHub `main`:
  - `POST /api/v1/projects/{projectId}/deployments` creates a `QUEUED` deployment record.
  - `GET /api/v1/projects/{projectId}/deployments` lists deployment history.
  - `GET /api/v1/deployments/{deploymentId}` retrieves a deployment.
- `requests/deployments.http` exercises project/deployment create, list, retrieve, invalid-source-ref, and log-read requests.
- Deployment-log persistence and cursor-based read API are present on GitHub `main`. Pages are bounded to 1–100 entries and ordered by per-deployment sequence number.
- The live logs endpoint was verified first with an empty page and later with three persisted worker-generated entries (sequences 1–3).
- The latest local Maven suite reports **26 tests, 0 failures, 0 errors, 0 skipped** after adding worker integration tests.
- A controlled, opt-in worker prototype is implemented locally: it claims a queued deployment, records diagnostic logs, then marks it `FAILED` when no runtime adapter is configured. It does not execute application code.
- **Sync status:** worker/poller source files, worker integration tests, and related application configuration still need to be pushed and confirmed on GitHub `main`.
- The deployment source files, integration tests, and HTTP request collection have been confirmed present on GitHub `main`.

**Not implemented yet:** a deployment worker that generates real logs, runtime adapters, the React dashboard, CI workflows, and live application execution/log collection.

## Build principles

- Build one working vertical slice before adding complexity.
- Explain and understand each infrastructure concept before automating it.
- Use AI to review and accelerate implementation, not to replace engineering understanding.
- Keep the control plane separate from the component that executes deployments.
- Do not run arbitrary untrusted workloads on the control-plane host. Isolation and access control are prerequisites before offering public deployments.
- Add technologies only when they solve a demonstrated problem.
- Track terminology, system-design decisions, experiments, and interview-relevant principles in the [learning log](docs/learning-log.md).

## Next milestone

Push and verify the controlled worker prototype. Then design and implement a constrained runtime adapter for a trusted sample application; do not report success until a real health check passes.

Track the V1 scope and progress in [Issue #1: Define Foundry v1 scope and architecture](https://github.com/krish-italiya/Foundry/issues/1).

## License

MIT. See [LICENSE](LICENSE).
