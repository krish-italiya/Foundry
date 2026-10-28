# Foundry

**Foundry turns a source repository into a deployed, observable, and manageable application.**

Foundry is an educational, production-inspired Internal Developer Platform (IDP). Its purpose is to hide repetitive deployment work behind a consistent developer workflow while keeping the underlying infrastructure understandable and controllable.

The project is **not tied to a single cloud provider**. The intended first isolated runtime is Docker; Cloud Run, Kubernetes, and other runtimes can be added later behind provider-specific adapters.

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

Foundry currently has a React + TypeScript dashboard and a Spring Boot API. The dashboard manages projects, deployment records, status, and persisted logs.

**Implemented control-plane and local-runtime capabilities:**
- Project registration/list/retrieval and deployment create/list/retrieval APIs.
- Guarded deployment lifecycle with persisted timestamps, failure reason, optional service path, and application URL.
- Cursor-paginated deployment logs with ordered sequence numbers, bounded messages, and captured stdout/stderr.
- An opt-in worker and local-process runtime adapter for trusted local development.
- An opt-in Docker runtime adapter on `feat/docker-runtime-adapter`, with generated fixed Dockerfiles, loopback-only host port publishing, CPU/memory/PID limits, dropped Linux capabilities, non-root Java/Node images, health checks, container logs, and cleanup. A trusted Express service has successfully built and run through this adapter on Windows with Docker Desktop. The adapter now labels generated resources, reconciles orphan containers/images on startup, retries failed removals, and protects startup recovery from concurrent worker polling (new lifecycle changes are awaiting a fresh test run).
- React + TypeScript + Vite dashboard in `apps/web`, organized into domain types, an API client, a workspace state hook, reusable components, separate pages, and layered stylesheets. Navigation is project-centered: Overview → Projects → a selected project's Overview, Deployments, and Build & Runtime tabs.
- Deployment history is scoped to the selected project and includes text/status filters and eight-item pagination. A separate Ionic React/Vite frontend has also successfully built and served through Foundry's static Docker runtime path.

**Repository build pipeline (tested locally; current onboarding changes are on `feat/npm-lockfile-fallback` and still need a fresh verification run):**
- Checkout of an explicitly selected HTTPS GitHub repository/ref into a disposable workspace.
- Manifest discovery for Maven `pom.xml` and npm `package.json`; optional service path for monorepos.
- Maven `verify`; supports executable Spring Boot JARs and ordinary runnable JARs with a `Main-Class` manifest entry.
- npm prefers `npm ci` when `package-lock.json` or `npm-shrinkwrap.json` exists; otherwise it warns and falls back to `npm install`. Configured tests and builds run when available.
- Persisted command stdout/stderr and exit-code log entries with per-command timeouts.
- Launch of supported Java JARs or npm `preview`/`start` scripts, followed by a real health check.
- Per-project build trust is explicitly opt-in in the dashboard, stored in the local database, and can be revoked. The administrator allowlist remains an optional local override.
- A local ingress filter gives active deployments stable `http://{deployment-id}.localhost:8080` URLs and proxies requests to their private loopback ports. This is for local development, not public hosting.
- Runtime state is tracked separately from the initial deployment result. A local monitor identifies exited applications as `CRASHED` and successful records whose process is no longer tracked after a Foundry restart as `INTERRUPTED`. It detects failures but does not restart workloads.

**Safe defaults:** `foundry.build.enabled=false`, `foundry.build.allowed-repositories=`, `foundry.worker.enabled=false`, `foundry.runtime.local.enabled=false`, `foundry.runtime.docker.enabled=false`, and `foundry.runtime.local.sample-mode-enabled=false`. Enable these deliberately for a local demonstration. Registering a URL alone does not authorize running its scripts; trust it explicitly in the dashboard or use the local administrator allowlist.

**Important boundary:** repository builds execute source-repository scripts on the local host. Project trust is an explicit local consent/authorization step, not a sandbox. Do not expose this runner publicly or execute untrusted repositories until isolated build/runtime workers, credential boundaries, resource limits, authentication, and access controls exist. The local ingress uses `.localhost` subdomains and forwards to loopback-published local processes/containers; it is not Internet ingress. Process routing state is in memory, so restarting the API stops local workloads and their old URLs become unavailable.

## Build principles

- Build one working vertical slice before adding complexity.
- Explain and understand each infrastructure concept before automating it.
- Use AI to review and accelerate implementation, not to replace engineering understanding.
- Keep the control plane separate from the component that executes deployments.
- Do not run arbitrary untrusted workloads on the control-plane host. Isolation and access control are prerequisites before offering public deployments.
- Add technologies only when they solve a demonstrated problem.
- Track terminology, system-design decisions, experiments, and interview-relevant principles in the [learning log](docs/learning-log.md).

## Next milestone

The Docker runtime has successfully deployed a trusted Express service and a separate Ionic React/Vite frontend on Windows. The project-scoped dashboard build and manual navigation checks passed. The new lifecycle recovery changeset also passed Windows CI at commit `bca66469`: **70 backend tests, 0 failures, 0 errors, 0 skipped**, followed by `npm ci` and a successful Vite production build ([workflow run](https://github.com/krish-italiya/Foundry/actions/runs/40740356945)). The remaining acceptance check is a live Docker shutdown/restart and cleanup-retry exercise on the development machine. Runtime monitoring improves diagnosis but does not restart applications or sandbox builds. Do not expose workloads publicly before an isolated build boundary, credential controls, authentication/authorization, quotas, and public TLS/DNS ingress are implemented.


Track the V1 scope and progress in [Issue #1: Define Foundry v1 scope and architecture](https://github.com/krish-italiya/Foundry/issues/1).

## License

MIT. See [LICENSE](LICENSE).
