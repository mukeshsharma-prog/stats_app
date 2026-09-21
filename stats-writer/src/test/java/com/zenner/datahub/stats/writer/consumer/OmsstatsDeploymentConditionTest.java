package com.zenner.datahub.stats.writer.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mock.env.MockEnvironment;

class OmsstatsDeploymentConditionTest {

    @ParameterizedTest
    @CsvSource({"all,true", "omsstats,true", "lorastats,false", "ltestats,false", "netstats,false", "sysstats,false"})
    void activatesOnlyForOmsstatsOrTheDevelopmentAllTypesMode(
            String configuredType, boolean expected) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(StatTypeDeployment.STAT_TYPE_PROPERTY, configuredType);
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);

        boolean matches = new OmsstatsDeploymentCondition()
                .matches(context, mock(AnnotatedTypeMetadata.class));

        assertThat(matches).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"all,true", "omsstats,true", " omsstats ,true", "lorastats,false"})
    void theSharedReadingTrimsAndCompares(String configuredType, boolean expected) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(StatTypeDeployment.STAT_TYPE_PROPERTY, configuredType);

        assertThat(StatTypeDeployment.serves(environment, OmsstatsConsumerDescriptor.TYPE))
                .isEqualTo(expected);
    }
}
