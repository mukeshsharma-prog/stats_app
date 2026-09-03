package com.zenner.datahub.stats.writer.consumer;

import com.zenner.datahub.stats.common.model.StatType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Selects the processor for one stat type, and reports whether it exists.
 *
 * <p>The listener container is registered up front but starts only when a real
 * {@link StatsMessageProcessor} for its type is present, so an intermediate
 * deployment cannot consume and acknowledge records through a no-op handler.</p>
 *
 * <p>Selection is <strong>by stat type</strong>, never "the only bean of this
 * interface". Phase 3 adds four more processors; resolving by uniqueness would then
 * throw {@code NoUniqueBeanDefinitionException} and adding a type would break the
 * existing ones, which rule 6 forbids. Two processors claiming the same type is a
 * configuration error and fails loudly rather than picking one.</p>
 */
public final class StatsMessageProcessorAvailability {

    private final ObjectProvider<StatsMessageProcessor> processors;

    public StatsMessageProcessorAvailability(ObjectProvider<StatsMessageProcessor> processors) {
        this.processors = Objects.requireNonNull(processors, "processors");
    }

    /**
     * Whether a processor for {@code wireName} exists.
     *
     * <p>Takes the wire literal because it is called from the {@code autoStartup}
     * SpEL of {@code @RabbitListener}, where only compile-time constants are
     * available.</p>
     */
    public boolean available(String wireName) {
        return find(StatType.fromWireName(wireName)).isPresent();
    }

    StatsMessageProcessor requiredProcessor(StatType type) {
        return find(type)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "No StatsMessageProcessor registered for stat type "
                                                + type.wireName()));
    }

    private Optional<StatsMessageProcessor> find(StatType type) {
        Objects.requireNonNull(type, "type");
        List<StatsMessageProcessor> matches =
                processors.stream().filter(processor -> processor.statType() == type).toList();
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "More than one StatsMessageProcessor claims stat type " + type.wireName());
        }
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.getFirst());
    }
}
