# Foundry — Initial Architecture

Status: proposed; implementation has not started.

## Product boundary

Foundry is the **control plane** that coordinates deployments. A **runtime** is where the user's application actually executes. These are separate responsibilities.

- **Control plane:** accepts requests, validates configuration, records state, and presents deployment progress.
- **Deployment worker:** performs a deployment job and reports progress.
- **Runtime adapter:** gives Foundry a consistent interface for operations on a particular runtime.
- **Application runtime:** runs the resulting application container.

## First vertical slice

The first milestone is intentionally narrow:

```text
Developer
   |
   | register trusted sample app / request deploy
   v
React dashboard
   |
   v
Spring Boot API ------ PostgreSQL
   |
   | create deployment record
   v
Deployment worker
   |
   | build image / run container
   v
Docker runtime
   |
   | health check + logs
   v
Deployment status shown in UI
```

Initially, the worker may run in the same development environment as the API to keep the slice small. The code boundary should still be clear so we can move the worker to a separate process when asynchronous execution and recovery become necessary.

## Core entities (initial proposal)

- **Project:** a registered application and its configuration.
- **Deployment:** one attempt to build and run a project.
- **Deployment status:** `QUEUED`, `BUILDING`, `STARTING`, `HEALTH_CHECK`, `SUCCEEDED`, or `FAILED`.
- **Deployment log:** timestamped output associated with a deployment.
- **Runtime target:** the place an application is deployed, initially a controlled Docker host.

State transitions must be explicit. A deployment should not report success until its health check passes.

## Runtime portability

Define a small runtime interface rather than spreading Docker-specific behavior across controllers and business logic. The initial adapter targets Docker. Future adapters may target Cloud Run or Kubernetes.

Portability is a design goal, not a promise that every runtime has identical behavior. Each adapter will need to document capability differences.

## Safety boundaries

Executing user-supplied containers is potentially dangerous: containers are not, by themselves, a sufficient security boundary for a public multi-tenant platform.

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
