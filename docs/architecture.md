# Foundry — Initial Architecture

Status: **partially implemented**. The system shape and first project-registration API exist; the deployment lifecycle and runtime integration are still planned.

## Product boundary

Foundry is the **control plane** that coordinates deployments. A **runtime** is where the user's application actually executes. These are separate responsibilities.

- **Control plane:** accepts requests, validates configuration, records state, and presents deployment progress.
- **Deployment worker:** performs a deployment job and reports progress.
- **Runtime adapter:** gives Foundry a consistent interface for operations on a particular runtime.
- **Application runtime:** runs the resulting application container.

## Implemented so far

- Spring Boot 4.1.1 API using Oracle JDK 25.
- System endpoint: `GET /api/v1/system`.
- Project registration: `POST /api/v1/projects`, with request validation, generated UUID, creation timestamp, persistence, and a `201 Created` response with a `Location` header.
- Project listing and retrieval: `GET /api/v1/projects` and `GET /api/v1/projects/{id}`.
- Integration tests for successful registration/persistence, blank-name rejection, project listing, and missing IDs.
- Runnable API requests and response assertions in `apps/api/foundry-api/requests/projects.http`.
- H2 is the current local development database; PostgreSQL remains the planned durable database.

## First end-to-end deployment slice (target)

The next meaningful product slice is deployment metadata and lifecycle, before running deployment jobs:

```text
Developer
   |
   | register project / request deploy
   v
React dashboard (planned)
   |
   v
Spring Boot API
   |
   +------ H2 now / PostgreSQL later
   |
   | create deployment record (planned)
   v
Deployment worker (planned)
   |
   | build image / run container
   v
Runtime adapter (planned)
   |
   v
Docker runtime (first target, planned)
   |
   | health check + logs
   v
Deployment status shown in UI (planned)
```

Initially, the worker may run in the same development environment as the API to keep the slice small. The code boundary should still be clear so we can move the worker to a separate process when asynchronous execution and recovery become necessary.

## Core entities (initial proposal)

- **Project:** a registered application and its configuration.
- **Deployment:** one attempt to build and run a project.
- **Deployment status:** candidate states are `QUEUED`, `BUILDING`, `STARTING`, `HEALTH_CHECK`, `SUCCEEDED`, and `FAILED`. These are not implemented yet.
- **Deployment log:** timestamped output associated with a deployment.
- **Runtime target:** the place an application is deployed, initially a controlled Docker host.

State transitions must be explicit. A deployment should not report success until its health check passes.

## System-design principles to apply when needed

- **Explicit state machine:** define allowed deployment states and transitions so impossible state changes are rejected.
- **Persist before doing long work:** record a deployment request before processing so users can see its state and later recover from failures.
- **Control plane vs. data plane/runtime:** management APIs coordinate work; the runtime executes the user's application.
- **Idempotency and retries:** repeated requests or worker retries must not accidentally create duplicate deployments or containers. Add when asynchronous job processing is implemented.
- **Observability:** preserve status, timestamps, logs, and health-check results so failures can be diagnosed.
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
- treat auth, tenant isolation, resource limits, and runtime isolation as blockers before public arbitrary deployments.

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
