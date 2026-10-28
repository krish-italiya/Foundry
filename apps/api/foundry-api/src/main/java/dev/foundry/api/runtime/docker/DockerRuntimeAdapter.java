package dev.foundry.api.runtime.docker;

import dev.foundry.api.deployment.build.BuiltArtifact;
import dev.foundry.api.deployment.log.DeploymentLogEntry;
import dev.foundry.api.deployment.log.DeploymentLogService;
import dev.foundry.api.deployment.log.DeploymentLogSeverity;
import dev.foundry.api.deployment.log.DeploymentLogStage;
import dev.foundry.api.deployment.log.DeploymentLogStream;
import dev.foundry.api.runtime.ManagedRepositoryArtifactRuntime;
import dev.foundry.api.runtime.local.LocalApplicationAddress;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(prefix = "foundry.runtime.docker", name = "enabled", havingValue = "true")
public class DockerRuntimeAdapter implements ManagedRepositoryArtifactRuntime {

    private static final Logger log = LoggerFactory.getLogger(DockerRuntimeAdapter.class);
    private static final int CONTAINER_PORT = 8080;
    private static final int MAX_CAPTURED_COMMAND_OUTPUT = 64 * 1024;
    private static final Duration INSPECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Pattern VALID_MEMORY_LIMIT = Pattern.compile("[1-9][0-9]*(?:[kKmMgG])?");
    private static final Set<String> SKIP_DIRECTORIES = Set.of(
            ".git", "node_modules", ".foundry", ".idea", ".gradle", ".venv", "target");
    private static final Set<String> SENSITIVE_FILES = Set.of(".npmrc", ".env");

    private final DeploymentLogService logService;
    private final String dockerExecutable;
    private final String memoryLimit;
    private final double cpuLimit;
    private final Duration buildTimeout;
    private final Path contextRoot;
    private final Path buildWorkspaceRoot;
    private final URI applicationBaseUrl;
    private final Map<UUID, String> containers = new ConcurrentHashMap<>();
    private final Map<UUID, URI> applicationUrls = new ConcurrentHashMap<>();
    private final Map<UUID, Process> logFollowers = new ConcurrentHashMap<>();
    private final Set<UUID> startingDeployments = ConcurrentHashMap.newKeySet();
    private final Set<UUID> cleanupPending = ConcurrentHashMap.newKeySet();
    private volatile boolean shuttingDown;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public DockerRuntimeAdapter(
            DeploymentLogService logService,
            @Value("${foundry.runtime.docker.command:docker}") String dockerExecutable,
            @Value("${foundry.runtime.docker.memory-limit:512m}") String memoryLimit,
            @Value("${foundry.runtime.docker.cpu-limit:1.0}") double cpuLimit,
            @Value("${foundry.runtime.docker.build-timeout-seconds:600}") long buildTimeoutSeconds,
            @Value("${foundry.runtime.docker.context-root:.foundry/docker-contexts}") String contextRoot,
            @Value("${foundry.build.workspace-root:.foundry/workspaces}") String buildWorkspaceRoot,
            @Value("${foundry.runtime.local.application-base-url:http://localhost:8080}") String applicationBaseUrl) {
        this.logService = logService;
        this.dockerExecutable = dockerExecutable == null || dockerExecutable.isBlank() ? "docker" : dockerExecutable;
        this.memoryLimit = memoryLimit == null ? "" : memoryLimit.trim();
        if (!VALID_MEMORY_LIMIT.matcher(this.memoryLimit).matches()) {
            throw new IllegalArgumentException(
                    "foundry.runtime.docker.memory-limit must be a positive Docker memory value such as 512m.");
        }
        if (!Double.isFinite(cpuLimit) || cpuLimit < 0.1 || cpuLimit > 8.0) {
            throw new IllegalArgumentException("foundry.runtime.docker.cpu-limit must be between 0.1 and 8.0.");
        }
        if (buildTimeoutSeconds < 30 || buildTimeoutSeconds > 3600) {
            throw new IllegalArgumentException(
                    "foundry.runtime.docker.build-timeout-seconds must be between 30 and 3600.");
        }
        this.cpuLimit = cpuLimit;
        this.buildTimeout = Duration.ofSeconds(buildTimeoutSeconds);
        this.contextRoot = Path.of(contextRoot).toAbsolutePath().normalize();
        this.buildWorkspaceRoot = Path.of(buildWorkspaceRoot).toAbsolutePath().normalize();
        this.applicationBaseUrl = LocalApplicationAddress.parseBaseUrl(applicationBaseUrl);
    }

