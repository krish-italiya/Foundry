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
- The deployment implementation, integration tests, and HTTP request collection have been confirmed present on GitHub `main`.

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

## Entry: Deployment log persistence and cursor-based reads

### Implemented and verified locally
- Added log stage, stream, and severity enums plus a JPA `DeploymentLogEntry` entity linked to `DeploymentEntity`.
- Each entry records a positive per-deployment sequence number, timestamp, stage, stream, severity, and bounded message. A unique constraint and index cover deployment ID + sequence number.
- Added `DeploymentLogRepository`, `DeploymentLogService`, and `DeploymentLogController`. The service appends entries in a transaction while locking the deployment row, then allocates the next sequence number. Only the read API is exposed over HTTP; the future trusted worker will append through the service.
- Implemented `GET /api/v1/deployments/{deploymentId}/logs?afterSequence=0&limit=50` with cursor retrieval and a maximum page size of 100. The service fetches one extra row to determine whether another page exists.
- Updated `DeploymentControllerIntegrationTest` with coverage for ordering and multiple pages, empty log results, unknown deployments, and invalid cursors/page sizes.
- The local Maven suite completed with **23 tests, 0 failures, 0 errors, 0 skipped**.
- Verified the endpoint against the live API with IntelliJ HTTP Client. The response was `200 OK` with `entries: []`, `nextAfterSequence: 0`, and `hasMore: false` for a deployment that had not generated logs.
- The log source files, tests, and `requests/deployments.http` are confirmed present on GitHub `main`.

### New concepts and interview connections
- **Append-only application contract:** the supported operation adds a new entry; existing entries are not edited through the application API.
- **Composite unique constraint:** the pair `(deployment_id, sequence_number)` is unique, while different deployments may each begin at sequence 1.
- **Pessimistic write lock:** a database lock that makes concurrent appenders to the same deployment take turns while choosing the next sequence.
- **Cursor pagination:** the client supplies its last-seen sequence and receives only entries with a larger sequence number.
- **Lookahead pagination:** fetching `limit + 1` rows lets the API determine `hasMore` without a separate count query.
- **Bounded page and message sizes:** limits prevent a single request or entry from consuming unbounded resources.
- **Write-path trust boundary:** the service can append logs for internal workers, while a public HTTP client cannot arbitrarily inject log entries.
- **Test distinction:** automated tests seed entries through the service to validate ordering and paging; the live HTTP request verified the currently-empty result from the running server. Real log generation awaits a worker.

### Trade-offs / follow-up
- Locking the deployment row is understandable and sufficient for the current relational prototype, but it may limit throughput if one deployment produces logs at a very high rate.
- H2 is suitable for the prototype; retention, secret redaction, authorization, and a high-volume external log backend are not yet implemented.
- The empty-page live response does not prove the full populated log flow on the running server; that behavior is currently covered by automated tests.

### Next milestone
Commit and push the local logging implementation, verify the files are present on GitHub `main`, and keep the repository clean. Once synchronized, design the first controlled deployment worker that advances the deployment state and appends logs without executing arbitrary untrusted code.

## Entry: Controlled deployment worker and failure-path verification

### Implemented and verified locally
- Added `DeploymentWorker`, which locks and checks a deployment, processes it only if still `QUEUED`, advances it to `BUILDING`, writes diagnostic log entries, and records a terminal failure when no runtime adapter is configured.
- Added an opt-in `DeploymentWorkerPoller` that polls the oldest queued deployment. `foundry.worker.enabled=false` is the default so normal development does not automatically fail new deployments.
- Added a repository lookup for the oldest deployment in a given status and worker integration tests for safe failure/log creation, unknown deployment IDs, and terminal records not being processed again.
- The local Maven suite completed with **26 tests, 0 failures, 0 errors, 0 skipped**.
- Live IntelliJ HTTP Client verification retrieved three persisted entries for one deployment in sequence order: queue claim, runtime-unavailable warning, and worker error indicating the deployment was marked failed. The log endpoint returned HTTP 200 and `hasMore: false`.
- This is a controlled failure-path prototype only. No repository is cloned, no image is built, and no application container is launched. A real runtime adapter and successful health check are required before Foundry may mark a deployment `SUCCEEDED`.
- **Sync status:** worker/poller files, worker integration tests, repository update, scheduling setup, and worker configuration still need to be committed/pushed and confirmed on GitHub `main`. Logging implementation itself is already present there.

