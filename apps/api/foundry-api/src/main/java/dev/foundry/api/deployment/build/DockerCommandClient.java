package dev.foundry.api.deployment.build;

import dev.foundry.api.deployment.log.DeploymentLogService;
import dev.foundry.api.deployment.log.DeploymentLogSeverity;
import dev.foundry.api.deployment.log.DeploymentLogStage;
import dev.foundry.api.deployment.log.DeploymentLogStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Executes Docker CLI argument lists without going through a shell. */
@Component
public class DockerCommandClient {
    private static final Logger log = LoggerFactory.getLogger(DockerCommandClient.class);
    private static final int MAX_CAPTURED_OUTPUT = 64 * 1024;
    private static final int MAX_LOG_LINE_LENGTH = 4000;
    private static final long MAX_FORWARDED_LOG_CHARACTERS = 2L * 1024L * 1024L;

    private final DeploymentLogService logService;
    private final String dockerExecutable;

    public DockerCommandClient(
            DeploymentLogService logService,
            @Value("$" + "{foundry.runtime.docker.command:docker}") String dockerExecutable) {
        this.logService = logService;
        this.dockerExecutable = dockerExecutable == null || dockerExecutable.isBlank()
                ? "docker" : dockerExecutable;
    }

    public Result execute(
            List<String> arguments,
            Duration timeout,
            UUID deploymentId,
            boolean forwardOutput) throws IOException, InterruptedException {
        return executeInternal(arguments, timeout, deploymentId, forwardOutput, null, null);
    }

    /** Streams binary input into a Docker command's stdin and closes the stream when finished. */
    public Result executeWithInput(
            List<String> arguments,
            InputStream input,
            Duration timeout,
            UUID deploymentId,
            boolean forwardOutput) throws IOException, InterruptedException {
        if (input == null) throw new IllegalArgumentException("Docker command input cannot be null.");
        return executeInternal(arguments, timeout, deploymentId, forwardOutput, input, null);
    }

    /** Redirects a Docker command's binary stdout to a file; only stderr is captured as text. */
    public Result executeToFile(
            List<String> arguments,
            Path outputFile,
            Duration timeout,
            UUID deploymentId,
            boolean forwardOutput) throws IOException, InterruptedException {
        if (outputFile == null) throw new IllegalArgumentException("Docker command output file cannot be null.");
        Path parent = outputFile.toAbsolutePath().normalize().getParent();
        if (parent == null || !java.nio.file.Files.isDirectory(parent)) {
            throw new IOException("Docker command output directory does not exist.");
        }
        return executeInternal(arguments, timeout, deploymentId, forwardOutput, null, outputFile);
    }

    private Result executeInternal(
            List<String> arguments,
            Duration timeout,
            UUID deploymentId,
            boolean forwardOutput,
            InputStream standardInput,
            Path standardOutputFile) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(dockerExecutable);
        command.addAll(arguments);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        if (standardOutputFile == null) {
            processBuilder.redirectErrorStream(true);
        } else {
            processBuilder.redirectOutput(standardOutputFile.toFile());
        }

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException unavailable) {
            throw new IOException(
                    "Could not start Docker CLI. Install Docker Desktop and ensure 'docker' is on PATH.",
                    unavailable);
        }

        StringBuilder captured = new StringBuilder();
        java.util.concurrent.atomic.AtomicLong forwardedCharacters = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicBoolean outputTruncated = new java.util.concurrent.atomic.AtomicBoolean();
        AtomicReference<IOException> inputFailure = new AtomicReference<>();

        Thread writer = null;
        if (standardInput != null) {
            writer = Thread.ofVirtual().name("foundry-docker-command-input").start(() -> {
                try (InputStream source = standardInput;
                     OutputStream destination = process.getOutputStream()) {
                    source.transferTo(destination);
                } catch (IOException failure) {
                    inputFailure.set(failure);
                    log.debug("Could not stream input to Docker command.", failure);
                }
            });
        } else {
            try {
                process.getOutputStream().close();
            } catch (IOException failure) {
                log.debug("Could not close unused Docker command input.", failure);
            }
        }

        var commandOutput = standardOutputFile == null
                ? process.getInputStream()
                : process.getErrorStream();
        Thread reader = Thread.ofVirtual().name("foundry-docker-command-output").start(() -> {
            try (BufferedReader input = new BufferedReader(
                    new InputStreamReader(commandOutput, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    synchronized (captured) {
                        if (captured.length() < MAX_CAPTURED_OUTPUT) captured.append(line).append('\n');
                    }
                    if (forwardOutput && deploymentId != null && !line.isBlank()) {
                        long total = forwardedCharacters.addAndGet(line.length() + 1L);
                        if (total <= MAX_FORWARDED_LOG_CHARACTERS) {
                            append(deploymentId, bounded(line));
                        } else {
                            outputTruncated.set(true);
                        }
                    }
                }
            } catch (IOException failure) {
                log.debug("Docker command output stream closed.", failure);
            }
        });

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
            if (writer != null) writer.interrupt();
            Thread.currentThread().interrupt();
            throw interrupted;
        }

        if (!finished) {
            process.destroyForcibly();
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
            try {
                process.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (writer != null) {
            try {
                writer.join(2000);
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
        synchronized (captured) { output = captured.toString(); }
        if (outputTruncated.get() && deploymentId != null) {
            append(deploymentId, "Docker command output truncated after the 2 MiB per-command log limit.");
        }
        if (!finished) {
            throw new IOException("Docker command timed out after " + timeout.toSeconds()
                    + " seconds: " + safeExcerpt(output));
        }
        IOException failedInput = inputFailure.get();
        int exitCode = process.exitValue();
        if (failedInput != null && exitCode == 0) {
            throw new IOException("Could not stream binary input into Docker command.", failedInput);
        }
        return new Result(exitCode, output);
    }

    private void append(UUID deploymentId, String message) {
        try {
            logService.append(deploymentId, DeploymentLogStage.BUILD,
                    DeploymentLogStream.STDOUT, DeploymentLogSeverity.INFO, message);
        } catch (RuntimeException failure) {
            log.warn("Could not persist Docker build output for deployment {}", deploymentId, failure);
        }
    }

    private static String bounded(String value) {
        return value.length() <= MAX_LOG_LINE_LENGTH ? value : value.substring(0, MAX_LOG_LINE_LENGTH);
    }

    private static String safeExcerpt(String value) {
        String text = value == null ? "" : value.trim();
        return text.length() <= 1200 ? text : text.substring(text.length() - 1200);
    }

    public record Result(int exitCode, String output) {}
}
