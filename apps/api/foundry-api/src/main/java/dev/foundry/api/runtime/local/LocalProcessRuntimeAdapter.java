package dev.foundry.api.runtime.local;

import dev.foundry.api.deployment.build.BuiltArtifact;
import dev.foundry.api.deployment.log.DeploymentLogEntry;
import dev.foundry.api.deployment.log.DeploymentLogService;
import dev.foundry.api.deployment.log.DeploymentLogSeverity;
import dev.foundry.api.deployment.log.DeploymentLogStage;
import dev.foundry.api.deployment.log.DeploymentLogStream;
import dev.foundry.api.runtime.RepositoryArtifactRuntime;
import dev.foundry.api.runtime.RuntimeAdapter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(
        prefix = "foundry.runtime.local",
        name = "enabled",
        havingValue = "true"
)
public class LocalProcessRuntimeAdapter implements RepositoryArtifactRuntime {

    private static final Logger log = LoggerFactory.getLogger(LocalProcessRuntimeAdapter.class);

    private final Path sampleJar;
    private final DeploymentLogService logService;
    private final URI applicationBaseUrl;
    private final Map<UUID, Process> processes = new ConcurrentHashMap<>();
    private final Map<UUID, URI> applicationUrls = new ConcurrentHashMap<>();
    private final Map<UUID, String> healthPaths = new ConcurrentHashMap<>();
    private final Map<UUID, String> healthMarkers = new ConcurrentHashMap<>();
    private final Map<UUID, Path> workspaces = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500))
            .build();

    public LocalProcessRuntimeAdapter(
            DeploymentLogService logService,
            @Value("${foundry.runtime.local.sample-jar}") String sampleJarPath,
            @Value("${foundry.runtime.local.application-base-url:http://localhost:8080}") String applicationBaseUrl) {
        this.logService = logService;
        this.sampleJar = Path.of(sampleJarPath).toAbsolutePath().normalize();
        this.applicationBaseUrl = LocalApplicationAddress.parseBaseUrl(applicationBaseUrl);
    }

    /**
     * Returns the loopback target for routing requests to a currently running deployment.
     * It is intentionally not the URL displayed to users.
     */
    public Optional<URI> internalApplicationUrl(UUID deploymentId) {
        return Optional.ofNullable(applicationUrls.get(deploymentId));
    }

    @Override
    public URI start(UUID deploymentId) throws IOException {
        if (!Files.isRegularFile(sampleJar)) {
            throw new IOException("Trusted sample JAR not found: " + sampleJar);
        }
        int port = reserveLoopbackPort();
        Path java = javaExecutable();
        ProcessBuilder builder = new ProcessBuilder(
                java.toString(), "-jar", sampleJar.toString(), Integer.toString(port));
        builder.directory(sampleJar.getParent().toFile());
        return launch(deploymentId, builder, port, "/health", "\"status\":\"UP\"", null);
    }

    @Override
    public URI startArtifact(UUID deploymentId, BuiltArtifact artifact) throws IOException {
        if (artifact == null || artifact.launchMode() == null) {
            throw new IOException("Repository build did not produce a supported runnable artifact.");
        }

        workspaces.put(deploymentId, artifact.workspaceDirectory());
        int port = reserveLoopbackPort();
        List<String> command = new ArrayList<>();
        Path workingDirectory = artifact.serviceDirectory();
        Map<String, String> environment = new java.util.HashMap<>();
        environment.put("CI", "true");
        environment.put("PORT", Integer.toString(port));
        environment.put("HOST", "127.0.0.1");
        environment.put("HOSTNAME", "127.0.0.1");

        switch (artifact.launchMode()) {
            case JAVA_JAR -> {
                if (artifact.artifactPath() == null || !Files.isRegularFile(artifact.artifactPath())) {
                    throw new IOException("Built Java JAR is missing.");
                }
                command.add(javaExecutable().toString());
                command.add("-jar");
                command.add(artifact.artifactPath().toString());
                // Foundry's first plain-Java runtime contract passes its assigned HTTP port as argument one.
                command.add(Integer.toString(port));
            }
            case SPRING_BOOT_JAR -> {
                if (artifact.artifactPath() == null || !Files.isRegularFile(artifact.artifactPath())) {
                    throw new IOException("Built Spring Boot JAR is missing.");
                }
                command.add(javaExecutable().toString());
                command.add("-jar");
                command.add(artifact.artifactPath().toString());
                command.add("--server.address=127.0.0.1");
                command.add("--server.port=" + port);
            }
            case VITE_PREVIEW -> {
                command.add(isWindows() ? "npm.cmd" : "npm");
                command.add("run");
                command.add("preview");
                command.add("--");
                command.add("--host");
                command.add("127.0.0.1");
                command.add("--port");
                command.add(Integer.toString(port));
                command.add("--strictPort");
            }
            case NODE_START -> {
                command.add(isWindows() ? "npm.cmd" : "npm");
                command.add("run");
                command.add("start");
            }
        }

        ProcessBuilder builder = new ProcessBuilder(windowsCommand(command));
        builder.directory(workingDirectory.toFile());
        builder.environment().putAll(environment);
        workspaces.put(deploymentId, artifact.workspaceDirectory());
        try {
            return launch(
                    deploymentId,
                    builder,
                    port,
                    artifact.healthPath(),
                    artifact.expectedHealthMarker(),
                    artifact.workspaceDirectory());
        } catch (IOException | RuntimeException failure) {
            workspaces.remove(deploymentId);
            try {
                deleteTree(artifact.workspaceDirectory());
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private URI launch(
            UUID deploymentId,
            ProcessBuilder builder,
            int port,
            String healthPath,
            String healthMarker,
            Path workspace) throws IOException {
        Process process = builder.start();
        Process existing = processes.putIfAbsent(deploymentId, process);
        if (existing != null) {
            process.destroyForcibly();
            throw new IllegalStateException("A runtime process already exists for this deployment.");
        }

        URI internalUrl = URI.create("http://127.0.0.1:" + port);
        applicationUrls.put(deploymentId, internalUrl);
        healthPaths.put(deploymentId, healthPath == null || healthPath.isBlank() ? "/" : healthPath);
        healthMarkers.put(deploymentId, healthMarker == null ? "" : healthMarker);
        if (workspace != null) workspaces.put(deploymentId, workspace);

        Thread.ofVirtual().name("foundry-stdout-" + deploymentId)
                .start(() -> captureOutput(deploymentId, process.getInputStream(), DeploymentLogStream.STDOUT));
        Thread.ofVirtual().name("foundry-stderr-" + deploymentId)
                .start(() -> captureOutput(deploymentId, process.getErrorStream(), DeploymentLogStream.STDERR));
        return LocalApplicationAddress.applicationUrl(deploymentId, applicationBaseUrl);
    }

    @Override
    public boolean awaitHealthy(UUID deploymentId, Duration timeout) throws InterruptedException {
        URI url = applicationUrls.get(deploymentId);
        if (url == null) return false;
        String path = healthPaths.getOrDefault(deploymentId, "/");
        String marker = healthMarkers.getOrDefault(deploymentId, "");
        URI healthUrl = url.resolve(path.startsWith("/") ? path : "/" + path);
        long deadline = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadline) {
            Process process = processes.get(deploymentId);
            if (process == null || !process.isAlive()) return false;

            try {
                HttpRequest request = HttpRequest.newBuilder(healthUrl)
                        .timeout(Duration.ofMillis(750))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300
                        && (marker.isBlank() || response.body().contains(marker))) {
                    return true;
                }
            } catch (IOException ignored) {
                // The application may still be starting; retry until the deadline.
            }

            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMillis > 0) Thread.sleep(Math.min(200, remainingMillis));
        }
        return false;
    }

    @Override
    public boolean awaitArtifactHealthy(
            UUID deploymentId,
            Duration timeout,
            BuiltArtifact artifact) throws InterruptedException {
        return awaitHealthy(deploymentId, timeout);
    }

    @Override
    public void stop(UUID deploymentId) {
        applicationUrls.remove(deploymentId);
        healthPaths.remove(deploymentId);
        healthMarkers.remove(deploymentId);
        Process process = processes.remove(deploymentId);

        if (process != null) {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            try {
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    process.waitFor(1, TimeUnit.SECONDS);
                }
            } catch (InterruptedException interrupted) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }

        Path workspace = workspaces.remove(deploymentId);
        if (workspace != null) {
            try {
                deleteTree(workspace);
            } catch (IOException cleanupFailure) {
                log.warn("Could not remove repository workspace for deployment {}", deploymentId,
                        cleanupFailure);
            }
        }
    }

    @PreDestroy
    public void stopAll() {
        for (UUID id : new ArrayList<>(processes.keySet())) stop(id);
    }

    private Path javaExecutable() throws IOException {
        boolean windows = isWindows();
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                windows ? "java.exe" : "java");
        if (!Files.isRegularFile(executable)) {
            throw new IOException("Java executable was not found: " + executable);
        }
        return executable;
    }

    private List<String> windowsCommand(List<String> args) {
        if (!isWindows() || args.isEmpty()
                || !(args.getFirst().toLowerCase(Locale.ROOT).endsWith(".cmd")
                || args.getFirst().toLowerCase(Locale.ROOT).endsWith(".bat"))) {
            return args;
        }
        String commandLine = args.stream()
                .map(value -> value.chars().anyMatch(Character::isWhitespace)
                        ? "\"" + value.replace("\"", "") + "\""
                        : value)
                .reduce((left, right) -> left + " " + right)
                .orElseThrow();
        return List.of("cmd.exe", "/d", "/s", "/c", "\"" + commandLine + "\"");
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private int reserveLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            return socket.getLocalPort();
        }
    }

    private void captureOutput(UUID deploymentId, InputStream input, DeploymentLogStream stream) {
        DeploymentLogSeverity severity = stream == DeploymentLogStream.STDERR
                ? DeploymentLogSeverity.ERROR : DeploymentLogSeverity.INFO;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String message = line.length() > DeploymentLogEntry.MAX_MESSAGE_LENGTH
                        ? line.substring(0, DeploymentLogEntry.MAX_MESSAGE_LENGTH) : line;
                try {
                    logService.append(deploymentId, DeploymentLogStage.RUNTIME, stream, severity, message);
                } catch (RuntimeException exception) {
                    log.warn("Could not persist runtime output for deployment {}", deploymentId, exception);
                }
            }
        } catch (IOException exception) {
            if (processes.containsKey(deploymentId)) {
                log.debug("Runtime output stream closed for deployment {}", deploymentId);
            }
        }
    }

    private static void deleteTree(Path target) throws IOException {
        if (target == null || !Files.exists(target)) return;
        Files.walkFileTree(target, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(
                    Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(
                    Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.deleteIfExists(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
}