### New concepts and interview connections
- **Polling worker:** checks persisted work items periodically rather than receiving work from a broker. It is simple for a prototype but adds polling latency and coordination concerns.
- **Claim/recheck under a database lock:** the worker rechecks deployment status after locking it so an already-terminal record is not processed again. This pattern is not yet a full distributed queue or recovery protocol.
- **Fail closed:** when a required runtime capability is missing, the worker stops and records failure rather than pretending deployment succeeded.
- **Transactional state/log updates:** the worker service method is transactional so deployment state changes and log writes can roll back together if persistence fails.
- **Idempotent terminal handling:** unknown and non-queued deployments are not processed again by this worker method.
- **Feature flag / opt-in configuration:** `foundry.worker.enabled` prevents background polling from changing new deployment records unless explicitly enabled.
- **End-to-end observability:** a persisted status is the machine-readable outcome; ordered logs explain why that outcome occurred.

### Trade-offs / unresolved issues
- This poller is for a single-process learning prototype, not production multi-instance execution. A reliable queue/claim protocol, crash recovery, retry policy, and idempotency keys remain future work.
- The failure message reflects missing runtime capability, not a real source checkout/build failure.
- The runtime choice must fit local environment and resource constraints. Do not expose real execution publicly or accept untrusted repositories until isolation, access control, resource limits, and secret redaction are addressed.

### Next milestone
Push and verify the worker implementation. Then choose and implement a constrained runtime adapter for a trusted sample app, including startup, health-check verification, and cleanup. Never mark success based only on build/start.

## Entry: Trusted local runtime and end-to-end health verification (2026-10-20)

### Implemented and verified
- Added a `RuntimeAdapter` interface and `LocalProcessRuntimeAdapter` for a constrained trusted-sample workflow.
- Built the sample application JAR from `apps/samples/hello-service` and launched it as a separate Java process on an available loopback port.
- Captured the sample process's standard output and standard error and persisted them alongside deployment-stage log entries.
- Probed the application's `/health` endpoint. The check passed only when the response was HTTP 200 and contained `"status":"UP"`.
- The worker then persisted `status: SUCCEEDED`, `applicationUrl`, and lifecycle timestamps. A live `GET /api/v1/deployments/{deploymentId}` response confirmed the final state and URL.
- A relative JAR path initially resolved against the wrong working directory. Updating the configuration to a repository-root-relative path fixed the lookup for the local launch setup.
- The full Maven `clean test` command and `git diff --check` completed successfully during this check-in. The feature code was pushed to GitHub `main`.

### Concepts and interview connections
- **Child process:** a separate operating-system process started and managed by the Foundry API process.
- **ProcessBuilder:** Java API used to construct a process command and working directory without relying on a shell command string.
- **Loopback binding:** binding to `127.0.0.1` limits access to the local machine; it is not an externally reachable deployment URL.
- **Ephemeral port:** an available port chosen for this process, avoiding a fixed-port conflict in this prototype.
- **Standard output and standard error:** separate process streams captured so startup messages and errors can be diagnosed.
- **Health/readiness check:** an application-level request that tests whether the service is ready, rather than inferring readiness from process creation alone.
- **Runtime adapter:** an interface that keeps worker orchestration independent of the details used to start, check, and stop a specific runtime.
- **Working directory:** the directory used to resolve relative paths. A path can fail when the IDE and terminal launch a program from different directories.
- **Feature flag:** a setting that explicitly enables a worker or runtime capability; safe defaults avoid launching processes unexpectedly.

### Design boundaries and follow-up
- This is a **trusted prebuilt JAR** workflow. Foundry currently records the repository URL/ref but does not clone that repository, resolve a commit, or build it.
- A local Java process is not a sandbox. Do not accept untrusted repositories or expose this launcher publicly; isolation, authorization, resource limits, and secret handling must come first.
- The application URL is loopback-only and useful for local verification, not external users.
- Test cleanup after unsuccessful health checks, worker exceptions, and API shutdown. Reliable crash recovery and cross-process workload tracking are not implemented yet.
- Docker remains the intended first isolated runtime target; implement it as a separate adapter only after the current lifecycle is well understood.