    @Override
    public URI start(UUID deploymentId) throws IOException {
        throw new IOException(
                "Docker runtime requires a repository-built artifact. Enable foundry.build.enabled=true; "
                        + "prebuilt sample mode is not supported by this runtime.");
    }

    @Override
    public boolean awaitHealthy(UUID deploymentId, Duration timeout) {
        return false;
    }

    @Override
    public URI startArtifact(UUID deploymentId, BuiltArtifact artifact) throws IOException {
        if (shuttingDown) throw new IOException("Docker runtime is shutting down; new deployments are not accepted.");
        if (deploymentId == null || artifact == null || artifact.launchMode() == null) {
            throw new IOException("A deployment ID and supported built artifact are required.");
        }
        if (!startingDeployments.add(deploymentId)) {
            throw new IllegalStateException("Docker startup is already in progress for this deployment.");
        }
        if (shuttingDown) {
            startingDeployments.remove(deploymentId);
            throw new IOException("Docker runtime is shutting down; new deployments are not accepted.");
        }
        if (containers.containsKey(deploymentId)) {
            startingDeployments.remove(deploymentId);
            throw new IllegalStateException("A Docker container is already tracked for this deployment.");
        }

        Path context = null;
        String image = imageName(deploymentId);
        String container = containerName(deploymentId);
        try {
            context = createRuntimeContext(deploymentId, artifact);
            runDockerCommand(List.of(
                    "build", "--progress=plain",
                    "--label", "dev.foundry.managed=true",
                    "--label", "dev.foundry.deployment-id=" + deploymentId,
                    "--tag", image,
                    "--file", context.resolve("Dockerfile").toString(), context.toString()),
                    buildTimeout, deploymentId, DeploymentLogStage.BUILD, true, true);
            if (shuttingDown) throw new IOException("Docker runtime shut down while the image was building.");

            int hostPort = reserveLoopbackPort();
            runDockerCommand(List.of(
                    "run", "--detach",
                    "--name", container,
                    "--label", "dev.foundry.managed=true",
                    "--label", "dev.foundry.deployment-id=" + deploymentId,
                    "--publish", "127.0.0.1:" + hostPort + ":" + CONTAINER_PORT,
                    "--memory", memoryLimit,
                    "--memory-swap", memoryLimit,
                    "--cpus", Double.toString(cpuLimit),
                    "--pids-limit", "128",
                    "--security-opt", "no-new-privileges:true",
                    "--cap-drop", "ALL",
                    "--init",
                    "--restart", "no",
                    "--network", "bridge",
                    "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
                    "--env", "PORT=8080",
                    "--env", "HOST=0.0.0.0",
                    "--env", "HOSTNAME=0.0.0.0",
                    image),
                    Duration.ofSeconds(30), deploymentId, DeploymentLogStage.STARTUP, true, true);
            if (shuttingDown) throw new IOException("Docker runtime shut down while the container was starting.");

            containers.put(deploymentId, container);
            cleanupPending.remove(deploymentId);
            applicationUrls.put(deploymentId, URI.create("http://127.0.0.1:" + hostPort));
            startLogFollower(deploymentId, container);
            appendLog(deploymentId, DeploymentLogStage.STARTUP, DeploymentLogStream.DEPLOYMENT,
                    DeploymentLogSeverity.INFO,
                    "Started Docker container " + container + " with loopback-only port publishing, "
                            + memoryLimit + " memory, " + cpuLimit + " CPU, and a 128-process limit.");
            return LocalApplicationAddress.applicationUrl(deploymentId, applicationBaseUrl);
        } catch (IOException | RuntimeException failure) {
            stop(deploymentId);
            throw failure;
        } finally {
            startingDeployments.remove(deploymentId);
            if (context != null) deleteTreeQuietly(context, "runtime build context");
            // The generated image has the required files; the checkout is disposable after packaging.
            deleteTreeQuietly(artifact.workspaceDirectory(), "repository build workspace");
        }
    }

