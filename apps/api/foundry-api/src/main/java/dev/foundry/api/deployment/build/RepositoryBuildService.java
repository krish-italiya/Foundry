package dev.foundry.api.deployment.build;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.foundry.api.deployment.log.DeploymentLogService;
import dev.foundry.api.deployment.log.DeploymentLogSeverity;
import dev.foundry.api.deployment.log.DeploymentLogStage;
import dev.foundry.api.deployment.log.DeploymentLogStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

@Service
@ConditionalOnProperty(prefix = "foundry.build", name = "enabled", havingValue = "true")
public class RepositoryBuildService {
    private static final Pattern SAFE_REF = Pattern.compile("[A-Za-z0-9._/-]{1,255}");
    private static final Pattern SAFE_SERVICE_PATH = Pattern.compile("[A-Za-z0-9._/-]{0,512}");
    private static final Set<String> SKIP_DIRECTORIES = Set.of(
            ".git", "node_modules", "target", "dist", "build", ".next",
            ".gradle", ".idea", ".venv", "vendor");
    private static final Duration CHECKOUT_TIMEOUT = Duration.ofMinutes(2);

    private final BuildCommandRunner commandRunner;
    private final DeploymentLogService logService;
    private final ObjectMapper objectMapper;
    private final Path workspaceRoot;
    private final List<String> allowedRepositories;
    private final Duration commandTimeout;

