package dev.foundry.api.runtime;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Keeps runtime selection unambiguous: exactly one execution backend may be active. */
@Component
public final class RuntimeSelectionValidator {

    private final boolean localEnabled;
    private final boolean dockerEnabled;

    public RuntimeSelectionValidator(
            @Value("${foundry.runtime.local.enabled:false}") boolean localEnabled,
            @Value("${foundry.runtime.docker.enabled:false}") boolean dockerEnabled) {
        this.localEnabled = localEnabled;
        this.dockerEnabled = dockerEnabled;
    }

    @PostConstruct
    public void validate() {
        if (localEnabled && dockerEnabled) {
            throw new IllegalStateException(
                    "Choose one Foundry runtime: enable either foundry.runtime.local.enabled "
                            + "or foundry.runtime.docker.enabled, not both.");
        }
    }
}
