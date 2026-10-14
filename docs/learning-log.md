# Foundry Learning Log

This log records concepts learned while building Foundry. Add concise entries after each session; prefer experiments and observed behavior over copied definitions. It also tracks practical system-design lessons that may be useful in interviews.

## Entry: Cloud VM, networking, and containers

### Observed
- A Compute Engine VM can run multiple services on different ports.
- Nginx on the host listened on port 80; a Docker container exposed its port 80 through host port 8080.
- `curl localhost:80` tests the host's local HTTP path; `curl localhost:8080` reached the container through Docker's port mapping.
- Stopping the host Nginx service did not stop the Nginx container, demonstrating separate lifecycles.
- The VM's internal address was `10.128.0.3`; its `us-central1` subnet range was `10.128.0.0/20`.
- A GCP VPC firewall rule allowing ingress TCP/80 could block external access while local Nginx access continued to work.

### Concepts
- **VM:** a software-created computer running on a cloud provider's physical infrastructure.
- **IP address:** a network address used to route traffic toward a network endpoint.
- **Port:** a numbered endpoint used by a network service.
- **Process / PID:** a running program / its process identifier.
- **Listening socket:** a network endpoint a process uses to accept connections.
- **Loopback / localhost:** a path for a machine to communicate with itself, typically using `127.0.0.1` for IPv4.
- **Private/internal IP:** an address used within an internal network.
- **External/public IP:** an Internet-routable address, when assigned.
- **VPC:** a virtual cloud network.
- **Subnet / CIDR:** an IP address range inside a network / notation that expresses that range.
- **Ingress / egress:** incoming / outgoing network traffic.
- **Firewall rule:** a policy that allows or denies specified traffic for selected targets.
- **Docker image:** a packaged filesystem and configuration used to create containers.
- **Container:** an isolated running instance created from an image; it shares the host kernel.
- **Port mapping:** forwarding a host port to a port in a container.
- **Registry:** a service for storing and distributing container images.
- **Artifact Registry:** Google's registry service used to store the `foundry-web:v1`, `v2`, and `v3` images.
- **Cloud Run service:** a managed endpoint that runs a container image.
- **Revision:** a specific deployed configuration/image version of a Cloud Run service.

### Commands used

```bash
ps aux | grep nginx
sudo ss -ltnp
sudo systemctl status nginx
sudo systemctl stop nginx
sudo systemctl start nginx
curl localhost:80
curl 10.128.0.3:80
ip addr
gcloud compute firewall-rules describe foundry-allow-http
gcloud compute networks list
gcloud compute networks subnets list
sudo docker images
sudo docker ps
sudo docker ps -a
sudo docker build -t foundry-web:v2 .
sudo docker run -d --name foundry-web-v2 -p 8082:80 foundry-web:v2
gcloud artifacts docker images list us-central1-docker.pkg.dev/foundry-001/foundry-images --include-tags
gcloud run services describe foundry-web --region=us-central1
gcloud run revisions list --service=foundry-web --region=us-central1
```

### Lessons to carry forward
- A successful build does not prove a service is reachable from outside.
- Diagnose by layers: application/process → listening port → local request → interface/network → firewall → external request.
- Building an image, storing an image, and running a container are separate responsibilities.
- A Cloud Run service and a Compute Engine VM are separate resources, but they can still share the same GCP project and billing dependency.
- Budget alerts are not hard spending caps; always check billing state and clean up unused resources.

## Entry: First Foundry API endpoint

### Implemented and verified
- Generated a Spring Boot 4.1.1 Maven project using Oracle JDK 25.
- Ran the Maven Wrapper test/build command successfully from the terminal, even though IntelliJ's Maven synchronization reported a separate Guice importer error.
- Added `SystemController` with `GET /api/v1/system`.
- Verified the local endpoint returned HTTP 200 and JSON containing `name: Foundry`, `status: UP`, and `version: 0.1.0`.

