package com.zenner.datahub.stats.common.model;

import com.fasterxml.jackson.databind.util.StdConverter;
import java.time.Instant;

/** Converts the JSON integer representation of Unix seconds to the domain type. */
public final class EpochSecondToInstantConverter extends StdConverter<Long, Instant> {

    @Override
    public Instant convert(Long epochSecond) {
        return Instant.ofEpochSecond(epochSecond);
    }
}
