package dev.foundry.api.deployment.build;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryBuildServiceTest {

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
