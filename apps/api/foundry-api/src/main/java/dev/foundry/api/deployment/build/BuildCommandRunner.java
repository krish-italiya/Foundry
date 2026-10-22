package dev.foundry.api.deployment.build;

import dev.foundry.api.deployment.log.DeploymentLogService;
import dev.foundry.api.deployment.log.DeploymentLogSeverity;
import dev.foundry.api.deployment.log.DeploymentLogStage;
import dev.foundry.api.deployment.log.DeploymentLogStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class BuildCommandRunner {
    private static final Logger log = LoggerFactory.getLogger(BuildCommandRunner.class);

    private final DeploymentLogService logService;

    public BuildCommandRunner(DeploymentLogService logService) {
        this.logService = logService;
    }

    public void run(
            UUID deploymentId,
            Path workingDirectory,
            List<String> arguments,
            Duration timeout,
            Map<String, String> environment) throws IOException, InterruptedException {

        ProcessBuilder builder = new ProcessBuilder(windowsCommand(arguments));
        builder.directory(workingDirectory.toFile());
        builder.environment().putAll(environment);
        builder.redirectErrorStream(false);

        String displayCommand = String.join(" ", arguments);
        append(deploymentId, DeploymentLogStream.DEPLOYMENT,
                DeploymentLogSeverity.INFO,
                "Running in " + workingDirectory + ": " + displayCommand);

        Process process = builder.start();
        Thread stdout = Thread.ofVirtual().name("foundry-build-stdout-" + deploymentId)
                .start(() -> capture(deploymentId, process.getInputStream(), DeploymentLogStream.STDOUT));
        Thread stderr = Thread.ofVirtual().name("foundry-build-stderr-" + deploymentId)
                .start(() -> capture(deploymentId, process.getErrorStream(), DeploymentLogStream.STDERR));

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
                throw new BuildCommandException(
                        "Command timed out after " + timeout.toMinutes()
                                + " minute(s): " + displayCommand);
            }
        } catch (InterruptedException interrupted) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            try {
                stdout.join(2000);
                stderr.join(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        int exitCode = process.exitValue();
        append(deploymentId, DeploymentLogStream.DEPLOYMENT,
                exitCode == 0 ? DeploymentLogSeverity.INFO : DeploymentLogSeverity.ERROR,
                "Command exited with code " + exitCode + ": " + displayCommand);

        if (exitCode != 0) {
            throw new BuildCommandException(
                    "Command failed with exit code " + exitCode + ": " + displayCommand);
        }
    }

    private List<String> windowsCommand(List<String> arguments) {
        if (!System.getProperty("os.name").toLowerCase().contains("win")
                || arguments.isEmpty()
                || !(arguments.getFirst().toLowerCase().endsWith(".cmd")
                || arguments.getFirst().toLowerCase().endsWith(".bat"))) {
            return arguments;
        }

        String commandLine = arguments.stream()
                .map(BuildCommandRunner::quoteCmdArgument)
                .reduce((left, right) -> left + " " + right)
                .orElseThrow();

        // The repository URL, ref, and service path are validated before reaching a build command.
        return List.of("cmd.exe", "/d", "/s", "/c", "\"" + commandLine + "\"");
    }

    private static String quoteCmdArgument(String argument) {
        if (argument.contains("\"")) {
            throw new IllegalArgumentException("Command arguments cannot contain quotes.");
        }
        if (argument.isEmpty() || argument.chars().anyMatch(Character::isWhitespace)) {
            return "\"" + argument + "\"";
        }
        return argument;
    }

    private void capture(UUID deploymentId, InputStream stream, DeploymentLogStream logStream) {
        DeploymentLogSeverity severity = logStream == DeploymentLogStream.STDERR
                ? DeploymentLogSeverity.ERROR : DeploymentLogSeverity.INFO;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String bounded = line.length() > 4000 ? line.substring(0, 4000) : line;
                append(deploymentId, logStream, severity, bounded);
            }
        } catch (IOException failure) {
            log.debug("Build output stream closed for deployment {}", deploymentId);
        }
    }

    private void append(
            UUID deploymentId,
            DeploymentLogStream stream,
            DeploymentLogSeverity severity,
            String message) {
        try {
            logService.append(
                    deploymentId, DeploymentLogStage.BUILD, stream, severity, message);
        } catch (RuntimeException failure) {
            log.warn("Could not persist build output for deployment {}", deploymentId, failure);
        }
    }

    public static class BuildCommandException extends IOException {
        public BuildCommandException(String message) {
            super(message);
        }
    }
}