### Concepts
- **Control plane:** the part of a platform that accepts management requests and tracks system state.
- **API endpoint:** a specific operation exposed at a URL and HTTP method.
- **REST controller:** a Spring component that maps HTTP requests to Java methods and serializes returned data.
- **JSON:** a text format for exchanging structured data between programs.
- **Maven Wrapper:** project-provided scripts that run the configured Maven version.
- **Health endpoint:** an endpoint used to report application health/readiness.
- **Git commit / push:** a commit records a change locally; push sends commits to the remote repository.

## Entry: Project registration API

### Implemented and verified
- Added `ProjectEntity` with a generated UUID, name, repository URL, and creation timestamp.
- Added `ProjectRepository` extending `JpaRepository<ProjectEntity, UUID>`; Spring Data JPA supplies common persistence operations.
- Added `ProjectCreateRequest` with `@NotBlank` and `@Size` validation constraints, plus a separate `ProjectResponse` DTO.
- Added `ProjectService` and `ProjectController`.
- Implemented `POST /api/v1/projects` returning `201 Created` and a `Location` header; `GET /api/v1/projects` lists projects; `GET /api/v1/projects/{id}` retrieves a project or returns `404 Not Found`.
- Confirmed blank project names return `400 Bad Request`.
- Added `ProjectControllerIntegrationTest`. The Maven test command completed successfully; the tests cover registration/persistence, invalid blank name, list, and missing ID.
- Added `apps/api/foundry-api/requests/projects.http` for the IntelliJ HTTP Client with response assertions and a variable that carries the newly created project ID between requests.
- Current local development uses H2; PostgreSQL is still a future step.

### New terminology
- **DTO (Data Transfer Object):** an object designed to carry data between components, such as client requests and backend responses.
- **Java record:** a concise Java type for data-carrying values; the compiler generates its constructor and accessors.
- **Bean Validation:** declarative constraints such as `@NotBlank` and `@Size`; constraints take effect when request validation is invoked.
- **Entity:** a class mapped to persisted database data.
- **Primary key:** a field identifying a database record.
- **UUID:** a 128-bit identifier commonly represented as a string; used here instead of a sequential public ID.
- **Repository:** a persistence abstraction used to save and retrieve entities.
- **Spring Data JPA:** a Spring abstraction that provides repository implementations for JPA entities.
- **JPA:** the Java persistence specification used to map Java objects to relational database records.
- **Service layer:** the component where application rules and coordination live between controllers and persistence.
- **HTTP status 201 Created:** indicates that a resource was successfully created.
- **Location header:** identifies the URL of a newly created resource.
- **HTTP status 400 Bad Request:** indicates the request was rejected, here because its input failed validation.
- **HTTP status 404 Not Found:** indicates the requested resource does not exist.
- **Integration test:** checks that multiple components work together, such as controller, validation, service, repository, and database.
- **MockMvc:** Spring's test client for exercising MVC request handling without making a real network connection.
- **Regression test:** protects already-working behavior from breaking when code changes.
- **IntelliJ HTTP Client:** runs saved HTTP requests from a `.http` file and can assert on response status/body.
- **Test fixture / setup:** data preparation performed before a test, such as clearing the database.
- **Git staging area:** the proposed set of changes to be included in the next commit.
- **Untracked file:** a file Git sees in the working directory but has not begun tracking.

