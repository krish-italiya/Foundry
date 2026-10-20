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

The code is on GitHub main. The latest verified vertical slice uses a **trusted, prebuilt JAR** to exercise the deployment workflow end to end.

**Implemented and verified:**
- Spring Boot 4.1.1 API using Oracle JDK 25, with system and project registration/list/retrieval endpoints.
- Deployment creation, project deployment history, and deployment retrieval through the project-scoped deployment API.
- Deployment state machine: QUEUED → BUILDING → STARTING → HEALTH_CHECK → SUCCEEDED, with a failure path from nonterminal states.
- Persisted deployment logs and cursor-based reads. Pages are bounded to 1–100 entries and entries have per-deployment sequence numbers.
- An opt-in worker/poller that records progress and delegates execution through a RuntimeAdapter.
- A local process runtime adapter that launches the prebuilt trusted sample JAR, binds its application to loopback, captures standard output/error, probes /health, and only reports success after an HTTP 200 response containing the status UP marker.
- The deployment response persists the resulting applicationUrl; a live request returned status SUCCEEDED, a populated URL, and completion timestamps.
- Sample application source and Maven build file at apps/samples/hello-service.
- IntelliJ HTTP Client requests in apps/api/foundry-api/requests/deployments.http.
- The full Maven clean test command completed successfully during this check-in session.

**Safe local defaults:** foundry.worker.enabled=false and foundry.runtime.local.enabled=false. Enable them explicitly for the trusted local demonstration. The configured sample JAR path is repository-root-relative, so run the API with the repository root as its working directory or override the property for your launch configuration.

**Not implemented yet:** arbitrary repository checkout/build, image creation, Docker execution, the React dashboard, authentication/authorization, multi-tenant isolation, robust crash recovery, retries, and production log retention/redaction.

The current local process adapter proves lifecycle orchestration and health checking; it is **not a sandbox** and must not be exposed as a service that executes untrusted code. The intended Docker runtime remains a later, separately isolated implementation.

## Build principles

- Build one working vertical slice before adding complexity.
- Explain and understand each infrastructure concept before automating it.
- Use AI to review and accelerate implementation, not to replace engineering understanding.
- Keep the control plane separate from the component that executes deployments.
- Do not run arbitrary untrusted workloads on the control-plane host. Isolation and access control are prerequisites before offering public deployments.
- Add technologies only when they solve a demonstrated problem.
- Track terminology, system-design decisions, experiments, and interview-relevant principles in the [learning log](docs/learning-log.md).

## Next milestone

Harden and test the trusted local-runtime lifecycle, including failure cleanup and process termination. Then evaluate the first isolated Docker runtime. Do not start arbitrary repositories or expose workloads publicly before isolation, access control, and resource limits are designed.


Track the V1 scope and progress in [Issue #1: Define Foundry v1 scope and architecture](https://github.com/krish-italiya/Foundry/issues/1).

## License

MIT. See [LICENSE](LICENSE).
