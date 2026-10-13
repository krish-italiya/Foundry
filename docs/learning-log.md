# Foundry Learning Log

This log records concepts learned while building Foundry. Add concise entries after each session; prefer experiments and observed behavior over copied definitions.

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
- **Artifact Registry:** Google's registry service used to store the `foundry-web:v1` and `foundry-web:v2` images.
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

## Entry: Project-registration data model (in progress)

### Implemented and verified
- Added `ProjectEntity` with a generated UUID, name, repository URL, and creation timestamp.
- Added `ProjectRepository` extending `JpaRepository<ProjectEntity, UUID>`; Spring Data JPA supplies common persistence operations.
- Added `ProjectCreateRequest` as a Java `record` with `@NotBlank` and `@Size` constraints.
- The Maven Wrapper tests passed after adding these classes.
- The registration API is not implemented yet: there is no service/controller flow saving projects from an HTTP request.

### New terminology
- **DTO (Data Transfer Object):** an object whose purpose is to carry data between components, such as a client request and backend.
- **Java record:** a concise Java type for data-carrying values; the compiler generates its constructor and accessors.
- **Bean Validation:** declarative constraints such as `@NotBlank` and `@Size`; these constraints take effect when validation is invoked, not merely because they appear on a class.
- **Entity:** a class mapped to persisted database data.
- **Primary key:** a field identifying a database record.
- **UUID:** a 128-bit identifier commonly represented as a string; used here instead of a sequential public ID.
- **Repository:** a persistence abstraction used to save and retrieve entities.
- **Spring Data JPA:** a Spring abstraction that provides repository implementations for JPA entities.
- **JPA:** the Java persistence specification used to map Java objects to relational database records.
- **Service layer:** the component where application rules and coordination live between controllers and persistence.
- **Git staging area:** the proposed set of changes to be included in the next commit.
- **Untracked file:** a file Git knows about in the working directory but has not begun tracking.

### Next milestone
Implement project registration end to end: request validation, service logic, persistence through `ProjectRepository`, and a `POST /api/v1/projects` endpoint returning `201 Created`. Add tests for successful creation and invalid inputs. Then add a list/get endpoint. Only after this should we introduce deployment records and the deployment lifecycle.

## Micro-revision format

When the developer has 5–10 spare minutes, use an interactive, practical revision: ask one short recall question at a time, let them answer, correct misconceptions, and occasionally ask them to explain a concept using Foundry's actual flow. Keep it active-recall and project-connected, not a long lecture. Track troublesome concepts here so future revision can revisit them. Don't launch a revision unless the developer asks for one.