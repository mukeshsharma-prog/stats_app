package com.zenner.datahub.stats.input.publish;

import com.zenner.datahub.stats.common.model.StatType;

/**
 * Publishes one record to the {@code app_stats_input} exchange, using the stat type
 * as the routing key, and waits for the publisher confirm.
 *
 * <p>The port exists so the gate and the confirm semantics can land
 * separately without the controller changing shape. Implementations must not return
 * {@link PublishOutcome#CONFIRMED} until the broker has actually confirmed: an
 * optimistic return here is indistinguishable from success at every layer above, and
 * a broker failure in that window loses the record <em>and</em> stops Heimdal
 * retrying, because it saw a 200.</p>
 *
 * @param body the exact bytes received over HTTP. Implementations must publish them
 *     unchanged — publication is byte-identical to the received record,
 *     which is what makes the Writer's per-partition duplicate suppression correct.
 */
public interface StatsPublisher {

    PublishOutcome publish(StatType statType, byte[] body);
}
