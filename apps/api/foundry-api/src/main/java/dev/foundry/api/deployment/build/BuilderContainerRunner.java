package dev.foundry.api.deployment.build;

import dev.foundry.api.deployment.log.DeploymentLogService;
import dev.foundry.api.deployment.log.DeploymentLogSeverity;
import dev.foundry.api.deployment.log.DeploymentLogStage;
import dev.foundry.api.deployment.log.DeploymentLogStream;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
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
import java.util.regex.Pattern;

/**
 * Runs repository-defined Maven/npm commands inside disposable Docker builders.
 *
 * The host performs checkout and manifest discovery only. Build commands run in a
 * constrained container using a Docker-managed tmpfs workspace; no host directory
 * or Docker socket is mounted into that container. Only expected build outputs are
 * copied back into the host workspace for the existing runtime packaging contract.
 */
@Component
public class BuilderContainerRunner {
    private static final Logger log = LoggerFactory.getLogger(BuilderContainerRunner.class);
    private static final Pattern VALID_LIMIT = Pattern.compile("[1-9][0-9]*(?:[kKmMgG])?");
    private static final Pattern SAFE_JAR_NAME = Pattern.compile("[A-Za-z0-9_.+-]+\\.jar");
    private static final Set<String> NPM_OUTPUT_DIRECTORIES =
            Set.of("dist", "build", ".next", ".nuxt", "out");
    private static final Duration INSPECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration COPY_TIMEOUT = Duration.ofMinutes(5);

    private final DockerCommandClient docker;
    private final DeploymentLogService logService;
    private final String mavenImage;
    private final String nodeImage;
    private final String memoryLimit;
    private final String workspaceTmpfsLimit;
    private final double cpuLimit;
    private final Duration imagePullTimeout;
    private final boolean enabled;
    private volatile boolean shuttingDown;
    private final Set<UUID> startingDeployments = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BuilderSession> sessions = new ConcurrentHashMap<>();

    public BuilderContainerRunner(
            DockerCommandClient docker,
            DeploymentLogService logService,
            @Value("$" + "{foundry.build.container.maven-image:maven:3.9-eclipse-temurin-25-noble}") String mavenImage,
            @Value("$" + "{foundry.build.container.node-image:node:22-bookworm-slim}") String nodeImage,
            @Value("$" + "{foundry.build.container.memory-limit:3g}") String memoryLimit,
            @Value("$" + "{foundry.build.container.cpu-limit:2.0}") double cpuLimit,
            @Value("$" + "{foundry.build.container.workspace-tmpfs-limit:4g}") String workspaceTmpfsLimit,
            @Value("$" + "{foundry.build.container.image-pull-timeout-seconds:600}") long imagePullTimeoutSeconds,
            @Value("$" + "{foundry.build.container.enabled:false}") boolean enabled) {
        this.docker = docker;
        this.logService = logService;
        this.mavenImage = requireNonBlank(mavenImage, "foundry.build.container.maven-image");
        this.nodeImage = requireNonBlank(nodeImage, "foundry.build.container.node-image");
        this.memoryLimit = validateLimit(memoryLimit, "foundry.build.container.memory-limit");
        this.workspaceTmpfsLimit = validateLimit(
                workspaceTmpfsLimit, "foundry.build.container.workspace-tmpfs-limit");
        if (!Double.isFinite(cpuLimit) || cpuLimit < 0.1 || cpuLimit > 8.0) {
            throw new IllegalArgumentException("foundry.build.container.cpu-limit must be between 0.1 and 8.0.");
        }
        if (imagePullTimeoutSeconds < 30 || imagePullTimeoutSeconds > 3600) {
            throw new IllegalArgumentException(
                    "foundry.build.container.image-pull-timeout-seconds must be between 30 and 3600.");
        }
        this.cpuLimit = cpuLimit;
        this.imagePullTimeout = Duration.ofSeconds(imagePullTimeoutSeconds);
        this.enabled = enabled;
    }

