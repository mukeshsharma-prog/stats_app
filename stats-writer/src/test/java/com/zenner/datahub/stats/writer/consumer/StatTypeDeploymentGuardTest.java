package com.zenner.datahub.stats.writer.consumer;

import static com.zenner.datahub.stats.writer.consumer.LorastatsDeploymentCondition.ALL_TYPES;
import static com.zenner.datahub.stats.writer.consumer.LorastatsDeploymentCondition.STAT_TYPE_PROPERTY;
import static com.zenner.datahub.stats.writer.consumer.StatTypeDeploymentGuard.PRODUCTION_PROFILE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zenner.datahub.stats.common.model.StatType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * D-10's unenforced half: ADR-003 D8 says one stat type per deployment in production,
 * and until 4 September 2026 nothing said so at runtime.
 */
class StatTypeDeploymentGuardTest {

    private static String anyRealType() {
        return StatType.values()[0].wireName();
    }

    @Test
    void refusesTheAllTypesModeUnderTheProductionProfile() {
        MockEnvironment environment =
                new MockEnvironment().withProperty(STAT_TYPE_PROPERTY, ALL_TYPES);
        environment.setActiveProfiles(PRODUCTION_PROFILE);

        assertThatThrownBy(() -> new StatTypeDeploymentGuard(environment))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(STAT_TYPE_PROPERTY)
                .hasMessageContaining(PRODUCTION_PROFILE);
    }

    /**
     * The convenience mode has to survive outside production or every developer running
     * the compose stack has to name a type. The WARN is what keeps it visible; a guard
     * that forbade it everywhere would just get the property removed.
     */
    @Test
    void allowsTheAllTypesModeOutsideProduction() {
        assertThatCode(
                        () ->
                                new StatTypeDeploymentGuard(
                                        new MockEnvironment()
                                                .withProperty(STAT_TYPE_PROPERTY, ALL_TYPES)))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsASingleTypeInProduction() {
        MockEnvironment environment =
                new MockEnvironment().withProperty(STAT_TYPE_PROPERTY, anyRealType());
        environment.setActiveProfiles(PRODUCTION_PROFILE);

        assertThatCode(() -> new StatTypeDeploymentGuard(environment)).doesNotThrowAnyException();
    }

    /**
     * The silent case, and the reason this check is not production-only: a typo makes
     * every per-type {@code Condition} evaluate false, so the Writer starts, binds no
     * listener, consumes nothing, and reports ready — readiness has no opinion about
     * whether a listener exists.
     */
    @Test
    void refusesAnUnrecognisedTypeInEveryProfile() {
        assertThatThrownBy(
                        () ->
                                new StatTypeDeploymentGuard(
                                        new MockEnvironment()
                                                .withProperty(STAT_TYPE_PROPERTY, "lorastat")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lorastat");
    }

    /** Whitespace around an env-var value is the ordinary deployment accident. */
    @Test
    void toleratesSurroundingWhitespace() {
        MockEnvironment environment =
                new MockEnvironment().withProperty(STAT_TYPE_PROPERTY, "  " + anyRealType() + " ");
        environment.setActiveProfiles(PRODUCTION_PROFILE);

        assertThatCode(() -> new StatTypeDeploymentGuard(environment)).doesNotThrowAnyException();
    }

    /**
     * The message has to name the alternatives, because the person reading it is holding
     * a failed deploy and needs the fix, not a diagnosis.
     */
    @Test
    void namesEveryValidTypeInTheFailureMessage() {
        MockEnvironment environment =
                new MockEnvironment().withProperty(STAT_TYPE_PROPERTY, ALL_TYPES);
        environment.setActiveProfiles(PRODUCTION_PROFILE);

        assertThatThrownBy(() -> new StatTypeDeploymentGuard(environment))
                .satisfies(
                        thrown -> {
                            for (StatType type : StatType.values()) {
                                assertThat(thrown).hasMessageContaining(type.wireName());
                            }
                        });
    }
}
