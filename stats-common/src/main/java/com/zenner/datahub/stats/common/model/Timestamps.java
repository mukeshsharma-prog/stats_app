package com.zenner.datahub.stats.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.time.Instant;

/**
 * The production {@code ts} block, represented as Instants in Java and Unix
 * seconds in JSON.
 *
 * <p>{@code server} is the canonical arrival clock (ADR-007 §3) — what the SRS
 * called {@code ts.processor}. It was chosen over {@code datastore} because
 * {@code datastore} is the legacy Mongo store's write clock and describes a
 * system we are replacing. Retention still keys on {@code device}, not on
 * {@code server} (STA-23B).</p>
 *
 * <p>{@code datastore} and {@code converter} are accepted, preserved, and never
 * read. {@code converter} is present on 100% of production records; ADR-004 used
 * to declare such a record invalid, which rejected every record Heimdal sends,
 * and that clause was removed on 29 August 2026. ADR-004's actual decision is
 * unchanged and still binding: <strong>the Stats App performs no timestamp
 * normalisation.</strong> Input meters presence as
 * {@code records_converter_ts_present{type}} — read it from {@link #converter()}
 * being non-null — so we can see if Heimdal ever stops sending it.</p>
 *
 * <p>{@code expiresOn} is the legacy Mongo TTL key, an ISO-8601 instant — never an
 * epoch integer — inside this same object, and it has been seen in <em>two</em>
 * spellings: the plain string of the 29 Aug 2026 sample, and MongoDB Extended JSON
 * {@code {"$date": "…"}} as the live datastore returns it (DL-146). Which one the
 * HTTP forward emits is open (Q15e), so it is kept as a {@link JsonNode} and never
 * parsed or read: Stats App retention is a partition detach-and-drop keyed on
 * {@code ts_device} (ADR-003 D1), not a TTL. It is modelled rather than ignored so
 * the typed view stays lossless, which is what lets ADR-004's byte-identical
 * publication survive a round trip through this record.</p>
 *
 * <p>{@code deviceOrig} appears when Heimdal has <em>rewritten</em> {@code device}:
 * live records carry {@code meta.tsDeviceCorrected: true} with {@code ts.device}
 * set equal to {@code ts.server} and the clock the gateway actually sent kept here
 * (DL-146). So {@code device} is not always device-assigned, and the value retention
 * keys on is the corrected one. Preserved in the passthrough column, never read;
 * when Heimdal applies the correction is open (Q15f).</p>
 *
 * <p>The Draft 2020-12 envelope schema owns range validation. The model does not
 * duplicate those checks; ingress validates the original JSON before creating
 * this typed view.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Timestamps(
        @JsonDeserialize(converter = EpochSecondToInstantConverter.class)
        @JsonSerialize(converter = InstantToEpochSecondConverter.class)
        Instant server,

        @JsonDeserialize(converter = EpochSecondToInstantConverter.class)
        @JsonSerialize(converter = InstantToEpochSecondConverter.class)
        Instant device,

        @JsonDeserialize(converter = EpochSecondToInstantConverter.class)
        @JsonSerialize(converter = InstantToEpochSecondConverter.class)
        Instant datastore,

        @JsonDeserialize(converter = EpochSecondToInstantConverter.class)
        @JsonSerialize(converter = InstantToEpochSecondConverter.class)
        Instant converter,

        @JsonDeserialize(converter = EpochSecondToInstantConverter.class)
        @JsonSerialize(converter = InstantToEpochSecondConverter.class)
        Instant deviceOrig,

        JsonNode expiresOn) {
}
