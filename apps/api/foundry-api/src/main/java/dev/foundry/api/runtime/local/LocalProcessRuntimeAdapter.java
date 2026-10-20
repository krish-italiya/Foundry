package dev.foundry.api.runtime.local;

import dev.foundry.api.deployment.log.DeploymentLogEntry;
import dev.foundry.api.deployment.log.DeploymentLogService;
import dev.foundry.api.deployment.log.DeploymentLogSeverity;
import dev.foundry.api.deployment.log.DeploymentLogStage;
import dev.foundry.api.deployment.log.DeploymentLogStream;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(
        prefix = "foundry.runtime.local",
        name = "enabled",
        havingValue = "true"
)
public class LocalProcessRuntimeAdapter implements RuntimeAdapter {

    private static final Logger log =
            LoggerFactory.getLogger(LocalProcessRuntimeAdapter.class);

    private final Path sampleJar;
    private final DeploymentLogService logService;

    private final Map<UUID, Process> processes =
            new ConcurrentHashMap<>();

    private final Map<UUID, URI> applicationUrls =
            new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500))
            .build();

    public LocalProcessRuntimeAdapter(
            DeploymentLogService logService,
            @Value("${foundry.runtime.local.sample-jar}") String sampleJarPath) {

        this.logService = logService;
        this.sampleJar = Path.of(sampleJarPath)
                .toAbsolutePath()
                .normalize();
    }

    @Override
    public URI start(UUID deploymentId) throws IOException {
        if (!Files.isRegularFile(sampleJar)) {
            throw new IOException(
                    "Trusted sample JAR not found: " + sampleJar);
        }

        boolean windows = System.getProperty("os.name")
                .toLowerCase(Locale.ROOT)
                .contains("win");

        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                windows ? "java.exe" : "java"
        );

        if (!Files.isRegularFile(javaExecutable)) {
            throw new IOException(
                    "Java executable not found: " + javaExecutable);
        }

        int port = reserveLoopbackPort();

        Process process = new ProcessBuilder(
                javaExecutable.toString(),
                "-jar",
                sampleJar.toString(),
                Integer.toString(port)
        )
                .directory(sampleJar.getParent().toFile())
                .start();

        Process existing = processes.putIfAbsent(deploymentId, process);

        if (existing != null) {
            process.destroyForcibly();
            throw new IllegalStateException(
                    "A runtime process already exists for this deployment.");
        }

        URI url = URI.create("http://127.0.0.1:" + port);
        applicationUrls.put(deploymentId, url);

        Thread.ofVirtual()
                .name("foundry-stdout-" + deploymentId)
                .start(() -> captureOutput(
                        deploymentId,
                        process.getInputStream(),
                        DeploymentLogStream.STDOUT));

        Thread.ofVirtual()
                .name("foundry-stderr-" + deploymentId)
                .start(() -> captureOutput(
                        deploymentId,
                        process.getErrorStream(),
                        DeploymentLogStream.STDERR));

        return url;
    }

    @Override
    public boolean awaitHealthy(
            UUID deploymentId,
            Duration timeout) throws InterruptedException {

        URI url = applicationUrls.get(deploymentId);

        if (url == null) {
            return false;
        }

        URI healthUrl = url.resolve("/health");
        long deadline = System.nanoTime() + timeout.toNanos();

        while (System.nanoTime() < deadline) {
            Process process = processes.get(deploymentId);

            if (process == null || !process.isAlive()) {
                return false;
            }

            try {
                HttpRequest request = HttpRequest.newBuilder(healthUrl)
                        .timeout(Duration.ofMillis(750))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200
                        && response.body() != null
                        && response.body().contains("\"status\":\"UP\"")) {
                    return true;
                }
            } catch (IOException ignored) {
                // The app may still be starting. Retry until the deadline.
            }

            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(
                    deadline - System.nanoTime());

            if (remainingMillis > 0) {
                Thread.sleep(Math.min(200, remainingMillis));
            }
        }

        return false;
    }

    @Override
    public void stop(UUID deploymentId) {
        applicationUrls.remove(deploymentId);
        Process process = processes.remove(deploymentId);

        if (process == null) {
            return;
        }

        process.destroy();

        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void stopAll() {
        for (UUID id : new ArrayList<>(processes.keySet())) {
            stop(id);
        }
    }

    private int reserveLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), 0));
            return socket.getLocalPort();
        }
    }

    private void captureOutput(
            UUID deploymentId,
            InputStream input,
            DeploymentLogStream stream) {

        DeploymentLogSeverity severity =
                stream == DeploymentLogStream.STDERR
                        ? DeploymentLogSeverity.ERROR
                        : DeploymentLogSeverity.INFO;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {

            String line;

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                String message = line.length()
                        > DeploymentLogEntry.MAX_MESSAGE_LENGTH
                        ? line.substring(
                        0,
                        DeploymentLogEntry.MAX_MESSAGE_LENGTH)
                        : line;

                try {
                    logService.append(
                            deploymentId,
                            DeploymentLogStage.RUNTIME,
                            stream,
                            severity,
                            message);
                } catch (RuntimeException exception) {
                    log.warn(
                            "Could not persist runtime output for deployment {}",
                            deploymentId,
                            exception);
                }
            }
        } catch (IOException exception) {
            if (processes.containsKey(deploymentId)) {
                log.debug(
                        "Runtime output stream closed for deployment {}",
                        deploymentId);
            }
        }
    }
}