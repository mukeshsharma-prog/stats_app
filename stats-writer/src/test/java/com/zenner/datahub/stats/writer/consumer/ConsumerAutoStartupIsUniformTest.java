package com.zenner.datahub.stats.writer.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenner.datahub.stats.common.model.StatType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * Every Writer listener starts under the same rule.
 *
 * <p>An explicit {@code autoStartup} on {@code @RabbitListener} overrides the container
 * factory, so whether {@code spring.rabbitmq.listener.simple.auto-startup} reaches a
 * listener depends on its own expression. Once, two of the five named the switch
 * and three did not: setting it {@code false} stopped {@code ltestats} and
 * {@code netstats} and nothing else — records piling up in two queues while the other
 * three drained normally. This scans the package for every listener, so a sixth type that
 * hand-writes its own expression fails here rather than in production.</p>
 */
class ConsumerAutoStartupIsUniformTest {

    private static final String PACKAGE = ConsumerAutoStartupIsUniformTest.class.getPackageName();

    @Test
    void everyStatTypeHasExactlyOneListenerAndEachUsesTheSharedSwitch() throws Exception {
        List<Method> listeners = listeners();

        assertThat(listeners).hasSize(StatType.values().length);
        for (StatType type : StatType.values()) {
            Method listener = listeners.stream()
                    .filter(method -> method.getDeclaringClass().getSimpleName()
                            .toLowerCase(Locale.ROOT).startsWith(type.wireName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no listener for " + type.wireName()));
            assertThat(listener.getAnnotation(RabbitListener.class).autoStartup())
                    .as("%s autoStartup", listener.getDeclaringClass().getSimpleName())
                    .isEqualTo(StatTypeDeployment.AUTO_STARTUP_PREFIX
                            + type.wireName()
                            + StatTypeDeployment.AUTO_STARTUP_SUFFIX);
        }
    }

    @Test
    void theSharedSwitchIsSpringsOwnListenerProperty() {
        assertThat(StatTypeDeployment.AUTO_STARTUP_SUFFIX)
                .contains("${spring.rabbitmq.listener.simple.auto-startup:true}");
    }

    private static List<Method> listeners() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));
        List<Method> found = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(PACKAGE)) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            if (type.getSimpleName().endsWith("Test")) {
                continue;
            }
            Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(RabbitListener.class))
                    .forEach(found::add);
        }
        return found;
    }
}