    public RepositoryBuildService(
            BuildCommandRunner commandRunner,
            DeploymentLogService logService,
            ObjectMapper objectMapper,
            @Value("${foundry.build.workspace-root:.foundry/workspaces}") String workspaceRoot,
            @Value("${foundry.build.allowed-repositories:}") String allowedRepositories,
            @Value("${foundry.build.command-timeout-seconds:600}") long commandTimeoutSeconds) {
        this.commandRunner = commandRunner;
        this.logService = logService;
        this.objectMapper = objectMapper;
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize();
        this.allowedRepositories = allowedRepositories.isBlank()
                ? List.of()
                : java.util.Arrays.stream(allowedRepositories.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(RepositoryBuildService::normalizeGitHubRepository)
                    .toList();
        if (commandTimeoutSeconds < 1 || commandTimeoutSeconds > 3600) {
            throw new IllegalArgumentException(
                    "foundry.build.command-timeout-seconds must be between 1 and 3600");
        }
        this.commandTimeout = Duration.ofSeconds(commandTimeoutSeconds);
    }

    public BuiltArtifact build(
            UUID deploymentId,
            String repositoryUrl,
            String sourceRef,
            String requestedServicePath) {
        String normalizedRepository = normalizeGitHubRepository(repositoryUrl);
        if (allowedRepositories.isEmpty()
                || !allowedRepositories.contains(normalizedRepository)) {
            throw new RepositoryBuildException(
                    "Repository builds are restricted to explicitly trusted GitHub repositories. "
                            + "Add " + normalizedRepository
                            + " to foundry.build.allowed-repositories for a local development run.");
        }
        validateRef(sourceRef);
        String servicePath = normalizeServicePath(requestedServicePath);

        Path workspace = workspaceRoot.resolve(deploymentId.toString()).normalize();
        if (!workspace.startsWith(workspaceRoot)) {
            throw new RepositoryBuildException("Resolved build workspace is outside the configured workspace root.");
        }

        boolean keepWorkspace = false;
        try {
            Files.createDirectories(workspaceRoot);
            deleteTree(workspace);
            append(deploymentId, DeploymentLogStream.DEPLOYMENT,
                    DeploymentLogSeverity.INFO,
                    "Checking out trusted repository " + normalizedRepository
                            + " at ref " + sourceRef.trim() + ".");

            commandRunner.run(
                    deploymentId,
                    workspaceRoot,
                    List.of("git", "clone", "--depth", "1", "--branch",
                            sourceRef.trim(), "--", normalizedRepository, workspace.toString()),
                    CHECKOUT_TIMEOUT,
                    Map.of());

            commandRunner.run(
                    deploymentId,
                    workspace,
                    List.of("git", "rev-parse", "HEAD"),
                    Duration.ofSeconds(15),
                    Map.of());

            BuildServiceDescriptor descriptor = selectService(
                    deploymentId, workspace, servicePath);
            append(deploymentId, DeploymentLogStream.DEPLOYMENT,
                    DeploymentLogSeverity.INFO,
                    "Selected service path '" + displayPath(workspace, descriptor.path())
                            + "' using " + descriptor.ecosystem() + ".");

            BuiltArtifact artifact = switch (descriptor.ecosystem()) {
                case "MAVEN" -> buildMaven(deploymentId, workspace, descriptor);
                case "NPM" -> buildNpm(deploymentId, workspace, descriptor);
                default -> throw new RepositoryBuildException(
                        "Unsupported project ecosystem: " + descriptor.ecosystem());
            };
            keepWorkspace = true;
            append(deploymentId, DeploymentLogStream.DEPLOYMENT,
                    DeploymentLogSeverity.INFO,
                    "Build completed. Prepared " + artifact.launchMode()
                            + " artifact for the runtime.");
            return artifact;
        } catch (RepositoryBuildException failure) {
            throw failure;
        } catch (BuildCommandRunner.BuildCommandException failure) {
            throw new RepositoryBuildException(failure.getMessage(), failure);
        } catch (IOException failure) {
            throw new RepositoryBuildException(
                    "Repository build failed: " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RepositoryBuildException("Repository build was interrupted.", interrupted);
        } finally {
            if (!keepWorkspace) {
                try {
                    deleteTree(workspace);
                } catch (IOException ignored) {
                    append(deploymentId, DeploymentLogStream.DEPLOYMENT,
                            DeploymentLogSeverity.WARN,
                            "Could not completely remove failed build workspace.");
                }
            }
        }
    }

    private BuildServiceDescriptor selectService(
            UUID deploymentId,
            Path workspace,
            String requestedServicePath) throws IOException {
        List<BuildServiceDescriptor> discovered = discoverServices(workspace);
        if (discovered.isEmpty()) {
            throw new RepositoryBuildException(
                    "No pom.xml or package.json was found in the trusted repository.");
        }

        if (!requestedServicePath.isBlank()) {
            Path requested = workspace.resolve(requestedServicePath).normalize();
            if (!requested.startsWith(workspace) || !Files.isDirectory(requested)) {
                throw new RepositoryBuildException(
                        "The configured service path does not exist inside the repository.");
            }
            List<BuildServiceDescriptor> matches = discovered.stream()
                    .filter(service -> workspace.resolve(service.path()).normalize().equals(requested))
                    .toList();
            if (matches.size() != 1) {
                throw new RepositoryBuildException(
                        "Service path '" + requestedServicePath
                                + "' must contain exactly one supported manifest (pom.xml or package.json).");
            }
            return matches.getFirst();
        }

        if (discovered.size() == 1) {
            return discovered.getFirst();
        }

        String candidates = discovered.stream()
                .limit(12)
                .map(service -> service.path().isBlank() ? "." : service.path())
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        append(deploymentId, DeploymentLogStream.DEPLOYMENT,
                DeploymentLogSeverity.WARN,
                "Multiple services were detected: " + candidates);
        throw new RepositoryBuildException(
                "Multiple buildable services were detected. Set the service path in the deployment form. "
                        + "Candidates: " + candidates);
    }

    private List<BuildServiceDescriptor> discoverServices(Path workspace) throws IOException {
        List<BuildServiceDescriptor> services = new ArrayList<>();
        Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(workspace)) {
                    Path name = dir.getFileName();
                    if (name != null && SKIP_DIRECTORIES.contains(name.toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (workspace.relativize(dir).getNameCount() > 8) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (!name.equals("pom.xml") && !name.equals("package.json")) {
                    return FileVisitResult.CONTINUE;
                }
                if (services.size() >= 30) return FileVisitResult.TERMINATE;

                Path directory = file.getParent();
                String relativeDirectory = directory.equals(workspace)
                        ? "" : workspace.relativize(directory).toString().replace('\\', '/');
                if (name.equals("pom.xml")) {
                    String pom = Files.readString(file);
                    boolean actuator = pom.contains("spring-boot-starter-actuator");
                    services.add(new BuildServiceDescriptor(
                            relativeDirectory,
                            directory.getFileName() == null ? "repository" : directory.getFileName().toString(),
                            "MAVEN",
                            true,
                            true,
                            actuator ? "/actuator/health" : "/"));
                } else {
                    JsonNode packageJson = objectMapper.readTree(file.toFile());
                    JsonNode scripts = packageJson.path("scripts");
                    boolean hasTest = usableScript(scripts.path("test").asText(""));
                    boolean hasBuild = usableScript(scripts.path("build").asText(""));
                    boolean hasPreview = usableScript(scripts.path("preview").asText(""));
                    boolean hasStart = usableScript(scripts.path("start").asText(""));
                    String launchMode = hasPreview ? "VITE_PREVIEW" : hasStart ? "NODE_START" : "UNSUPPORTED";
                    String packageName = packageJson.path("name").asText(
                            directory.getFileName() == null ? "web-app" : directory.getFileName().toString());
                    services.add(new BuildServiceDescriptor(
                            relativeDirectory,
                            packageName,
                            "NPM",
                            hasTest,
                            hasBuild,
                            launchMode));
                }
                return FileVisitResult.CONTINUE;
            }
        });

        // Multiple manifests in one directory require an explicit service choice, not a silent guess.
        Map<String, List<BuildServiceDescriptor>> byPath = new HashMap<>();
        for (BuildServiceDescriptor service : services) {
            byPath.computeIfAbsent(service.path(), ignored -> new ArrayList<>()).add(service);
        }
        List<BuildServiceDescriptor> normalized = new ArrayList<>();
        for (Map.Entry<String, List<BuildServiceDescriptor>> entry : byPath.entrySet()) {
            if (entry.getValue().size() > 1) {
                normalized.add(new BuildServiceDescriptor(entry.getKey(), entry.getValue().getFirst().name(),
                        "AMBIGUOUS", false, false, "UNSUPPORTED"));
            } else normalized.add(entry.getValue().getFirst());
        }
        normalized.sort(Comparator.comparing(BuildServiceDescriptor::path));
        return normalized;
    }

    private BuiltArtifact buildMaven(
            UUID deploymentId,
            Path workspace,
            BuildServiceDescriptor descriptor) throws IOException, InterruptedException {
        Path serviceDirectory = resolveServiceDirectory(workspace, descriptor.path());
        Path pom = serviceDirectory.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new RepositoryBuildException("Maven manifest disappeared from selected service.");
        }

        List<String> command;
        Path commandDirectory;
        Path wrapperInService = serviceDirectory.resolve(isWindows() ? "mvnw.cmd" : "mvnw");
        Path wrapperAtRoot = workspace.resolve(isWindows() ? "mvnw.cmd" : "mvnw");
        if (Files.isRegularFile(wrapperInService)) {
            commandDirectory = serviceDirectory;
            command = List.of(wrapperInService.getFileName().toString(), "-B", "test", "package");
        } else if (Files.isRegularFile(wrapperAtRoot)) {
            commandDirectory = workspace;
            command = List.of(wrapperAtRoot.getFileName().toString(),
                    "-f", workspace.relativize(pom).toString(), "-B", "test", "package");
        } else {
            commandDirectory = serviceDirectory;
            command = List.of(isWindows() ? "mvn.cmd" : "mvn", "-B", "test", "package");
        }

        append(deploymentId, DeploymentLogStream.DEPLOYMENT,
                DeploymentLogSeverity.INFO, "Maven build started: tests and package lifecycle.");
        commandRunner.run(deploymentId, commandDirectory, command, commandTimeout, Map.of("CI", "true"));

        Path artifactPath;
        try (var targets = Files.list(serviceDirectory.resolve("target"))) {
            artifactPath = targets
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(RepositoryBuildService::isExecutableSpringBootJar)
                    .max(Comparator.comparingLong(RepositoryBuildService::lastModified))
                    .orElseThrow(() -> new RepositoryBuildException(
                            "Maven finished, but no executable Spring Boot JAR was found in target/. "
                                    + "Library modules and non-executable JARs are not deployable yet."));
        } catch (IOException missingTarget) {
            throw new RepositoryBuildException(
                    "Maven finished, but the service target/ directory could not be read.", missingTarget);
        }

        String pomText = Files.readString(pom);
        boolean hasActuator = pomText.contains("spring-boot-starter-actuator");
        return new BuiltArtifact(
                workspace,
                serviceDirectory,
                artifactPath,
                BuiltArtifact.LaunchMode.SPRING_BOOT_JAR,
                hasActuator ? "/actuator/health" : descriptor.launchMode(),
                hasActuator ? "\"status\":\"UP\"" : "");
    }