    public void start(UUID deploymentId, Path workspace, String ecosystem)
            throws IOException, InterruptedException {
        if (!enabled) throw new IOException("Isolated build containers are disabled; set foundry.build.container.enabled=true.");
        if (shuttingDown) throw new IOException("Builder runtime is shutting down; new builds are not accepted.");
        if (deploymentId == null || workspace == null || !Files.isDirectory(workspace)) {
            throw new IOException("A deployment ID and existing repository workspace are required for an isolated build.");
        }
        if (sessions.containsKey(deploymentId)) {
            throw new IllegalStateException("An isolated builder is already running for this deployment.");
        }
        if (!startingDeployments.add(deploymentId)) {
            throw new IllegalStateException("An isolated builder is already starting for this deployment.");
        }

        String image = switch (ecosystem == null ? "" : ecosystem.toUpperCase(Locale.ROOT)) {
            case "MAVEN" -> mavenImage;
            case "NPM" -> nodeImage;
            default -> {
                startingDeployments.remove(deploymentId);
                throw new IOException("No isolated builder image is configured for ecosystem: " + ecosystem);
            }
        };
        String container = containerName(deploymentId);
        boolean creationAttempted = false;
        try {
            ensureImage(image, deploymentId);
            if (shuttingDown) throw new IOException("Builder runtime shut down before container startup.");
            append(deploymentId, DeploymentLogSeverity.INFO,
                    "Starting isolated " + ecosystem.toUpperCase(Locale.ROOT) + " builder container.");

            creationAttempted = true;
            DockerCommandClient.Result started = docker.execute(
                    createContainerArguments(deploymentId, container, image),
                    Duration.ofSeconds(45), deploymentId, false);
            requireSuccess(started, "Could not start the isolated build container");
            if (shuttingDown) throw new IOException("Builder runtime shut down while the container was starting.");
            sessions.put(deploymentId,
                    new BuilderSession(container, workspace.toAbsolutePath().normalize(), image));

            String hostSource = workspace.toAbsolutePath().normalize().toString()
                    + (isWindows() ? "\\." : "/.");
            DockerCommandClient.Result copied = docker.execute(
                    List.of("cp", hostSource, container + ":/workspace"),
                    COPY_TIMEOUT, deploymentId, false);
            requireSuccess(copied, "Could not copy the repository into the isolated build container");

            DockerCommandClient.Result removeGitMetadata = docker.execute(
                    List.of("exec", "--user", "0:0", container, "rm", "-rf", "/workspace/.git"),
                    INSPECT_TIMEOUT, deploymentId, false);
            requireSuccess(removeGitMetadata, "Could not remove source-control metadata from the builder workspace");

            DockerCommandClient.Result normalizedWrappers = docker.execute(
                    List.of("exec", "--user", "0:0", container, "find", "/workspace", "-type", "f",
                            "-name", "mvnw", "-exec", "sed", "-i", "s/\\r$//", "{}", "+"),
                    INSPECT_TIMEOUT, deploymentId, false);
            requireSuccess(normalizedWrappers, "Could not normalize Maven wrapper line endings");

            DockerCommandClient.Result executableWrappers = docker.execute(
                    List.of("exec", "--user", "0:0", container, "find", "/workspace", "-type", "f",
                            "-name", "mvnw", "-exec", "chmod", "+x", "{}", "+"),
                    INSPECT_TIMEOUT, deploymentId, false);
            requireSuccess(executableWrappers, "Could not prepare Maven wrapper executable permissions");

            DockerCommandClient.Result permissions = docker.execute(
                    List.of("exec", "--user", "0:0", container, "chmod", "-R", "a+rwX", "/workspace"),
                    COPY_TIMEOUT, deploymentId, false);
            requireSuccess(permissions, "Could not prepare the isolated builder workspace permissions");

            append(deploymentId, DeploymentLogSeverity.INFO,
                    "Repository source copied into a size-limited container tmpfs; no host directory or Docker socket is mounted.");
        } catch (IOException | InterruptedException | RuntimeException failure) {
            sessions.remove(deploymentId);
            if (creationAttempted) removeContainer(container, deploymentId);
            throw failure;
        } finally {
            startingDeployments.remove(deploymentId);
        }
    }

