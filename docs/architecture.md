# Foundry — Initial Architecture

Status: **first local deployment vertical slice verified; production runtime and public platform are not implemented**. The source and tests are on GitHub main.

## Product boundary

Foundry is a **control plane** that accepts deployment requests, records lifecycle state, and exposes status and logs. A **runtime** is where an application process executes. These responsibilities are separated behind a RuntimeAdapter interface.

- **API/control plane:** validates requests and records project/deployment metadata.
- **Deployment worker/poller:** claims queued deployment records and coordinates a deployment attempt. The current poller runs inside the API process and is disabled by default.
- **Runtime adapter:** provides a stable interface for starting, checking, and stopping a workload.
- **Application process:** the sample service launched by the current local adapter.

## Current vertical slice

```text
HTTP Client / API caller
          |
          v
     Spring Boot API
          |
          +------ H2 persistence
          |        projects, deployments, ordered log entries
          |
          v
     Opt-in worker/poller
          |
          v
       RuntimeAdapter
          |
          v
 LocalProcessRuntimeAdapter
          |
          | launches trusted, prebuilt JAR
          v
Sample Java process (127.0.0.1:ephemeral-port)
          |
          v
       GET /health
          |
          | HTTP 200 + status UP
          v
Deployment marked SUCCEEDED
applicationUrl and lifecycle timestamps persisted
```

This flow was verified locally and pushed to GitHub. The local adapter is a learning prototype, not a production runtime or security boundary.

## Implemented API and lifecycle

- System endpoint: `GET /api/v1/system`.
- Project registration/list/retrieval: `POST /api/v1/projects`, `GET /api/v1/projects`, and `GET /api/v1/projects/{id}`.
- Create a deployment under a project: `POST /api/v1/projects/{projectId}/deployments`.
- List project deployment history: `GET /api/v1/projects/{projectId}/deployments`.
- Read deployment details: `GET /api/v1/deployments/{deploymentId}`.
- Read persisted deployment logs: `GET /api/v1/deployments/{deploymentId}/logs?afterSequence=0&limit=50`.

A deployment follows `QUEUED → BUILDING → STARTING → HEALTH_CHECK → SUCCEEDED`. A failure from a nonterminal state records a failure reason. `SUCCEEDED` is only reached after the runtime adapter's health check passes. The response includes `applicationUrl` when successful.

The log API uses sequence-based cursor pagination and bounded pages (1–100 entries). Each entry records its deployment, sequence number, timestamp, stage, stream, severity, and bounded message. Deployment state and diagnostic log entries remain separate records.

## Local process adapter

The current implementation:
- accepts only the configured, prebuilt trusted sample JAR; it does not clone the source repository or build arbitrary projects;
- starts a separate Java process and allocates an available loopback port;
- captures the child process's standard output and standard error into the deployment log service;
- probes the application's `/health` endpoint until it passes or the configured timeout expires;
- returns the loopback application URL and allows the worker to persist success only after health validation;
- includes process-stop and shutdown handling in the adapter prototype.

The sample JAR is built from `apps/samples/hello-service`. The sample JAR path setting is repository-root-relative; the local API launch working directory must match this assumption unless the property is overridden.

Both `foundry.worker.enabled` and `foundry.runtime.local.enabled` are disabled by default. They must be explicitly enabled for local runtime testing.

## Runtime boundary and safety

The sample binds to `127.0.0.1`, so its URL is local to the same machine. This is not a public deployment URL and the local process adapter is **not a sandbox**.

Not implemented:
- repository checkout, ref resolution to an immutable commit, and build orchestration;
- image building or Docker execution;
- deployment authentication/authorization and tenant separation;
- CPU, memory, filesystem, network, and execution-time isolation for workloads;
- reliable multi-worker claiming, crash recovery, retries, and idempotency;
- production log retention, secret redaction, and a dedicated high-volume log backend;
- React dashboard and CI automation.

The planned first container runtime remains Docker, with cloud runtimes considered later only when justified. The local adapter is a smaller vertical slice to validate orchestration, logs, health checks, and the runtime interface before adding container complexity. Never expose arbitrary user code on the control-plane host.

## System-design principles demonstrated

- **Persist before long work:** create a deployment record before the worker performs its steps.
- **Explicit state machine:** keep legal transitions and terminal states explicit.
- **Success means readiness:** a process start is not success until the application health endpoint passes.
- **Separate status from diagnostics:** status answers which lifecycle stage is active; logs explain what happened.
- **Bounded cursor pagination:** fetch incremental log entries without returning the full history on every request.
- **Feature flags default off:** background processing and process launching must be explicitly enabled in local development.
- **Control plane versus runtime:** HTTP/resource management is separate from the interface that launches and checks an application.
- **Trust boundary first:** a host process or container is not, by itself, sufficient isolation for public multi-tenant arbitrary code execution.

## Next engineering checkpoints

1. Test runtime stop/cleanup on health failure, worker exceptions, and application shutdown.
2. Document repeatable local setup and the trusted-sample-only constraint.
3. Design the isolated Docker adapter before enabling arbitrary source builds.
4. Add idempotency, reliable job recovery, authorization, resource limits, and log redaction before any public workload execution.