    private BuiltArtifact buildNpm(
            UUID deploymentId,
            Path workspace,
            BuildServiceDescriptor descriptor) throws IOException, InterruptedException {
        Path serviceDirectory = resolveServiceDirectory(workspace, descriptor.path());
        Path packageFile = serviceDirectory.resolve("package.json");
        JsonNode packageJson = objectMapper.readTree(packageFile.toFile());
        JsonNode scripts = packageJson.path("scripts");
        boolean hasLockfile = Files.isRegularFile(serviceDirectory.resolve("package-lock.json"));
        if (!hasLockfile) {
            throw new RepositoryBuildException(
                    "This first npm pipeline requires package-lock.json so dependencies are installed reproducibly.");
        }

        List<String> npmPrefix = List.of(isWindows() ? "npm.cmd" : "npm");
        append(deploymentId, DeploymentLogStream.DEPLOYMENT,
                DeploymentLogSeverity.INFO, "NPM build started.");
        commandRunner.run(deploymentId, serviceDirectory,
                concat(npmPrefix, List.of("ci")), commandTimeout, Map.of("CI", "true"));

        String testScript = scripts.path("test").asText("");
        if (usableScript(testScript)) {
            commandRunner.run(deploymentId, serviceDirectory,
                    concat(npmPrefix, List.of("test")), commandTimeout, Map.of("CI", "true"));
        } else {
            append(deploymentId, DeploymentLogStream.DEPLOYMENT,
                    DeploymentLogSeverity.WARN, "No test script is configured; test step skipped.");
        }

        String buildScript = scripts.path("build").asText("");
        if (usableScript(buildScript)) {
            commandRunner.run(deploymentId, serviceDirectory,
                    concat(npmPrefix, List.of("run", "build")), commandTimeout, Map.of("CI", "true"));
        } else if (!usableScript(scripts.path("start").asText(""))) {
            throw new RepositoryBuildException(
                    "No usable npm build or start script was found in package.json.");
        }

        boolean preview = usableScript(scripts.path("preview").asText(""));
        boolean start = usableScript(scripts.path("start").asText(""));
        if (!preview && !start) {
            throw new RepositoryBuildException(
                    "NPM commands completed, but no preview or start script is available to run the service.");
        }
        if (preview && !Files.isDirectory(serviceDirectory.resolve("dist"))) {
            throw new RepositoryBuildException(
                    "A preview script is configured but no dist/ output directory was produced.");
        }
        return new BuiltArtifact(
                workspace,
                serviceDirectory,
                preview ? serviceDirectory.resolve("dist") : null,
                preview ? BuiltArtifact.LaunchMode.VITE_PREVIEW : BuiltArtifact.LaunchMode.NODE_START,
                "/",
                "");
    }

