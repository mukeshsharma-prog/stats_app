package com.zenner.datahub.stats.writer.consumer;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Enables the omsstats listener only for its production deployment or the local all-types mode. */
final class OmsstatsDeploymentCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return StatTypeDeployment.serves(
                context.getEnvironment(), OmsstatsConsumerDescriptor.TYPE);
    }
}
