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

Foundry now has a React + TypeScript dashboard and a Spring Boot API. The dashboard manages projects, deployment records, status, and persisted logs.

**Working vertical slices:**
- Project registration/list/retrieval and deployment create/list/retrieval APIs.
- Guarded deployment lifecycle with persisted timestamps, failure reason, and application URL.
- Cursor-paginated deployment logs with ordered sequence numbers, bounded messages, and captured sample-process stdout/stderr.
- An opt-in worker and local runtime adapter that can launch a prebuilt trusted sample JAR and verify its health endpoint.
- A React + TypeScript + Vite dashboard in `apps/web` for project registration, deployment requests/history, log viewing, and application URLs.
- An opt-in **repository build pipeline** on `feat/repository-build-pipeline`: an explicit GitHub repository allowlist, checkout of a source ref, detection of Maven/npm manifests, real build/test command execution, persisted stdout/stderr/exit-code logs, and local launch for supported Spring Boot JARs and npm preview/start scripts.
- Frontend dependency lockfile and Vite proxy for local development.

**Repository build configuration is disabled by default:**
- `foundry.build.enabled=false`
- `foundry.build.allowed-repositories=`
- `foundry.worker.enabled=false`
- `foundry.runtime.local.enabled=false`
- `foundry.runtime.local.sample-mode-enabled=false`

The build pipeline only executes repositories explicitly listed in `foundry.build.allowed-repositories`. For local testing, temporarily enable the worker, local runtime, and build pipeline and add the exact HTTPS GitHub repository URL to the allowlist. The worker checks out the deployment's ref, selects a single detected service or requires a service path when multiple manifests are found, runs Maven tests/package or npm install/test/build, launches a supported artifact, and marks success only after a health check passes.

Supported first slice:
- Maven services producing an executable Spring Boot JAR.
- npm services with a `package-lock.json` and a `preview` or `start` script; `test` and `build` scripts run when configured.
- One service directory per deployment, selected via the optional service path. If several candidate directories are present, Foundry logs the candidates instead of guessing.

**Not a production platform yet:** the repository build branch still requires local compilation and end-to-end verification. Builds execute repository code; an allowlist is a development guardrail, not a sandbox. Do not expose this command runner publicly or allow untrusted repositories until isolated build/runtime workers, credentials handling, resource limits, and access controls exist. The local application URL is loopback-only.

## Build principles

- Build one working vertical slice before adding complexity.
- Explain and understand each infrastructure concept before automating it.
- Use AI to review and accelerate implementation, not to replace engineering understanding.
- Keep the control plane separate from the component that executes deployments.
- Do not run arbitrary untrusted workloads on the control-plane host. Isolation and access control are prerequisites before offering public deployments.
- Add technologies only when they solve a demonstrated problem.
- Track terminology, system-design decisions, experiments, and interview-relevant principles in the [learning log](docs/learning-log.md).

## Next milestone

Verify the repository build pipeline locally against a trusted allowlisted repository: Maven test/package or npm ci/test/build, persisted command output, supported artifact startup, and a passing health check. Then move build execution into an isolated runtime before any public or arbitrary repository support.


Track the V1 scope and progress in [Issue #1: Define Foundry v1 scope and architecture](https://github.com/krish-italiya/Foundry/issues/1).

## License

MIT. See [LICENSE](LICENSE).
