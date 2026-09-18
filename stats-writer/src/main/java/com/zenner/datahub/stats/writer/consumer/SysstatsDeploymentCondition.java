package com.zenner.datahub.stats.writer.consumer;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Enables the sysstats listener only for its production deployment or the local all-types mode. */
final class SysstatsDeploymentCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return StatTypeDeployment.serves(
                context.getEnvironment(), SysstatsConsumerDescriptor.TYPE);
    }
}