    @Override
    public boolean awaitArtifactHealthy(
            UUID deploymentId, Duration timeout, BuiltArtifact artifact) throws InterruptedException {
        URI baseUrl = applicationUrls.get(deploymentId);
        if (baseUrl == null) return false;
        String path = artifact == null || artifact.healthPath() == null || artifact.healthPath().isBlank()
                ? "/" : artifact.healthPath();
        String marker = artifact == null || artifact.expectedHealthMarker() == null
                ? "" : artifact.expectedHealthMarker();
        URI healthUrl = baseUrl.resolve(path.startsWith("/") ? path : "/" + path);
        long deadline = System.nanoTime() + timeout.toNanos();
        int attempts = 0;

        while (System.nanoTime() < deadline) {
            attempts++;
            try {
                HttpRequest request = HttpRequest.newBuilder(healthUrl)
                        .timeout(Duration.ofSeconds(1)).GET().build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300
                        && (marker.isBlank() || response.body().contains(marker))) {
                    return true;
                }
            } catch (IOException ignored) {
                // A container may need several attempts before its HTTP service is ready.
            }
            if (attempts % 5 == 0) {
                Optional<Boolean> alive = processAlive(deploymentId);
                if (alive.isEmpty() || !alive.get()) return false;
            }
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining > 0) Thread.sleep(Math.min(250, remaining));
        }
        return false;
    }

    @Override
    public Optional<URI> internalApplicationUrl(UUID deploymentId) {
        return Optional.ofNullable(applicationUrls.get(deploymentId));
    }

    @Override
    public Optional<Boolean> processAlive(UUID deploymentId) {
        if (cleanupPending.contains(deploymentId)) {
            // Cleanup was attempted but Docker did not confirm removal. Keep the prior
            // persisted state until retry resolves the uncertainty; don't call it crashed.
            return Optional.of(true);
        }
        String container = containers.get(deploymentId);
        if (container == null) return Optional.empty();
        try {
            CommandResult result = runDockerCommand(
                    List.of("inspect", "--format={{.State.Running}}", container),
                    INSPECT_TIMEOUT, null, null, false, false);
            if (result.exitCode() == 0) return Optional.of(Boolean.parseBoolean(result.output().trim()));
            if (dockerDaemonUnavailable(result.output())) {
                log.warn("Docker daemon unavailable while checking deployment {}", deploymentId);
                return Optional.of(true); // Retry next poll instead of falsely marking every app crashed.
            }
            return Optional.of(false);
        } catch (IOException unavailable) {
            log.warn("Could not inspect Docker runtime for deployment {}: {}", deploymentId, unavailable.getMessage());
            return Optional.of(true);
        }
    }

    @Override
    public void stop(UUID deploymentId) {
        if (deploymentId == null) return;
        applicationUrls.remove(deploymentId);
        stopLogFollower(deploymentId);

        String container = containers.getOrDefault(deploymentId, containerName(deploymentId));
        boolean removed = removeContainer(container, deploymentId);
        if (removed) {
            containers.remove(deploymentId, container);
            cleanupPending.remove(deploymentId);
            deleteImageQuietly(imageName(deploymentId));
        } else {
            // Keep ownership and a retry marker until Docker confirms the container is gone.
            cleanupPending.add(deploymentId);
        }

        // A concurrent build may still be reading its context. Its finally block owns
        // workspace deletion once startup exits; the next startup sweeper handles a hard crash.
        if (!startingDeployments.contains(deploymentId)) {
            deleteTreeQuietly(contextRoot.resolve(deploymentId.toString()).normalize(), "runtime build context");
            deleteTreeQuietly(buildWorkspaceRoot.resolve(deploymentId.toString()).normalize(),
                    "repository build workspace");
        }
    }

    private void stopLogFollower(UUID deploymentId) {
        Process follower = logFollowers.remove(deploymentId);
        if (follower == null || !follower.isAlive()) return;
        follower.destroy();
        try {
            if (!follower.waitFor(1, TimeUnit.SECONDS)) follower.destroyForcibly();
        } catch (InterruptedException interrupted) {
            follower.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private boolean removeContainer(String container, UUID deploymentId) {
        try {
            CommandResult result = runDockerCommand(
                    List.of("rm", "--force", container), Duration.ofSeconds(15),
                    null, null, false, false);
            if (result.exitCode() == 0 || missingContainer(result.output())) return true;
            log.warn("Docker container cleanup for deployment {} will be retried: {}",
                    deploymentId, safeExcerpt(result.output()));
            return false;
        } catch (IOException failure) {
            log.warn("Docker container cleanup for deployment {} will be retried: {}",
                    deploymentId, failure.getMessage());
            return false;
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void cleanupAfterRestart() {
        reconcileOrphanedDockerResources();
        cleanupStaleLocalDirectories(contextRoot, "runtime build context");
        cleanupStaleLocalDirectories(buildWorkspaceRoot, "repository build workspace");
    }

    /**
     * Periodically retries cleanup when Docker Desktop was temporarily unavailable.
     * Only UUID-named Foundry resources not owned by this process are considered orphans.
     */
    @Scheduled(fixedDelayString = "${foundry.runtime.docker.cleanup-interval-ms:30000}")
    public void retryPendingCleanupAndRemoveOrphans() {
        for (UUID deploymentId : List.copyOf(cleanupPending)) stop(deploymentId);
        reconcileOrphanedDockerResources();
    }

    void reconcileOrphanedDockerResources() {
        CommandResult containersResult;
        try {
            containersResult = runDockerCommand(
                    List.of("container", "ls", "--all", "--format={{.ID}}|{{.Names}}",
                            "--filter", "name=foundry-deployment-"),
                    Duration.ofSeconds(15), null, null, false, false);
        } catch (IOException unavailable) {
            log.debug("Skipping orphan container reconciliation because Docker is unavailable: {}",
                    unavailable.getMessage());
            return;
        }
        if (containersResult.exitCode() != 0) {
            log.debug("Skipping orphan container reconciliation: {}", safeExcerpt(containersResult.output()));
            return;
        }

        for (String line : containersResult.output().split("\\R")) {
            int separator = line.indexOf('|');
            if (separator < 1 || separator == line.length() - 1) continue;
            String name = line.substring(separator + 1).trim();
            Optional<UUID> parsedId = deploymentIdFromContainerName(name);
            if (parsedId.isEmpty()) continue;
            UUID deploymentId = parsedId.get();
            if (isOwnedByThisProcess(deploymentId)) continue;
            log.info("Removing orphaned Foundry container {} after control-plane restart.", name);
            stop(deploymentId);
        }

        // Clean image tags left behind if the API stopped between image build and container start.
        CommandResult imagesResult;
        try {
            imagesResult = runDockerCommand(
                    List.of("image", "ls", "--all", "--format={{.Repository}}:{{.Tag}}",
                            "--filter", "reference=foundry-deployment-*"),
                    Duration.ofSeconds(15), null, null, false, false);
        } catch (IOException unavailable) {
            log.debug("Skipping orphan image reconciliation because Docker is unavailable: {}",
                    unavailable.getMessage());
            return;
        }
        if (imagesResult.exitCode() != 0) {
            log.debug("Skipping orphan image reconciliation: {}", safeExcerpt(imagesResult.output()));
            return;
        }

        for (String line : imagesResult.output().split("\\R")) {
            String reference = line.trim();
            int colon = reference.lastIndexOf(':');
            if (colon <= 0) continue;
            String repository = reference.substring(0, colon);
            Optional<UUID> parsedId = deploymentIdFromContainerName(repository);
            if (parsedId.isEmpty()) continue;
            UUID deploymentId = parsedId.get();
            if (isOwnedByThisProcess(deploymentId) || cleanupPending.contains(deploymentId)) continue;
            deleteImageQuietly(imageName(deploymentId));
        }
    }

    private boolean isOwnedByThisProcess(UUID deploymentId) {
        return startingDeployments.contains(deploymentId)
                || containers.containsKey(deploymentId)
                || cleanupPending.contains(deploymentId);
    }

    private void cleanupStaleLocalDirectories(Path root, String description) {
        if (!Files.isDirectory(root)) return;
        try (var entries = Files.list(root)) {
            entries.filter(Files::isDirectory).forEach(path -> {
                Optional<UUID> id = parseUuid(path.getFileName().toString());
                if (id.isEmpty() || isOwnedByThisProcess(id.get())) return;
                deleteTreeQuietly(path, description);
            });
        } catch (IOException failure) {
            log.warn("Could not scan stale {} directories under {}: {}", description, root, failure.getMessage());
        }
    }

    static Optional<UUID> deploymentIdFromContainerName(String rawName) {
        if (rawName == null) return Optional.empty();
        String name = rawName.startsWith("/") ? rawName.substring(1) : rawName;
        String prefix = "foundry-deployment-";
        if (!name.startsWith(prefix)) return Optional.empty();
        return parseUuid(name.substring(prefix.length()));
    }

    private static Optional<UUID> parseUuid(String value) {
        if (value == null) return Optional.empty();
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equalsIgnoreCase(value) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException invalidUuid) {
            return Optional.empty();
        }
    }

    private static boolean missingContainer(String output) {
        String text = output == null ? "" : output.toLowerCase(Locale.ROOT);
        return text.contains("no such container") || text.contains("no such object")
                || text.contains("container not found");
    }

    @PreDestroy
    public void stopAll() {
        shuttingDown = true;
        Set<UUID> ids = ConcurrentHashMap.newKeySet();
        ids.addAll(containers.keySet());
        ids.addAll(cleanupPending);
        ids.addAll(startingDeployments);
        for (UUID deploymentId : ids) stop(deploymentId);
    }

    /** Deterministic, repository-independent Dockerfile generation used by focused contract tests. */
    static String dockerfileFor(BuiltArtifact artifact, Path stagedApp) throws IOException {
        return switch (artifact.launchMode()) {
            case SPRING_BOOT_JAR -> """
                    FROM eclipse-temurin:25-jre
                    WORKDIR /app
                    COPY --chown=10001:10001 app.jar /app/app.jar
                    USER 10001:10001
                    EXPOSE 8080
                    ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar","--server.address=0.0.0.0","--server.port=8080"]
                    """;
            case JAVA_JAR -> """
                    FROM eclipse-temurin:25-jre
                    WORKDIR /app
                    COPY --chown=10001:10001 app.jar /app/app.jar
                    USER 10001:10001
                    EXPOSE 8080
                    ENTRYPOINT ["java","-jar","/app/app.jar","8080"]
                    """;
            case NODE_START -> {
                if (!Files.isRegularFile(stagedApp.resolve("package.json"))) {
                    throw new IOException("Node runtime packaging requires package.json in the selected service.");
                }
                boolean hasLockfile = Files.isRegularFile(stagedApp.resolve("package-lock.json"))
                        || Files.isRegularFile(stagedApp.resolve("npm-shrinkwrap.json"));
                String install = hasLockfile ? "RUN npm ci --omit=dev" : "RUN npm install --omit=dev";
                yield """
                        FROM node:22-alpine
                        WORKDIR /app
                        ENV NODE_ENV=production PORT=8080 HOST=0.0.0.0 HOSTNAME=0.0.0.0
                        COPY --chown=node:node app/ ./
                        RUN chown node:node /app
                        USER node
                        %s
                        EXPOSE 8080
                        CMD ["npm","run","start"]
                        """.formatted(install);
            }
            case VITE_PREVIEW -> """
                    FROM nginxinc/nginx-unprivileged:1.27-alpine
                    COPY app/ /usr/share/nginx/html/
                    EXPOSE 8080
                    """;
        };
    }

    private Path createRuntimeContext(UUID deploymentId, BuiltArtifact artifact) throws IOException {
        Files.createDirectories(contextRoot);
        Path context = contextRoot.resolve(deploymentId.toString()).normalize();
        if (!context.startsWith(contextRoot)) throw new IOException("Docker context escaped its configured root.");
        deleteTree(context);
        Path stagedApp = context.resolve("app");
        Files.createDirectories(stagedApp);

        switch (artifact.launchMode()) {
            case SPRING_BOOT_JAR, JAVA_JAR -> {
                if (artifact.artifactPath() == null || !Files.isRegularFile(artifact.artifactPath())) {
                    throw new IOException("Built Java artifact is missing.");
                }
                Files.copy(artifact.artifactPath(), context.resolve("app.jar"));
            }
            case NODE_START -> copyApplicationTree(artifact.serviceDirectory(), stagedApp);
            case VITE_PREVIEW -> {
                Path dist = artifact.serviceDirectory().resolve("dist");
                if (!Files.isDirectory(dist)) throw new IOException("Vite runtime needs the built dist/ directory.");
                copyApplicationTree(dist, stagedApp);
            }
        }
        Files.writeString(context.resolve("Dockerfile"), dockerfileFor(artifact, stagedApp), StandardCharsets.UTF_8);
        return context;
    }

    private void copyApplicationTree(Path source, Path destination) throws IOException {
        Path root = source.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("Runtime source directory is missing: " + root);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Path relative = root.relativize(directory);
                if (!relative.toString().isEmpty() && containsSkippedDirectory(relative)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(destination.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE;
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (SENSITIVE_FILES.contains(name) || name.startsWith(".env.")) return FileVisitResult.CONTINUE;
                Path target = destination.resolve(root.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean containsSkippedDirectory(Path relative) {
        for (Path part : relative) {
            if (SKIP_DIRECTORIES.contains(part.toString().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private void startLogFollower(UUID deploymentId, String container) {
        try {
            Process follower = new ProcessBuilder(dockerExecutable, "logs", "--follow", container)
                    .redirectErrorStream(true).start();
            if (logFollowers.putIfAbsent(deploymentId, follower) != null) {
                follower.destroyForcibly();
                return;
            }
            Thread.ofVirtual().name("foundry-docker-logs-" + deploymentId)
                    .start(() -> captureContainerLogs(deploymentId, follower));
        } catch (IOException failure) {
            log.warn("Container {} is running, but log streaming could not start: {}", container, failure.getMessage());
            appendLog(deploymentId, DeploymentLogStage.RUNTIME, DeploymentLogStream.DEPLOYMENT,
                    DeploymentLogSeverity.WARN, "Container log streaming could not start.");
        }
    }

    private void captureContainerLogs(UUID deploymentId, Process follower) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(follower.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) appendLog(deploymentId, DeploymentLogStage.RUNTIME,
                        DeploymentLogStream.STDOUT, DeploymentLogSeverity.INFO, bounded(line));
            }
        } catch (IOException failure) {
            if (follower.isAlive()) log.debug("Docker log follower ended for {}", deploymentId, failure);
        }
    }

    CommandResult runDockerCommand(
            List<String> args, Duration timeout, UUID deploymentId,
            DeploymentLogStage stage, boolean logOutput, boolean requireSuccess) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(dockerExecutable);
        command.addAll(args);
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException unavailable) {
            throw new IOException("Could not start Docker CLI. Install Docker Desktop and ensure 'docker' is on PATH.",
                    unavailable);
        }

        StringBuilder captured = new StringBuilder();
        Thread reader = Thread.ofVirtual().name("foundry-docker-command-output").start(() -> {
            try (BufferedReader input = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    synchronized (captured) {
                        if (captured.length() < MAX_CAPTURED_COMMAND_OUTPUT) captured.append(line).append('\n');
                    }
                    if (logOutput && deploymentId != null && stage != null && !line.isBlank()) {
                        appendLog(deploymentId, stage, DeploymentLogStream.DEPLOYMENT,
                                DeploymentLogSeverity.INFO, bounded(line));
                    }
                }
            } catch (IOException ignored) {
                // The process closes this stream on exit.
            }
        });

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Docker command was interrupted.", interrupted);
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            reader.join(2000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        String output;
        synchronized (captured) {
            output = captured.toString();
        }
        if (!finished) throw new IOException(
                "Docker command timed out after " + timeout.toSeconds() + " seconds: " + safeExcerpt(output));
        int exitCode = process.exitValue();
        if (requireSuccess && exitCode != 0) {
            throw new IOException("Docker command failed with exit code " + exitCode + ": " + safeExcerpt(output));
        }
        return new CommandResult(exitCode, output);
    }

    private int reserveLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            return socket.getLocalPort();
        }
    }

    private void deleteImageQuietly(String image) {
        try {
            CommandResult result = runDockerCommand(List.of("image", "rm", "--force", image),
                    Duration.ofSeconds(30), null, null, false, false);
            if (result.exitCode() != 0
                    && !result.output().toLowerCase(Locale.ROOT).contains("no such image")
                    && !dockerDaemonUnavailable(result.output())) {
                log.debug("Docker image cleanup for {} returned: {}", image, safeExcerpt(result.output()));
            }
        } catch (IOException failure) {
            log.debug("Could not remove Docker image {}: {}", image, failure.getMessage());
        }
    }

    private void deleteTreeQuietly(Path path, String description) {
        try {
            deleteTree(path);
        } catch (IOException failure) {
            log.warn("Could not remove {} at {}: {}", description, path, failure.getMessage());
        }
    }

    private static void deleteTree(Path target) throws IOException {
        if (target == null || !Files.exists(target)) return;
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void appendLog(UUID id, DeploymentLogStage stage, DeploymentLogStream stream,
                           DeploymentLogSeverity severity, String message) {
        if (message == null || message.isBlank()) return;
        try {
            logService.append(id, stage, stream, severity, bounded(message));
        } catch (RuntimeException failure) {
            log.warn("Could not persist Docker output for deployment {}", id, failure);
        }
    }

    private static String imageName(UUID id) {
        return "foundry-deployment-" + id.toString().toLowerCase(Locale.ROOT);
    }

    private static String containerName(UUID id) {
        return imageName(id);
    }

    private static boolean dockerDaemonUnavailable(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return text.contains("cannot connect to the docker daemon")
                || text.contains("error during connect")
                || text.contains("is the docker daemon running")
                || text.contains("failed to connect to the docker api");
    }

    private static String bounded(String value) {
        String text = value == null ? "" : value;
        int max = DeploymentLogEntry.MAX_MESSAGE_LENGTH;
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String safeExcerpt(String value) {
        String text = value == null ? "" : value.trim();
        int max = 1200;
        return text.length() <= max ? text : text.substring(text.length() - max);
    }

    record CommandResult(int exitCode, String output) {
    }
}
