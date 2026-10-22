# Foundry Architecture — Repository-to-Deployment Pipeline

Status: **local product prototype; repository build pipeline is implemented on `feat/repository-build-pipeline` and still needs local compile and end-to-end verification**.

## Current path

```text
React + TypeScript dashboard
            |
            | create project / request deployment
            v
Spring Boot control-plane API
            |
            +---- H2 metadata: projects, deployments, ordered logs
            |
            v
Opt-in deployment worker
            |
            v
RepositoryBuildService (opt-in)
            |
            +---- exact GitHub repository allowlist
            +---- git clone of requested ref into a disposable workspace
            +---- find pom.xml / package.json, with service-path selection
            +---- Maven tests + package OR npm ci + configured test/build scripts
            +---- capture stdout/stderr and exit status into deployment logs
            |
            v
BuiltArtifact
            |
            v
LocalProcessRuntimeAdapter
            |
            +---- executable Spring Boot JAR OR npm preview/start
            +---- loopback URL
            +---- HTTP health check
            |
            v
Persist SUCCEEDED only after health check passes
```

This pipeline must stay disabled by default. A trusted-repository allowlist limits which source can be checked out but does **not** sandbox build scripts. Do not expose it to the public or run untrusted repositories until execution is moved into an appropriately isolated worker/runtime with resource limits and credential boundaries.

## Existing control-plane APIs

- `GET /api/v1/system`
- `POST /api/v1/projects`, `GET /api/v1/projects`, `GET /api/v1/projects/{id}`
- `POST /api/v1/projects/{projectId}/deployments`
- `GET /api/v1/projects/{projectId}/deployments`
- `GET /api/v1/deployments/{deploymentId}`
- `GET /api/v1/deployments/{deploymentId}/logs?afterSequence=0&limit=50`

A deployment request stores a repository URL, source ref, and optional relative service path. Its state advances `QUEUED → BUILDING → STARTING → HEALTH_CHECK → SUCCEEDED`, with failure paths from nonterminal states. Log entries have a per-deployment sequence number and are returned using bounded cursor pagination.

## First build strategies

### Maven
- Detect `pom.xml`.
- Prefer `mvnw.cmd` / `mvnw` from the service directory or repository root; otherwise use installed Maven.
- Run `test package`; a non-zero exit code fails the deployment.
- The first launcher supports executable Spring Boot JARs. A generic library JAR or non-executable package is reported as unsupported rather than marked as deployed.

### npm
- Detect `package.json` and require `package-lock.json` for the first implementation.
- Run `npm ci`.
- Run `npm test` if a usable test script exists; otherwise record a warning and skip the test step.
- Run `npm run build` if configured.
- Launch via `npm run preview` when configured (and a `dist/` directory exists), or `npm run start` if preview is unavailable.
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

For a local demonstration, enable the worker, runtime, and build flag and set `foundry.build.allowed-repositories` to the exact HTTPS GitHub repository URL. Keep sample-only mode disabled when testing real repository builds so a deployment cannot succeed by launching an unrelated prebuilt JAR.

The local adapter runs the service bound to `127.0.0.1`. Its generated URL is reachable only from the same machine, not from the public Internet.

## System-design lessons and remaining boundaries

- **A build is not a deployment:** require a supported runnable artifact and a passing health check before persisting success.
- **Command output is telemetry:** stdout, stderr, exit code, status, and timestamps explain what happened; status alone is not enough.
- **Bound execution time:** commands have timeouts and processes are terminated when possible on timeout/interruption.
- **Repository selection is not trust:** an allowlist is an explicit development guardrail, not isolation.
- **Do not pass user-controlled commands through shell-string concatenation.** Use fixed build strategies and validated repository/ref/service-path values.
- **Secrets are not implemented:** private-repository credentials, log redaction, and credential isolation must be designed before hosted use.
- **Production gaps:** no container sandbox, resource limits, authenticated multi-tenancy, reliable queue recovery/retries, retention policy, or public application ingress yet.
- **Frontend deployment is separate:** the dashboard currently uses a local Vite proxy. A hosted frontend/backend arrangement will need an API origin, CORS/auth configuration, and durable database setup.

## Next checkpoints

1. Check out the build branch locally, run API `clean test` and frontend `npm run build`, and fix compile issues.
2. Run an allowlisted, trusted repository through the dashboard and verify logs for clone, detection, tests, build, startup, and health check.
3. Exercise a failed Maven/npm command and verify a clear failed state with actual output.
4. Only after local end-to-end proof, design isolated build workers and a publicly reachable runtime.
