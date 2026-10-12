package com.zenner.datahub.stats.writer.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mock.env.MockEnvironment;

class LtestatsDeploymentConditionTest {

    @ParameterizedTest
    @CsvSource({"all,true", "ltestats,true", "lorastats,false", "omsstats,false", "netstats,false", "sysstats,false"})
    void activatesOnlyForLtestatsOrTheDevelopmentAllTypesMode(
            String configuredType, boolean expected) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(StatTypeDeployment.STAT_TYPE_PROPERTY, configuredType);
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);

        boolean matches = new LtestatsDeploymentCondition()
                .matches(context, mock(AnnotatedTypeMetadata.class));

        assertThat(matches).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"all,true", "ltestats,true", " ltestats ,true", "lorastats,false"})
    void theSharedReadingTrimsAndCompares(String configuredType, boolean expected) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(StatTypeDeployment.STAT_TYPE_PROPERTY, configuredType);

        assertThat(StatTypeDeployment.serves(environment, LtestatsConsumerDescriptor.TYPE))
                .isEqualTo(expected);
    }
}
