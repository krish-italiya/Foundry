# Foundry — Initial Architecture

Status: **partially implemented**. Project registration and a deployment-record API have been implemented and verified locally. The deployment source files, integration tests, and HTTP request collection are confirmed present on GitHub `main`; log persistence and runtime execution remain planned.

## Product boundary

Foundry is the **control plane** that coordinates deployments. A **runtime** is where the user's application actually executes. These are separate responsibilities.

- **Control plane:** accepts requests, validates configuration, records state, and presents deployment progress.
- **Deployment worker:** performs a deployment job and reports progress.
- **Runtime adapter:** gives Foundry a consistent interface for operations on a particular runtime.
- **Application runtime:** runs the user's application container.

## Implemented so far

- Spring Boot 4.1.1 API using Oracle JDK 25.
- System endpoint: `GET /api/v1/system`.
- Project registration: `POST /api/v1/projects`, with request validation, generated UUID, creation timestamp, persistence, and a `201 Created` response with a `Location` header.
- Project listing and retrieval: `GET /api/v1/projects` and `GET /api/v1/projects/{id}`.
- Integration tests for successful registration/persistence, blank-name rejection, project listing, and missing IDs.
- Deployment records: `POST /api/v1/projects/{projectId}/deployments`, `GET /api/v1/projects/{projectId}/deployments`, and `GET /api/v1/deployments/{deploymentId}`.
- The deployment request records the source repository URL and requested source ref, starts at `QUEUED`, and captures lifecycle timestamps/failure details as the state machine progresses.
- Deployment state-machine and controller integration tests; local Maven run reports 19 tests, 0 failures, and 0 errors.
- Runnable IntelliJ HTTP Client requests in `apps/api/foundry-api/requests/projects.http` and `apps/api/foundry-api/requests/deployments.http`. The user confirmed the deployment request flow against the live local API.
- H2 is the current local development database; PostgreSQL remains the planned durable database.
- Deployment source files and tests have been confirmed present on GitHub `main`.

## First end-to-end deployment slice (current boundary)

```text
Developer
   |
   | request deployment
   v
React dashboard (planned)
   |
   v
Spring Boot API
   |
   +------ H2 now / PostgreSQL later
   |
   | persist QUEUED deployment record (implemented)
   v
Deployment worker (planned)
   |    |  ---- build/deployment log events (planned)
   v
Runtime adapter (planned)
   |
   v
Docker runtime (first target, planned)
   |    |  ---- application stdout/stderr logs (planned)
   |
   | health check
   v
Deployment state + log view in UI (planned)
```

Initially, the worker may run in the same development environment as the API to keep the slice small. The code boundary should still be clear so we can move the worker to a separate process when asynchronous execution and recovery become necessary.

## Deployment resource and lifecycle (state model implemented locally)

A **Deployment** represents one attempt to deploy a particular project from a particular source revision/configuration.

Candidate fields:
- `id`: generated UUID
- `project_id`: the owning project
- `source_repository_url`: repository URL captured when the deployment is requested
- `source_ref`: requested branch or tag; record the resolved Git commit SHA once checkout occurs
- `status`: current lifecycle state
- `created_at`, `started_at`, `completed_at`: lifecycle timestamps
- `failure_reason`: concise, sanitized failure summary

Implemented state transitions:
- `QUEUED` → `BUILDING` → `STARTING` → `HEALTH_CHECK` → `SUCCEEDED`
- Any nonterminal state may transition to `FAILED` using a failure reason.
- `SUCCEEDED` and `FAILED` are terminal. A new deployment request creates a new record.
- The API currently creates records only in `QUEUED`; no worker advances them yet.

A deployment is **not** successful merely because the build passed or the container process started. It counts as successful only after the configured health check passes.

## Logging design (proposed, not implemented)

Foundry needs two related but distinct log streams:

1. **Build/deployment logs:** source checkout, build command output, image creation, container startup, health-check attempts, and worker errors. These exist before an application is running.
2. **Application runtime logs:** the deployed process's standard output and standard error after startup. These can continue long after deployment is marked successful.

Do not store an unlimited combined log blob on the Deployment row. Keep deployment state/metadata separate from append-only log entries so logs can be fetched incrementally and retained independently.

Initial learning/MVP model:
- `DeploymentLogEntry` linked to `deployment_id`
- monotonically increasing per-deployment `sequence_number` to fetch entries after a known cursor
- timestamp, stage, stream/severity, and message
- separate log entries for build/deployment output versus application runtime output
- bound message size and define a retention/cleanup policy
- redact credentials and secrets; never intentionally write tokens, secret environment values, or private keys to logs

For the first local prototype, storing log entries in H2 is acceptable. This is a learning implementation, not the final high-volume logging architecture. Later, a dedicated log sink such as a managed logging service or log backend can store/search high-volume runtime logs without pushing unbounded data into the relational metadata database.

For the first UI version, poll a logs endpoint using `afterSequence` to retrieve only new entries. This is straightforward to understand and reconnect. Consider Server-Sent Events (SSE) when live streaming is needed; do not add WebSockets unless we need bidirectional real-time communication.

Deployment status answers **what stage is the deployment in?** Logs answer **what happened inside that stage?** Keep both; neither replaces the other.

## System-design principles to apply when needed

- **Explicit state machine:** define allowed deployment states and transitions so impossible state changes are rejected.
- **Persist before doing long work:** record a deployment request before processing so users can see its state and later recover from failures.
- **Control plane vs. data plane/runtime:** management APIs coordinate work; the runtime executes the user's application.
- **Idempotency and retries:** repeated requests or worker retries must not accidentally create duplicate deployments or containers. Add when asynchronous job processing is implemented.
- **Observability:** preserve status, timestamps, sanitized failure summaries, logs, and health-check results so failures can be diagnosed.
- **Separate metadata from log volume:** deployment records are mutable state; logs are append-only and can be high-volume. Give them separate persistence and retention strategies.
- **API contracts:** use request/response DTOs to avoid exposing persistence entities as the public API.
- **Trust boundary:** executing user-supplied code is dangerous; a container alone is not a sufficient isolation boundary for a public multi-tenant platform.

## Runtime portability

Define a small runtime interface rather than spreading Docker-specific behavior across controllers and business logic. The initial adapter targets Docker. Future adapters may target Cloud Run or Kubernetes.

Portability is a design goal, not a promise that every runtime has identical behavior. Each adapter will need to document capability differences.

## Safety boundaries

For the first slice:
- deploy only trusted sample repositories;
- do not expose a public API that launches arbitrary code;
- do not mount the Docker socket into an untrusted application container;
- do not pass cloud credentials or secrets to deployed workloads by default;
- treat auth, tenant isolation, resource limits, and runtime isolation as blockers before public arbitrary deployments;
- do not expose raw build logs to untrusted users until secret redaction and access control are in place.

## Out of scope for the first slice

- Kubernetes, autoscaling, and multi-region deployment
- Multi-tenant arbitrary code execution
- Billing for platform users
- AI making deployment decisions autonomously
- Advanced traffic-splitting or blue/green deployments

Add these only after the base deploy/observe/recover lifecycle works.

## Decisions to revisit with evidence

1. When does the worker need to become a separate process?
2. Which queue semantics are required for retry, deduplication, and recovery?
3. How should application ports and generated local URLs be allocated safely?
4. Which minimum sandbox is required before accepting untrusted repositories?
5. What must be abstracted across runtimes, and what should remain runtime-specific?
6. When should log storage move from the local relational database to a dedicated log backend?
7. What retention, paging, redaction, and access-control rules are required for logs?
