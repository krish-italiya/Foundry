package dev.foundry.api.runtime;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Enables local ingress and lifecycle reconciliation if either runtime is explicitly enabled. */
public final class AnyRuntimeEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Boolean.parseBoolean(context.getEnvironment()
                    .getProperty("foundry.runtime.local.enabled", "false"))
                || Boolean.parseBoolean(context.getEnvironment()
                    .getProperty("foundry.runtime.docker.enabled", "false"));
    }
}
