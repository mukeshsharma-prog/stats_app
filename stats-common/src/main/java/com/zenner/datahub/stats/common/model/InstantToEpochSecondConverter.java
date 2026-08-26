package com.zenner.datahub.stats.common.model;

import com.fasterxml.jackson.databind.util.StdConverter;
import java.time.Instant;

/** Converts the domain timestamp back to its Unix-seconds wire representation. */
public final class InstantToEpochSecondConverter extends StdConverter<Instant, Long> {

    @Override
    public Long convert(Instant instant) {
        return instant.getEpochSecond();
    }
}
