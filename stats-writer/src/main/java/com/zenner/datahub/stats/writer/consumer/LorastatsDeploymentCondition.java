package com.zenner.datahub.stats.writer.consumer;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Enables this listener only for its production deployment or the local all-types mode. */
final class LorastatsDeploymentCondition implements Condition {

    static final String STAT_TYPE_PROPERTY = StatTypeDeployment.STAT_TYPE_PROPERTY;
    static final String ALL_TYPES = StatTypeDeployment.ALL_TYPES;

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return StatTypeDeployment.serves(
                context.getEnvironment(), LorastatsConsumerDescriptor.TYPE);
    }
}