### System-design lessons / interview connections
- **Separate API contracts from persistence entities.** Request and response DTOs prevent database schema changes from accidentally becoming public API changes.
- **Layer responsibilities.** The controller handles HTTP mapping, the service coordinates application rules, and the repository handles persistence. Keep them separate for testability and maintainability; avoid adding layers that have no current responsibility.
- **Validate at the boundary.** Reject invalid input before saving records. Tests should verify the invalid request returns the expected status and has no side effect in the database.
- **Resource lifecycle.** A project can be created, listed, retrieved, or not found. Foundry's deployments will need a richer explicit lifecycle.
- **Use observable HTTP semantics.** `201 Created` plus a `Location` header tells clients that creation occurred and where to retrieve the resource.
- **HTTP tests and automated tests solve different needs.** The IntelliJ `.http` file is convenient for exploration; JUnit integration tests provide repeatable regression checks in local builds and CI.
- **Persistence assumptions matter.** H2 is convenient for local iteration, but in-memory development storage is not the same as durable production storage. PostgreSQL and migration strategy will be introduced when needed.
- **Control plane vs. runtime.** Foundry's API manages metadata and coordinates work; a separate runtime executes applications. Don't conflate successful API requests with successful deployments.
- **Trust boundary.** Arbitrary user code execution is unsafe to expose without authentication, resource limits, and real isolation. The initial runtime must be limited to trusted sample projects until those controls exist.

### Next milestone
Design a deployment resource and its state machine before implementing it. Define states, allowed transitions, timestamps, failure information, and what "success" proves. Then add the entity, DTOs, service, controller, and tests as one logical slice. Don't build a real deployment worker yet.

## Micro-revision format

When the developer has 5–10 spare minutes, use an interactive, practical revision: ask one short recall question at a time, let them answer, correct misconceptions, and occasionally ask them to explain a concept using Foundry's actual flow. Track both terminology and system-design ideas, including interview connections and weak areas. Keep it active-recall and project-connected, not a long lecture. Don't launch a revision unless the developer asks for one.


## Entry: Deployment lifecycle and logging design (design phase; implementation not started)

### Key design decisions
- A Deployment represents one attempt for a project and a captured source configuration/revision.
- Candidate lifecycle: `QUEUED → BUILDING → STARTING → HEALTH_CHECK → SUCCEEDED`; any nonterminal state can transition to `FAILED`.
- `SUCCEEDED` and `FAILED` are terminal for a deployment record. A new attempt should normally create a new deployment record so history remains auditable.
- A deployment counts as successful only after the configured health check passes; building an image or starting a process alone is insufficient.
- Store lifecycle timestamps and a sanitized failure summary.
- Build/deployment logs and application runtime logs are separate streams with different start times and retention needs.
- Do not put an unlimited combined log blob in the Deployment row. Keep mutable deployment metadata separate from append-only log entries.
- For a local prototype, log entries may be persisted in H2 with a deployment ID, monotonically increasing sequence number, timestamp, stage, stream/severity, and message.
- The first UI can poll for entries after a known sequence cursor. Server-Sent Events (SSE) can be considered when live streaming becomes necessary; WebSockets are not justified unless bidirectional communication is needed.
- Bound entry size, plan retention/cleanup, enforce access control, and redact secrets. Never intentionally log credentials, access tokens, secret environment values, or private keys.
- At higher log volume, move to a dedicated log sink/backend rather than treating a relational metadata database as an unlimited log warehouse.

### New concepts and interview connections
- **State machine:** a defined set of states plus the transitions permitted between them.
- **Terminal state:** a state from which that record cannot transition further, such as `SUCCEEDED` or `FAILED`.
- **Invariant:** a condition that must remain true; Foundry's invariant is that success requires a passing health check.
- **Asynchronous processing:** accept and persist a request promptly, then let a worker perform long-running work independently of the HTTP request.
- **Snapshotting configuration:** preserve the source URL/ref and resolved revision used for one deployment so later project changes do not rewrite its history.
- **Append-only log:** new log entries are added rather than repeatedly replacing a whole log document.
- **Cursor / sequence number:** a position a client can use to ask only for new log entries, rather than downloading the entire log every time.
- **Polling:** clients periodically request updates. It is easy to implement and recover after disconnects but creates repeated requests and may add latency.
- **Server-Sent Events (SSE):** a server-to-client streaming mechanism suited to pushing live log updates; it is one-way and simpler than WebSockets for this use case.
- **Log retention:** a policy defining how long log data remains available before cleanup.
- **Redaction:** removing secrets or sensitive values from log content before storing or displaying it.
- **Observability:** using state, timestamps, logs, and health-check results to infer what the system is doing and diagnose failures.
- **Metadata versus telemetry separation:** deployment status records are structured control-plane data; logs may be much higher volume and need separate paging, retention, access, and storage rules.
- **HTTP 202 Accepted:** appropriate for an accepted asynchronous operation that has not completed yet; it must not be interpreted as success of the deployment itself.