## Entry: Failure cleanup must not hide deployment failure (2026-10-22)

### Implemented and verified
- Refactored the worker's unhealthy-runtime path to use the same failure handler as other runtime errors.
- Made runtime cleanup best-effort: when `runtime.stop()` throws a `RuntimeException`, the worker preserves the original failure reason, adds cleanup diagnostics, and still attempts to persist the deployment as `FAILED`.
- Added `cleanupFailureDoesNotPreventDeploymentFromBeingMarkedFailed` to `DeploymentWorkerRuntimeIntegrationTest`. It simulates an unhealthy application and a cleanup exception, then asserts that the deployment is failed and the failure details contain both causes.
- Ran the complete Maven suite: **29 tests, 0 failures, 0 errors, 0 skipped**.
- Committed and pushed as [`0e25c1e`](https://github.com/krish-italiya/Foundry/commit/0e25c1e21d2d885fa3808a60597b2c1ddc5001dd).

### Concepts and interview connections
- **Best-effort cleanup:** resource cleanup should be attempted, but an error in cleanup should not erase the original operation outcome.
- **Failure-path testing:** deliberately cause multiple failures in sequence to validate that the system reaches a predictable terminal state.
- **Preserving primary and secondary errors:** the health-check failure remains the main deployment cause, while the cleanup exception is attached as diagnostic context.
- **Terminal-state observability:** persist the final status and include enough sanitized diagnostic information to explain why the deployment failed.

### Boundary and follow-up
- This regression test uses a mocked runtime adapter to reproduce a cleanup exception deterministically. It does not by itself prove that every real OS-process cleanup or API-shutdown path is safe.
- Next, test the concrete local adapter's process termination and shutdown behavior, including unsuccessful health checks and child processes that resist graceful termination. Then reassess the runtime lifecycle before starting the isolated Docker adapter.


## Entry: Repository-to-deployment pipeline foundation (2026-10-23)

### Implemented on `feat/repository-build-pipeline` — local verification pending
- Added an opt-in repository build service with strict HTTPS GitHub URL normalization and an exact trusted-repository allowlist.
- Clones the requested ref into a disposable workspace, discovers `pom.xml` and `package.json` files while skipping generated/dependency directories, and supports an optional service path for monorepos.
- Maven strategy runs `test package` and only accepts an executable Spring Boot JAR as deployable.
- npm strategy requires `package-lock.json`, runs `npm ci`, runs `npm test` when a usable script exists, runs `npm run build` when configured, and supports configured `preview` or `start` scripts.
- A build-command runner captures stdout and stderr separately into the existing log stream, records command exit codes, and enforces per-command timeouts.
- A repository-artifact runtime can launch an executable Spring Boot JAR or an npm preview/start process, then checks health before recording success.
- Added the optional `servicePath` field to deployment requests/responses and an input in the dashboard.
- Added an explicit sample-only mode flag so the prebuilt sample JAR cannot be mistaken for the registered repository's deployment.
- Added unit tests for GitHub repository URL validation. The full local Maven suite and frontend production build still need to run against this branch.

### Trust boundary and limitations
- Repository builds remain disabled by default and require an exact trusted GitHub allowlist.
- This code executes repository build scripts locally and is not a sandbox. Do not expose it to arbitrary public repositories.
- First Maven support is executable Spring Boot JARs. First npm support requires a package lock and `preview` or `start` script. Private-repository authentication, container isolation, resource quotas, credential boundaries, log redaction, retries, and production ingress are not implemented.

### Next validation
Run Maven `clean test` and frontend `npm run build`, then test a trusted repository end to end and inspect persisted stdout/stderr/exit-code entries before calling this milestone verified.


## Entry: Repository onboarding and per-project build trust (2026-10-25)

### Verified deployment milestones from the current local workflow
- The Java sample repository completed checkout, Maven `verify`, runnable-JAR selection, launch, and HTTP health check.
- The independent Instacv and Airbnb Clone React/Vite repositories completed `npm ci`, production builds, Vite Preview startup, and health checks using explicit service paths when needed.
- A standalone Express repository with no npm lockfile completed the warned `npm install` fallback, launched on Foundry's assigned `PORT`, and passed the health check.
- The current API test suite had reached **37 passing tests** before the latest trust-onboarding changes. The new trust endpoints and persistence changes have **not yet been locally verified**.

### New implementation in progress on `feat/npm-lockfile-fallback`
- Added a persisted `buildTrusted` decision to each registered project, defaulting to false.
- Added a project registration consent checkbox, a project-level trust status, and a reversible `Trust repository` / `Revoke trust` action.
- Added `PATCH /api/v1/projects/{id}/build-trust`.
- Updated the build service to verify that the deployment's registered project matches the repository URL and requires project trust or an explicit local administrator allowlist entry before cloning/building.
- Changed local H2 configuration to a git-ignored file-backed database so registered projects and trust decisions can survive API restarts. Added a separate in-memory H2 test database to avoid mutating local data during integration tests.
- Added integration tests for default-untrusted registration, explicit trust, toggling trust on/off, unknown project handling, and rejection of an untrusted build before any command execution.

### Concepts and interview connections
- **Explicit trust/consent gate:** registering a repository is not the same as authorizing its build scripts to execute.
- **Least privilege:** a new project is untrusted by default; local execution requires a deliberate opt-in or a configured administrator exception.
- **Reversible authorization:** trust can be revoked without deleting and re-registering the project.
- **Durable metadata:** file-backed H2 survives ordinary process restarts; tests use an isolated in-memory database to avoid side effects.
- **Reproducible dependency installation:** `npm ci` is preferred when a lockfile exists; `npm install` fallback is explicitly warned because dependency resolution can drift.

### Security boundary and next validation
- Project trust and the administrator allowlist are local execution gates, **not a sandbox**. They do not make public multi-user or remotely accessible builds safe. The build worker still needs process/filesystem isolation, resource limits, secret handling, and proper authentication before public use.
- Run Maven `clean test` and `npm run build` on Windows; fix any compiler/TypeScript/test issues.
- Verify project registration and trust state survive an API restart. Then register a repository without adding it to `foundry.build.allowed-repositories`, opt in via the dashboard, deploy it, revoke trust, and confirm subsequent builds are denied.


## Entry: Local application ingress and host-based routing (2026-10-25)

### Implemented on `feat/local-application-routing`; local verification pending
- Separated internal runtime target addresses (the app process's `127.0.0.1:ephemeral-port`) from the user-facing application URL.
- Introduced a stable per-deployment URL shape: `http://{deployment-uuid}.localhost:8080`.
- Added hostname parsing that routes only a single valid UUID under the `.localhost` suffix.
- Added a servlet filter that proxies matching requests to the active deployment's private loopback URL, preserving method, request path, query string, request headers, and body, then relaying status, response headers, and body.
- Unknown or stopped deployment hosts return 404; ordinary Foundry API hostnames continue through normal Spring MVC routing.
- Added focused tests for local URL creation/validation and an HTTP proxy round trip. The new routing changes have not yet been run through the Windows Maven suite.

### Concepts and interview connections
- **Ingress / reverse proxy:** a front door that receives client requests and forwards them to a backend service selected by host/path.
- **Host-based routing:** the HTTP hostname identifies the destination application; Foundry uses a deployment UUID subdomain.
- **Internal target vs. public URL:** the service stays bound to loopback; the stable hostname is a proxy address, not the process's own listening socket.
- **Hop-by-hop headers:** connection-specific headers must not be blindly copied through a proxy because they describe one HTTP connection, not the end-to-end request.
- **Loopback-only hostname:** `.localhost` is for same-machine preview; this does not create a DNS record or an Internet-reachable service.
- **Active runtime registry:** routing only succeeds while the adapter still has an active-process mapping for the deployment ID.

### Boundaries and next validation
- This local router is not a production reverse proxy. Public deployment requires TLS/DNS, authentication and authorization, request limits, timeout/rate policies, safe forwarding-header rules, durable runtime mapping/recovery, and isolated workloads.
- Runtime registry state is in memory. The API's `@PreDestroy` cleanup stops child processes; after API restart, persisted deployment records may say `SUCCEEDED` while the process is gone. The local host URL then correctly returns 404, but lifecycle reconciliation is a future task.
- Run API `clean test` and frontend `npm run build`; fix any compile/test errors before merging.
- Verify a live Express deployment receives a `.localhost:8080` URL and the browser can navigate paths and load assets through the router.


## Entry: Runtime lifecycle reconciliation (2026-10-25)

### Implemented and verified locally on `feat/runtime-lifecycle-reconciliation`
- Separated deployment status from runtime state. `SUCCEEDED` records that the initial health check passed; it does not guarantee the process remains alive later.
- Added `RuntimeState`: `NOT_STARTED`, `RUNNING`, `STOPPED`, `CRASHED`, and `INTERRUPTED`.
- Added process liveness inspection to the local runtime adapter and a scheduled monitor that reconciles persisted successful deployments against the in-memory process registry.
- Exposed runtime state through the deployment response and dashboard detail strip.
- Windows API Maven suite passed: **54 tests, 0 failures, 0 errors, 0 skipped**. The frontend production build passed.
- End-to-end deployed `render-examples/express-hello-world`: Foundry cloned the repository, installed dependencies, launched Express on a dynamic local port, passed the health check, and served the app through its UUID `.localhost:8080` URL.

### Live lifecycle experiments
- **Application process exit:** stopped only the verified Express process (`node app.js`) while Foundry remained running. The dashboard changed runtime state from `RUNNING` to `CRASHED` and emitted `Application process exited unexpectedly; marked CRASHED.`
- **Foundry API restart:** restarted the API after a deployment had succeeded. The new API process no longer tracked that deployment's application process, so the monitor changed its runtime state to `INTERRUPTED` and emitted `Deployment runtime is not tracked by the current Foundry process; marked INTERRUPTED.`
- In both cases, the original deployment status remained `SUCCEEDED`, intentionally distinguishing the initial rollout result from current runtime availability.

### Concepts and interview connections
- **Deployment state vs. runtime state:** successful rollout is an event in time; it is not an ongoing health guarantee.
- **Reconciliation loop:** repeatedly compare persisted metadata with observed process state and correct stale records.
- **Crash detection:** a known child process that has exited is different from a process that isn't tracked after the control plane restarts.
- **Interrupted runtime:** the app process registry is in memory, so a persisted `SUCCEEDED` record after a Foundry restart doesn't prove the app still exists.
- **Idempotent reconciliation:** persist a runtime state transition once and avoid duplicating cleanup/log events on every poll.

### Boundaries and next validation
- This monitor detects process unavailability; it does not restart applications, perform retries, or provide sandboxing.
- The tested `.localhost` URL is local preview only, not public Internet hosting.
- Public hosting remains out of scope until execution isolation, resource limits, access controls, and public TLS/DNS ingress are designed and validated.


## Entry: Docker-backed runtime vertical slice (2026-10-27)

### Feature branch: `feat/docker-runtime-adapter`
- Added a separate opt-in `DockerRuntimeAdapter`; the current local-process adapter remains available behind its existing property.
- The Docker adapter packages supported artifacts using generated, fixed Dockerfiles rather than interpolating repository scripts into shell command strings.
- Node services are copied without `.git`, `node_modules`, common build directories, `.env*`, or `.npmrc`; dependencies install inside the image and use `npm ci` when a lockfile exists.
- Java images use a non-root numeric user; Node uses the image's `node` user; static Vite output uses unprivileged Nginx.
- Each container gets a deterministic Foundry name, port 8080 inside the container, loopback-only host publishing, CPU/memory/PID limits, dropped Linux capabilities, no-new-privileges, and a bounded temporary filesystem.
- Docker CLI arguments are passed as individual `ProcessBuilder` arguments. Image build output and container logs flow into the existing deployment log stream.
- Local ingress and runtime reconciliation now use a common managed-runtime interface to support either local-process or Docker mode.
- Docker execution remains disabled by default; do not enable local and Docker runtime flags together.

### Important boundary
- This feature branch has not yet been compiled or exercised against Docker Desktop; run API tests, frontend production build, and a live Windows Docker deployment before merge.
- Docker restrictions provide defense-in-depth, not a complete sandbox for hostile repositories. Do not expose public deployments without a separately isolated build/execution boundary, credential policy, authentication, quotas, and public TLS/DNS.

### First live Docker build failure and fix (2026-10-27)
- The first Express image build failed at `RUN npm ci --omit=dev` with `EACCES: permission denied, mkdir '/app/node_modules'`.
- Root cause: Docker's `WORKDIR /app` created a root-owned directory; switching to `USER node` did not make that directory writable. `COPY --chown=node:node` applies ownership to copied files/directories, but doesn't guarantee the parent work directory is owned by Node.
- Fix committed to `feat/docker-runtime-adapter`: explicitly `RUN chown node:node /app` before switching to `USER node`, with a regression assertion checking the Dockerfile instruction order.
- The backend suite (59 tests) and frontend production build passed before the live integration test. After applying the ownership fix, the trusted Express example successfully built an image, started a constrained container, streamed its logs, passed the health check, and rendered through its UUID `.localhost:8080` URL.
- Follow-up UX work on the same feature branch replaces the one-page dashboard with separate Overview, Projects, Deployments, and Build & Runtime views. Deployment history is searchable, filterable by status, and paginated to eight releases per page, with a compact mobile navigation bar.
- Verification still outstanding: rerun the frontend production build after the UI refactor, smoke-test the new views on desktop/mobile, deploy a distinct Vite static frontend, and exercise Docker crash/cleanup/restart handling.
- System-design lesson: unit tests validate the generated configuration contract, but a real image build catches filesystem ownership and runtime integration assumptions that string-based tests cannot prove.


### Project-scoped dashboard and frontend modularization (2026-10-28)
- Product information architecture: top-level navigation is limited to **Overview** and **Projects**. Selecting a project opens its workspace with **Overview**, **Deployments**, and **Build & Runtime** tabs. This keeps release history and logs scoped to the owning project and avoids global deployment/log screens.
- Overview is intended as a concise view of project activity and relevant releases, rather than repeating the same project/deployment navigation in redundant quick-action cards.
- Frontend source responsibilities are separated:
  - `types/workspace.ts`: domain and API request/response types.
  - `services/api.ts`: shared HTTP client and error normalization.
  - `utils/format.ts`: common date, ID, status, and repository formatting helpers.
  - `hooks/useFoundryWorkspace.ts`: API-backed project/deployment/log state and mutations.
  - `components/`: shared layout, status/feedback/empty-state primitives, project/deployment lists and forms, pipeline timeline, and terminal log view.
  - `pages/`: Overview, Projects, selected-project workspace, and its Overview/Deployments/Build & Runtime tab content.
  - `styles/base.css`, `styles/workspace.css`, and `styles/project-workspace.css`: base styles, shared dashboard component styles, and project workspace styles, composed by `styles.css`.
- State/ownership principle: `App.tsx` coordinates top-level navigation and connects the workspace hook to page components, while project tabs own view-specific UI state such as filters and pagination.
- Correctness detail: changing the selected project clears the previous project's deployments and selected release before loading the new project data, avoiding a misleading cross-project display.
- Verification status at commit time: source files and import references were reviewed through the GitHub connector, but the new composition has **not yet been run through a local TypeScript/Vite production build**. That is the immediate acceptance gate before treating this refactor as complete.


### Modular dashboard automated verification (2026-10-28)
- The latest project-scoped dashboard refactor passed the frontend production build (`tsc --noEmit && vite build`) on Windows.
- The complete backend Maven test suite passed: 59 tests, 0 failures, 0 errors, 0 skipped.
- These results validate type/build compatibility and existing backend regressions, but do not replace manual UI interaction testing. Still to smoke-test: entering a project workspace, switching project tabs, deploying from that project's Deployments tab, selecting a release to open Build & Runtime, search/status filtering and pagination, and changing projects without leaking the previous project's logs/deployments into the new selection.
- Docker lifecycle cleanup and API restart reconciliation also remain acceptance checks before merging PR #13.


### Final Overview navigation build (2026-10-28)
- Pulled current branch head `118d6fc9732553645d6ee69fbf4882bc0ada4965`; `npm run build` passed with `tsc --noEmit` and Vite transforming 48 modules (CSS 45.09 kB, JS 267.84 kB before gzip).
- This last commit only changes frontend overview/navigation wiring and related documentation. The backend suite had passed 59/59 immediately before it; no backend source changed in this final commit.
- Remaining: manual UI smoke test of project-scoped navigation and Docker lifecycle cleanup/restart behavior before PR #13 is merge-ready.
