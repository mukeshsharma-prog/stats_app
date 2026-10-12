package com.zenner.datahub.stats.writer.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mock.env.MockEnvironment;

class NetstatsDeploymentConditionTest {

    @ParameterizedTest
    @CsvSource({"all,true", "netstats,true", "lorastats,false", "ltestats,false", "omsstats,false", "sysstats,false"})
    void activatesOnlyForNetstatsOrTheDevelopmentAllTypesMode(
            String configuredType, boolean expected) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(StatTypeDeployment.STAT_TYPE_PROPERTY, configuredType);
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);

        boolean matches = new NetstatsDeploymentCondition()
                .matches(context, mock(AnnotatedTypeMetadata.class));

        assertThat(matches).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"all,true", "netstats,true", " netstats ,true", "lorastats,false"})
    void theSharedReadingTrimsAndCompares(String configuredType, boolean expected) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(StatTypeDeployment.STAT_TYPE_PROPERTY, configuredType);

        assertThat(StatTypeDeployment.serves(environment, NetstatsConsumerDescriptor.TYPE))
                .isEqualTo(expected);
    }
}