    public void run(
            UUID deploymentId,
            Path workingDirectory,
            List<String> arguments,
            Duration timeout,
            Map<String, String> environment) throws IOException, InterruptedException {
        BuilderSession session = requireSession(deploymentId);
        String containerWorkingDirectory = containerPath(session.workspace(), workingDirectory);
        List<String> translated = translateCommand(arguments);

        List<String> command = new ArrayList<>();
        command.add("exec");
        command.add("--user");
        command.add("10001:10001");
        command.add("--workdir");
        command.add(containerWorkingDirectory);
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IOException("Invalid builder environment variable name.");
            }
            command.add("--env");
            command.add(entry.getKey() + "=" + entry.getValue());
        }
        command.add("--env");
        command.add("HOME=/tmp");
        command.add("--env");
        command.add("MAVEN_OPTS=-Dmaven.repo.local=/tmp/.m2/repository");
        command.add("--env");
        command.add("npm_config_cache=/tmp/.npm");
        command.add(session.container());

        // Run a timeout inside the container so the actual build process is terminated,
        // even if the Docker CLI process is interrupted on the host.
        command.add("timeout");
        command.add("--signal=TERM");
        command.add("--kill-after=5s");
        command.add(Math.max(1, timeout.toSeconds()) + "s");
        command.addAll(translated);

        append(deploymentId, DeploymentLogSeverity.INFO,
                "Running isolated build command: " + String.join(" ", translated));
        DockerCommandClient.Result result = docker.execute(
                command, timeout.plusSeconds(20), deploymentId, true);
        if (result.exitCode() == 124 || result.exitCode() == 137) {
            throw new IOException("Isolated build command exceeded its "
                    + timeout.toSeconds() + "-second timeout: " + String.join(" ", translated));
        }
        requireSuccess(result, "Isolated build command failed");
    }

    /** Copies Maven JAR candidates only; the caller validates their manifests before choosing one. */
    public void copyMavenJarCandidates(UUID deploymentId, Path serviceDirectory)
            throws IOException, InterruptedException {
        BuilderSession session = requireSession(deploymentId);
        Path service = requireInsideWorkspace(session.workspace(), serviceDirectory);
        String targetPath = containerPath(session.workspace(), service.resolve("target"));

        DockerCommandClient.Result targetExists = docker.execute(
                List.of("exec", "--user", "10001:10001", session.container(), "test", "-d", targetPath),
                INSPECT_TIMEOUT, deploymentId, false);
        Path hostTarget = service.resolve("target");
        deleteTree(hostTarget);
        if (targetExists.exitCode() != 0) return;

        DockerCommandClient.Result listing = docker.execute(
                List.of("exec", "--user", "10001:10001", session.container(),
                        "find", targetPath, "-maxdepth", "1", "-type", "f", "-name", "*.jar", "-print"),
                INSPECT_TIMEOUT, deploymentId, false);
        requireSuccess(listing, "Could not enumerate Maven JAR outputs in the isolated builder");

        Files.createDirectories(hostTarget);
        for (String line : listing.output().split("\\R")) {
            String candidatePath = line.trim();
            if (candidatePath.isEmpty()) continue;
            String fileName = Path.of(candidatePath).getFileName().toString();
            if (!SAFE_JAR_NAME.matcher(fileName).matches()) {
                append(deploymentId, DeploymentLogSeverity.WARN,
                        "Skipping Maven output with an unsupported artifact filename.");
                continue;
            }
            DockerCommandClient.Result copied = docker.execute(
                    List.of("cp", session.container() + ":" + targetPath + "/" + fileName,
                            hostTarget.toString()),
                    COPY_TIMEOUT, deploymentId, false);
            requireSuccess(copied, "Could not copy Maven JAR output from the isolated builder");
        }
        append(deploymentId, DeploymentLogSeverity.INFO,
                "Copied Maven JAR candidates only; intermediate classes and dependency caches remain inside the builder.");
    }

    /** Copies only known generated web output directories, not source mutations or dependency caches. */
    public void copyNpmOutputs(UUID deploymentId, Path serviceDirectory)
            throws IOException, InterruptedException {
        BuilderSession session = requireSession(deploymentId);
        Path service = requireInsideWorkspace(session.workspace(), serviceDirectory);
        String containerService = containerPath(session.workspace(), service);

        for (String directory : NPM_OUTPUT_DIRECTORIES) {
            String output = containerService + "/" + directory;
            DockerCommandClient.Result exists = docker.execute(
                    List.of("exec", "--user", "10001:10001", session.container(), "test", "-d", output),
                    INSPECT_TIMEOUT, deploymentId, false);
            if (exists.exitCode() != 0) continue;

            DockerCommandClient.Result notSymlink = docker.execute(
                    List.of("exec", "--user", "10001:10001", session.container(), "test", "!", "-L", output),
                    INSPECT_TIMEOUT, deploymentId, false);
            if (notSymlink.exitCode() != 0) {
                append(deploymentId, DeploymentLogSeverity.WARN,
                        "Skipped generated output directory because its root is a symbolic link: " + directory);
                continue;
            }

            Path hostOutput = service.resolve(directory).normalize();
            if (!hostOutput.startsWith(service)) {
                throw new IOException("Generated output path escaped the selected service directory.");
            }
            deleteTree(hostOutput);
            DockerCommandClient.Result copied = docker.execute(
                    List.of("cp", session.container() + ":" + output, service.toString()),
                    COPY_TIMEOUT, deploymentId, false);
            requireSuccess(copied, "Could not copy generated web output from the isolated builder");
            rejectSymbolicLinks(hostOutput);
        }
        append(deploymentId, DeploymentLogSeverity.INFO,
                "Copied generated web output directories only; source mutations and dependencies remain inside the builder.");
    }

    public void stop(UUID deploymentId) {
        if (deploymentId == null) return;
        BuilderSession session = sessions.remove(deploymentId);
        if (session != null) removeContainer(session.container(), deploymentId);
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${foundry.build.container.cleanup-interval-ms:30000}")
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    public void removeOrphanedBuilderContainersAfterRestart() {
        if (!enabled || shuttingDown) return;
        DockerCommandClient.Result listed;
        try {
            listed = docker.execute(
                    List.of("container", "ls", "--all", "--format={{.Names}}",
                            "--filter", "label=dev.foundry.builder=true"),
                    INSPECT_TIMEOUT, null, false);
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            log.debug("Skipping stale builder cleanup because Docker is unavailable: {}", failure.getMessage());
            return;
        }
        if (listed.exitCode() != 0) {
            log.debug("Skipping stale builder cleanup: {}", safeExcerpt(listed.output()));
            return;
        }

        for (String line : listed.output().split("\\R")) {
            String name = line.trim();
            Optional<UUID> id = deploymentIdFromContainerName(name);
            if (id.isEmpty() || sessions.containsKey(id.get()) || startingDeployments.contains(id.get())) continue;
            log.info("Removing orphaned Foundry builder container {} after control-plane restart.", name);
            removeContainer(name, id.get());
        }
    }

    @PreDestroy
    public void stopAll() {
        shuttingDown = true;
        Set<UUID> ids = ConcurrentHashMap.newKeySet();
        ids.addAll(sessions.keySet());
        ids.addAll(startingDeployments);
        for (UUID deploymentId : ids) {
            if (sessions.containsKey(deploymentId)) stop(deploymentId);
            else removeContainer(containerName(deploymentId), deploymentId);
        }
    }

    List<String> createContainerArguments(UUID deploymentId, String container, String image) {
        return List.of(
                "run", "--detach",
                "--name", container,
                "--label", "dev.foundry.builder=true",
                "--label", "dev.foundry.deployment-id=" + deploymentId,
                "--memory", memoryLimit,
                "--memory-swap", memoryLimit,
                "--cpus", Double.toString(cpuLimit),
                "--pids-limit", "256",
                "--security-opt", "no-new-privileges:true",
                "--cap-drop", "ALL",
                "--init",
                "--network", "bridge",
                "--read-only",
                "--tmpfs", "/tmp:rw,exec,nosuid,size=2g",
                "--tmpfs", "/workspace:rw,exec,nosuid,size=" + workspaceTmpfsLimit,
                "--workdir", "/workspace",
                "--user", "0:0",
                "--entrypoint", "/bin/sh",
                image,
                "-c", "exec sleep infinity");
    }

    static String containerName(UUID deploymentId) {
        return "foundry-builder-" + deploymentId.toString().toLowerCase(Locale.ROOT);
    }

    static Optional<UUID> deploymentIdFromContainerName(String rawName) {
        if (rawName == null || !rawName.startsWith("foundry-builder-")) return Optional.empty();
        return parseUuid(rawName.substring("foundry-builder-".length()));
    }

    private static Optional<UUID> parseUuid(String value) {
        if (value == null) return Optional.empty();
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equalsIgnoreCase(value) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private void ensureImage(String image, UUID deploymentId) throws IOException, InterruptedException {
        DockerCommandClient.Result inspected = docker.execute(
                List.of("image", "inspect", "--format={{.Id}}", image),
                INSPECT_TIMEOUT, deploymentId, false);
        if (inspected.exitCode() == 0) return;

        append(deploymentId, DeploymentLogSeverity.INFO, "Pulling isolated builder image " + image + ".");
        DockerCommandClient.Result pulled = docker.execute(
                List.of("pull", image), imagePullTimeout, deploymentId, true);
        requireSuccess(pulled, "Could not pull isolated builder image " + image);
    }

    private BuilderSession requireSession(UUID deploymentId) throws IOException {
        BuilderSession session = sessions.get(deploymentId);
        if (session == null) {
            throw new IOException("No isolated builder container is active for this deployment.");
        }
        return session;
    }

    static List<String> translateCommand(List<String> arguments) throws IOException {
        if (arguments == null || arguments.isEmpty()) {
            throw new IOException("A non-empty build command is required.");
        }
        List<String> translated = new ArrayList<>(arguments);
        String executable = Path.of(translated.getFirst().replace('\\', '/')).getFileName().toString();
        String normalized = executable.toLowerCase(Locale.ROOT);

        if (normalized.equals("npm.cmd") || normalized.equals("npm")) {
            translated.set(0, "npm");
        } else if (normalized.equals("mvn.cmd") || normalized.equals("mvn")) {
            translated.set(0, "mvn");
        } else if (normalized.equals("mvnw.cmd") || normalized.equals("mvnw")) {
            translated.set(0, "sh");
            translated.add(1, "./mvnw");
        } else {
            throw new IOException("The isolated builder does not allow this command executable: " + executable);
        }
        return List.copyOf(translated);
    }

    private static String containerPath(Path workspace, Path path) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        Path candidate = path.toAbsolutePath().normalize();
        if (!candidate.startsWith(root)) {
            throw new IOException("Build working directory must remain inside the deployment workspace.");
        }
        String relative = root.relativize(candidate).toString().replace('\\', '/');
        return relative.isBlank() ? "/workspace" : "/workspace/" + relative;
    }

    private static Path requireInsideWorkspace(Path workspace, Path path) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        Path candidate = path.toAbsolutePath().normalize();
        if (!candidate.startsWith(root)) {
            throw new IOException("Build output path must remain inside the deployment workspace.");
        }
        return candidate;
    }

    private void removeContainer(String container, UUID deploymentId) {
        // Cleanup must still be attempted if the build thread was interrupted.
        boolean interrupted = Thread.interrupted();
        try {
            try {
                DockerCommandClient.Result removed = docker.execute(
                        List.of("rm", "--force", "--volumes", container), CLEANUP_TIMEOUT, deploymentId, false);
                if (removed.exitCode() != 0 && !isMissingContainer(removed.output())) {
                    log.warn("Builder container cleanup for deployment {} may need manual attention: {}",
                            deploymentId, safeExcerpt(removed.output()));
                }
            } catch (InterruptedException failure) {
                interrupted = true;
                log.warn("Builder container cleanup for deployment {} was interrupted.", deploymentId);
            } catch (IOException failure) {
                log.warn("Builder container cleanup for deployment {} failed: {}", deploymentId, failure.getMessage());
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void requireSuccess(DockerCommandClient.Result result, String message) throws IOException {
        if (result.exitCode() != 0) {
            throw new IOException(message + " (exit code " + result.exitCode() + "): "
                    + safeExcerpt(result.output()));
        }
    }

    private void append(UUID id, DeploymentLogSeverity severity, String message) {
        try {
            logService.append(id, DeploymentLogStage.BUILD,
                    DeploymentLogStream.DEPLOYMENT, severity, message);
        } catch (RuntimeException failure) {
            log.warn("Could not persist builder lifecycle output for deployment {}", id, failure);
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

    private static void rejectSymbolicLinks(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) throw new IOException("Generated output contains a symbolic link.");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file)) {
                    throw new IOException("Generated output contains a symbolic link: " + file.getFileName());
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isMissingContainer(String output) {
        String value = output == null ? "" : output.toLowerCase(Locale.ROOT);
        return value.contains("no such container") || value.contains("no such object")
                || value.contains("container not found");
    }

    private static String requireNonBlank(String value, String property) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(property + " must not be blank.");
        return value.trim();
    }

    private static String validateLimit(String value, String property) {
        String result = requireNonBlank(value, property);
        if (!VALID_LIMIT.matcher(result).matches()) {
            throw new IllegalArgumentException(property + " must be a positive Docker size such as 3g.");
        }
        return result;
    }

    private static String safeExcerpt(String output) {
        String value = output == null ? "" : output.trim();
        return value.length() <= 1200 ? value : value.substring(value.length() - 1200);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private record BuilderSession(String container, Path workspace, String image) {}
}
