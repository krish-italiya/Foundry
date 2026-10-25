# Foundry Architecture — Repository-to-Deployment Pipeline

Status: **local product prototype; repository builds, project-level trust, local subdomain routing, and runtime lifecycle reconciliation have been verified on Windows. The runtime monitor detects an exited application as `CRASHED` and a previously successful deployment no longer tracked after a Foundry API restart as `INTERRUPTED`.**

## Current path

```text
React + TypeScript dashboard
            |
            | create project / request deployment
            v
Spring Boot control-plane API
            |
            +---- file-backed H2 metadata: projects, build trust, deployments, ordered logs
            |
            v
Opt-in deployment worker
            |
            v
RepositoryBuildService (opt-in)
            |
            +---- per-project build trust (explicit opt-in) or administrator allowlist override
            +---- git clone of requested ref into a disposable workspace
            +---- find pom.xml / package.json, with service-path selection
            +---- Maven verify OR npm ci (lockfile) / npm install (warned fallback), with configured test/build scripts
            +---- capture stdout/stderr and exit status into deployment logs
            |
            v
BuiltArtifact
            |
            v
LocalProcessRuntimeAdapter
            |
            +---- executable Spring Boot JAR OR npm preview/start
            +---- private 127.0.0.1:ephemeral-port process
            +---- HTTP health check against the private target
            |
            +---- local ingress filter:
                   {deployment-id}.localhost:8080
                   -> active deployment lookup
                   -> reverse proxy to private loopback target
            |
            v
Persist SUCCEEDED only after health check passes
```

This pipeline must stay disabled by default. A trusted-repository allowlist limits which source can be checked out but does **not** sandbox build scripts. Do not expose it to the public or run untrusted repositories until execution is moved into an appropriately isolated worker/runtime with resource limits and credential boundaries.

## Existing control-plane APIs

- `GET /api/v1/system`
- `POST /api/v1/projects`, `GET /api/v1/projects`, `GET /api/v1/projects/{id}`
- `PATCH /api/v1/projects/{id}/build-trust` to explicitly enable or revoke local repository build trust
- `POST /api/v1/projects/{projectId}/deployments`
- `GET /api/v1/projects/{projectId}/deployments`
- `GET /api/v1/deployments/{deploymentId}`
- `GET /api/v1/deployments/{deploymentId}/logs?afterSequence=0&limit=50`

A deployment request stores a repository URL, source ref, and optional relative service path. Its state advances `QUEUED → BUILDING → STARTING → HEALTH_CHECK → SUCCEEDED`, with failure paths from nonterminal states. Log entries have a per-deployment sequence number and are returned using bounded cursor pagination.

## First build strategies

### Maven
- Detect `pom.xml`.
- Prefer `mvnw.cmd` / `mvnw` from the service directory or repository root; otherwise use installed Maven.
- Run `verify`; a non-zero exit code fails the deployment.
- Prefer an executable Spring Boot JAR; also supports a regular runnable Java JAR with a `Main-Class` manifest entry. A library JAR is rejected.

### npm
- Detect `package.json`.
- Prefer `npm ci` when `package-lock.json` or `npm-shrinkwrap.json` exists. Without a lockfile, emit a warning and fall back to `npm install`; this is less reproducible and should be corrected by adding a lockfile.
- Run `npm test` if a usable test script exists; otherwise record a warning and skip the test step.
- Run `npm run build` if configured.
- Launch via `npm run preview` when configured (and a `dist/` directory exists), or `npm run start` if preview is unavailable.
- A repository must be explicitly trusted in the project UI or present in the local administrator allowlist before build scripts execute.
- Capture stdout and stderr separately as deployment log entries, cap individual log messages, and impose per-command timeouts.

The optional service path identifies a nested service in a monorepo. If discovery finds multiple candidate directories and no path is selected, the worker records the candidates in the logs and fails explicitly rather than guessing. Directories with both `pom.xml` and `package.json` are unsupported in this initial slice; split services into separate directories or define a clearer build plan later.

