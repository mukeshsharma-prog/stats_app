package com.zenner.datahub.stats.writer.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mock.env.MockEnvironment;

class LorastatsDeploymentConditionTest {

    @ParameterizedTest
    @CsvSource({"all,true", "lorastats,true", "ltestats,false", "sysstats,false", "netstats,false", "omsstats,false"})
    void activatesOnlyForLorastatsOrTheDevelopmentAllTypesMode(
            String configuredType, boolean expected) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(LorastatsDeploymentCondition.STAT_TYPE_PROPERTY, configuredType);
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);

        boolean matches = new LorastatsDeploymentCondition()
                .matches(context, mock(AnnotatedTypeMetadata.class));

        assertThat(matches).isEqualTo(expected);
    }
}