### Design trade-offs
- H2 log storage keeps the first prototype simple and testable, but is not the intended solution for high-volume production logs.
- Polling is the simplest first log UI; SSE can be introduced when near-real-time updates are worth the additional lifecycle/reconnection considerations.
- Persisting a deployment record before work begins makes it visible and recoverable, but actual reliable job execution will later require queue/worker recovery and idempotency.
- A failure summary belongs on the Deployment record; the detailed diagnostic trail belongs in log entries.

### Next milestone
Add persisted append-only deployment log entries and a read endpoint supporting an `afterSequence` cursor. Test log ownership, ordering, and empty results. Only after the log data model and API work should we introduce a worker that actually builds and launches containers.


## Entry: Deployment record API and live HTTP verification

### Implemented and verified locally
- Added a deployment state enum and entity with a guarded lifecycle: `QUEUED → BUILDING → STARTING → HEALTH_CHECK → SUCCEEDED`, with a failure path from any nonterminal state.
- A deployment can become `SUCCEEDED` only after the health-check state is reached. Terminal deployments cannot be advanced again; failures require a nonblank reason.
- Added request/response DTOs, repository, service, and controller for creating a deployment under a project, listing that project's history, and retrieving a deployment by ID.
- Creating a deployment captures the project's repository URL and the requested source ref (branch or tag) so later project edits do not rewrite the deployment's source snapshot.
- Integration tests cover creation, validation, missing projects/deployments, listing, and retrieval. The local Maven run completed with **19 tests, 0 failures, 0 errors, 0 skipped**.
- The IntelliJ HTTP Client collection `requests/deployments.http` was run against the live local API. The user confirmed the endpoint responses matched expectations; the retrieved record contained its IDs, captured repository URL, `sourceRef: main`, `status: QUEUED`, a creation timestamp, and null start/completion/failure fields.
- **Important boundary:** a `201 Created` deployment response means that the deployment record was accepted/persisted, not that an application was built or launched. There is no deployment worker yet.
- The deployment implementation is verified in the local working tree, but its source changes still need to be checked for commit/push to GitHub `main`.

### New concepts and interview connections
- **Source ref:** a requested branch or tag. A future worker should also record the resolved Git commit SHA to make the actual source revision reproducible.
- **Resource-oriented endpoint:** an API path models a resource, for example a deployment nested under its project for creation/history and addressed by ID for retrieval.
- **Derived repository query:** Spring Data JPA can infer a query from method names such as `findAllByProject_IdOrderByCreatedAtDesc`.
- **Historical source snapshot:** storing the repository URL and requested ref on each deployment protects the deployment record from later project-configuration changes.
- **Live HTTP test vs. integration test:** JUnit/MockMvc proves components work together in the test environment; the IntelliJ HTTP Client verifies requests against the running local server and its actual configuration.
- **Persistence vs. execution:** a deployment record in `QUEUED` proves the control-plane request was recorded; it does not prove a build or runtime exists.

### System-design lessons
- Return `201 Created` and a `Location` header when the deployment resource is created, while making the current state explicit in the response.
- Keep the controller focused on HTTP semantics, the service on resource lookup/creation rules, and the repository on persistence.
- Test both successful behavior and negative paths such as blank source refs and unknown project IDs.
- Do not add a worker until deployment records and the next log-persistence slice have clear contracts.