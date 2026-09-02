package com.zenner.datahub.stats.writer.consumer;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Enables this listener only for its production deployment or the local all-types mode. */
final class LorastatsDeploymentCondition implements Condition {

    static final String STAT_TYPE_PROPERTY = "stats.writer.stat-type";
    static final String ALL_TYPES = "all";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String configured =
                context.getEnvironment().getProperty(STAT_TYPE_PROPERTY, ALL_TYPES).trim();
        return ALL_TYPES.equals(configured)
                || LorastatsConsumerDescriptor.TYPE.wireName().equals(configured);
    }
}