    private Path resolveServiceDirectory(Path workspace, String relativePath) {
        Path serviceDirectory = relativePath.isBlank()
                ? workspace : workspace.resolve(relativePath).normalize();
        if (!serviceDirectory.startsWith(workspace)) {
            throw new RepositoryBuildException("Selected service path escapes the checkout workspace.");
        }
        return serviceDirectory;
    }

    private static String normalizeServicePath(String requested) {
        if (requested == null || requested.isBlank() || requested.trim().equals(".")) return "";
        String value = requested.trim().replace('\\', '/');
        if (!SAFE_SERVICE_PATH.matcher(value).matches()
                || value.startsWith("/")
                || value.contains("//")
                || List.of(value.split("/")).contains("..")
                || List.of(value.split("/")).contains(".")) {
            throw new RepositoryBuildException("Service path must be a repository-relative path without '..' segments.");
        }
        return value;
    }

    private static void validateRef(String sourceRef) {
        if (sourceRef == null || !SAFE_REF.matcher(sourceRef.trim()).matches()
                || sourceRef.trim().startsWith("-")
                || sourceRef.contains("..")
                || sourceRef.contains("//")
                || sourceRef.contains("@{")) {
            throw new RepositoryBuildException("Source ref is invalid for a Git branch or tag.");
        }
    }

    public static String normalizeGitHubRepository(String repositoryUrl) {
        try {
            URI uri = URI.create(repositoryUrl == null ? "" : repositoryUrl.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null
                    || uri.getPort() != -1
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new RepositoryBuildException(
                        "Only HTTPS GitHub repository URLs can be used for builds.");
            }
            String path = uri.getPath();
            if (path == null) throw new RepositoryBuildException("Invalid GitHub repository URL.");
            String[] parts = path.replaceFirst("^/", "").replaceFirst("/+$", "").split("/");
            if (parts.length != 2) {
                throw new RepositoryBuildException(
                        "Repository URL must look like https://github.com/owner/repository.");
            }
            String owner = parts[0];
            String name = parts[1].endsWith(".git")
                    ? parts[1].substring(0, parts[1].length() - 4) : parts[1];
            if (!owner.matches("[A-Za-z0-9_.-]+")
                    || !name.matches("[A-Za-z0-9_.-]+")) {
                throw new RepositoryBuildException("Invalid GitHub repository path.");
            }
            return "https://github.com/" + owner + "/" + name;
        } catch (IllegalArgumentException invalid) {
            if (invalid instanceof RepositoryBuildException buildFailure) throw buildFailure;
            throw new RepositoryBuildException("Invalid GitHub repository URL.", invalid);
        }
    }

    private static boolean usableScript(String script) {
        return script != null && !script.isBlank()
                && !script.contains("Error: no test specified");
    }

    private static boolean isExecutableSpringBootJar(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.stream().anyMatch(entry -> entry.getName().startsWith("BOOT-INF/"));
        } catch (IOException invalidJar) {
            return false;
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static List<String> concat(List<String> left, List<String> right) {
        List<String> result = new ArrayList<>(left);
        result.addAll(right);
        return result;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String displayPath(Path workspace, String relativePath) {
        return relativePath.isBlank() ? "." : relativePath;
    }

    private void append(UUID id, DeploymentLogStream stream, DeploymentLogSeverity severity, String message) {
        logService.append(id, DeploymentLogStage.BUILD, stream, severity, message);
    }

    private static void deleteTree(Path target) throws IOException {
        if (!Files.exists(target)) return;
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
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
}