## Safe defaults and local configuration

```properties
foundry.worker.enabled=false
foundry.runtime.local.enabled=false
foundry.runtime.local.sample-mode-enabled=false
foundry.build.enabled=false
foundry.build.workspace-root=.foundry/workspaces
foundry.build.allowed-repositories=
foundry.build.command-timeout-seconds=600
```

For a local demonstration, enable the worker, runtime, and build flag. Register the repository and explicitly enable its `Build trust` setting in the dashboard; this trust state is stored in a local file-backed H2 database and can be revoked. The exact HTTPS `foundry.build.allowed-repositories` setting remains an optional administrator override. Tests use an isolated in-memory H2 database. Keep sample-only mode disabled when testing real repository builds so a deployment cannot succeed by launching an unrelated prebuilt JAR.

The runtime binds the application process to `127.0.0.1` on an ephemeral port. The local ingress returns `http://{deployment-id}.localhost:8080` and proxies requests for that UUID host to the registered active process. This preserves paths, query strings, request headers, and request bodies for a useful local preview. Requests for unknown or stopped deployment hosts return 404. The `.localhost` URL resolves to loopback; it is reachable only from the same machine, not from the public Internet.

The deployment's initial `SUCCEEDED` status means its first health check passed; it is not an ongoing health guarantee. The local runtime monitor records a separate `runtimeState`: `RUNNING`, `CRASHED`, or `INTERRUPTED`. A process that exits while Foundry is running is marked `CRASHED`; a previously successful deployment with no process tracked after a Foundry restart is marked `INTERRUPTED`. Monitoring is detection-only and does not restart processes.

The ingress is deliberately a local-development seam, not production ingress. Runtime-to-deployment mappings are in memory and are removed when the process stops; API restart terminates workloads rather than recovering them. Request size/rate limits, durable process recovery, public TLS/DNS, authentication, and isolation still need design before hosting arbitrary projects on the Internet.

## System-design lessons and remaining boundaries

- **A build is not a deployment:** require a supported runnable artifact and a passing health check before persisting success.
- **Command output is telemetry:** stdout, stderr, exit code, status, and timestamps explain what happened; status alone is not enough.
- **Bound execution time:** commands have timeouts and processes are terminated when possible on timeout/interruption.
- **Repository selection is not trust:** registration records the source; project build trust is a separate, explicit consent gate. The administrator allowlist can override project trust for local development, but neither is a sandbox.
- **Do not pass user-controlled commands through shell-string concatenation.** Use fixed build strategies and validated repository/ref/service-path values.
- **Secrets are not implemented:** private-repository credentials, log redaction, and credential isolation must be designed before hosted use.
- **Production gaps:** no container sandbox, request/build resource limits, authenticated multi-tenancy, reliable queue recovery/retries, retention policy, public TLS/DNS ingress, automatic application restart, or durable runtime recovery yet.
- **Frontend deployment is separate:** the dashboard currently uses a local Vite proxy. A hosted frontend/backend arrangement will need an API origin, CORS/auth configuration, and durable database setup.

## Verified locally

- Windows API Maven suite passed: **54 tests, 0 failures, 0 errors, 0 skipped**.
- Frontend production build passed.
- Deployed `render-examples/express-hello-world` from GitHub through checkout, npm install, process startup, and health check. The app rendered through its UUID `.localhost:8080` URL.
- Stopping the verified `node app.js` process changed runtime state to `CRASHED` and recorded a runtime log.
- Restarting the Foundry API changed a previously successful deployment with no process tracked by the new API process to `INTERRUPTED` and recorded the reconciliation log.

## Next checkpoints

1. Merge the reviewed lifecycle-reconciliation change and synchronize the local branch.
2. Continue hardening local runtime behavior and cleanup, including stale URL/route checks after a process exits.
3. Design isolated runtime workers, resource limits, and public DNS/TLS ingress before attempting public hosting.
