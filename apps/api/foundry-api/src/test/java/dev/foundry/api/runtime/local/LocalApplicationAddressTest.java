package dev.foundry.api.runtime.local;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalApplicationAddressTest {

    @Test
    void createsStablePerDeploymentLocalhostSubdomain() {
        UUID id = UUID.fromString("af831a2a-d706-433a-8f1d-6a523e04de6e");
        URI base = LocalApplicationAddress.parseBaseUrl("http://localhost:8080");

        assertThat(LocalApplicationAddress.applicationUrl(id, base))
                .isEqualTo(URI.create("http://af831a2a-d706-433a-8f1d-6a523e04de6e.localhost:8080"));
    }

    @Test
    void recognizesOnlySingleUuidLocalhostHostnames() {
        UUID id = UUID.fromString("af831a2a-d706-433a-8f1d-6a523e04de6e");

        assertThat(LocalApplicationAddress.deploymentIdFromHost(id + ".localhost")).isEqualTo(id);
        assertThat(LocalApplicationAddress.deploymentIdFromHost("LOCALHOST")).isNull();
        assertThat(LocalApplicationAddress.deploymentIdFromHost("other.example.com")).isNull();
        assertThat(LocalApplicationAddress.deploymentIdFromHost("a.b.localhost")).isNull();
        assertThat(LocalApplicationAddress.deploymentIdFromHost("not-a-uuid.localhost")).isNull();
    }

    @Test
    void rejectsNonLocalApplicationBaseUrls() {
        assertThatThrownBy(() -> LocalApplicationAddress.parseBaseUrl("https://example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localhost");
        assertThatThrownBy(() -> LocalApplicationAddress.parseBaseUrl("http://localhost:8080/apps"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localhost");
        assertThatThrownBy(() -> LocalApplicationAddress.parseBaseUrl("http://user@localhost:8080"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localhost");
    }
}
