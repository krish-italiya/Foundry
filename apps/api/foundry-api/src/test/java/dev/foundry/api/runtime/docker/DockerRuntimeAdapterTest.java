package dev.foundry.api.runtime.docker;

import dev.foundry.api.deployment.build.BuiltArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerRuntimeAdapterTest {

    @TempDir
    Path tempDirectory;

    @Test
    void nodeStartUsesLockfileInstallWhenLockfileIsAvailable() throws IOException {
        Files.writeString(tempDirectory.resolve("package.json"), "{\"scripts\":{\"start\":\"node app.js\"}}");
        Files.writeString(tempDirectory.resolve("package-lock.json"), "{}");

        String dockerfile = DockerRuntimeAdapter.dockerfileFor(
                artifact(BuiltArtifact.LaunchMode.NODE_START), tempDirectory);

        assertThat(dockerfile).contains("FROM node:22-alpine", "RUN chown node:node /app",
                "RUN npm ci --omit=dev", "USER node", "CMD [\"npm\",\"run\",\"start\"]");
        assertThat(dockerfile.indexOf("RUN chown node:node /app"))
                .isLessThan(dockerfile.indexOf("USER node"));
        assertThat(dockerfile.indexOf("USER node"))
                .isLessThan(dockerfile.indexOf("RUN npm ci --omit=dev"));
    }

    @Test
    void nodeStartHasFallbackWhenNoLockfileExists() throws IOException {
        Files.writeString(tempDirectory.resolve("package.json"), "{\"scripts\":{\"start\":\"node app.js\"}}");

        String dockerfile = DockerRuntimeAdapter.dockerfileFor(
                artifact(BuiltArtifact.LaunchMode.NODE_START), tempDirectory);

        assertThat(dockerfile).contains("RUN npm install --omit=dev");
    }

    @Test
    void nodeStartRejectsMissingPackageManifest() {
        assertThatThrownBy(() -> DockerRuntimeAdapter.dockerfileFor(
                artifact(BuiltArtifact.LaunchMode.NODE_START), tempDirectory))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("package.json");
    }

    @Test
    void javaRuntimeUsesNonRootUserAndFixedContainerPort() throws IOException {
        String dockerfile = DockerRuntimeAdapter.dockerfileFor(
                artifact(BuiltArtifact.LaunchMode.SPRING_BOOT_JAR), tempDirectory);

        assertThat(dockerfile).contains("USER 10001:10001", "--server.address=0.0.0.0",
                "--server.port=8080", "EXPOSE 8080");
    }

    @Test
    void viteRuntimeUsesUnprivilegedNginx() throws IOException {
        String dockerfile = DockerRuntimeAdapter.dockerfileFor(
                artifact(BuiltArtifact.LaunchMode.VITE_PREVIEW), tempDirectory);

        assertThat(dockerfile).contains("nginx-unprivileged:1.27-alpine",
                "COPY app/ /usr/share/nginx/html/");
    }

    private static BuiltArtifact artifact(BuiltArtifact.LaunchMode mode) {
        return new BuiltArtifact(Path.of("workspace"), Path.of("service"), Path.of("artifact.jar"),
                mode, "/", "");
    }
}
