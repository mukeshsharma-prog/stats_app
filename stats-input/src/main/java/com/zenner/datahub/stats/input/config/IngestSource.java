package com.zenner.datahub.stats.input.config;

import java.util.Locale;

/**
 * Which of the two ingest doors a request came through (DL-151).
 *
 * <p>This is a <em>label</em> — on every Input counter as {@code source} and on every
 * ingest log line as {@code ingestSource} — and nothing else. No gate, publisher or
 * response branches on it: both doors accept the same records under the same rules,
 * and a record that was accepted through one would have been accepted through the
 * other. The value that makes the split worth having is that an operator can see what
 * external callers send separately from what Heimdal sends.</p>
 */
public enum IngestSource {
    /** Heimdal Core — the Datahub's own forward. */
    INTERNAL,
    /** Anything that is not Heimdal, reaching us through the external ingress. */
    EXTERNAL;

    /** Label value used before the path has been resolved; pairs with {@code unknown} elsewhere. */
    public static final String UNKNOWN = "unknown";

    /** The metric and MDC spelling: lower case, so {@code source="internal"}. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Label for a possibly-null source. */
    public static String labelOf(IngestSource source) {
        return source == null ? UNKNOWN : source.label();
    }
}
