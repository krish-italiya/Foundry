package dev.foundry.api.deployment.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryBuildServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsRunnableJarWithMainClassManifest() throws IOException {
        Path jar = createJar("runnable.jar", "dev.foundry.samples.HelloServiceApplication");

        assertThat(RepositoryBuildService.isRunnableJar(jar)).isTrue();
    }

    @Test
    void rejectsJarWithoutMainClassManifest() throws IOException {
        Path jar = createJar("library.jar", null);

        assertThat(RepositoryBuildService.isRunnableJar(jar)).isFalse();
    }

    private Path createJar(String fileName, String mainClass) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClass != null) {
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        Path jar = temporaryDirectory.resolve(fileName);
        try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            // A manifest-only archive is sufficient to test runnable-JAR detection.
        }
        return jar;
    }

    @Test
    void normalizesPublicHttpsGitHubRepositoryUrl() {
        assertThat(RepositoryBuildService.normalizeGitHubRepository(
                "https://github.com/OpenAI/example.git"))
                .isEqualTo("https://github.com/OpenAI/example");
        assertThat(RepositoryBuildService.normalizeGitHubRepository(
                "https://github.com/OpenAI/example/"))
                .isEqualTo("https://github.com/OpenAI/example");
    }

    @Test
    void rejectsNonGitHubRepositoryHosts() {
        assertThatThrownBy(() -> RepositoryBuildService.normalizeGitHubRepository(
                "https://example.com/owner/repo"))
                .isInstanceOf(RepositoryBuildException.class)
                .hasMessageContaining("GitHub");
    }

    @Test
    void rejectsGitHubUrlsWithCredentialsOrQueryParameters() {
        assertThatThrownBy(() -> RepositoryBuildService.normalizeGitHubRepository(
                "https://token@github.com/owner/repo"))
                .isInstanceOf(RepositoryBuildException.class);
        assertThatThrownBy(() -> RepositoryBuildService.normalizeGitHubRepository(
                "https://github.com/owner/repo?token=secret"))
                .isInstanceOf(RepositoryBuildException.class);
    }
}
