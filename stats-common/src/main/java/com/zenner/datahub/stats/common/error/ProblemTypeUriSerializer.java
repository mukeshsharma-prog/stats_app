package com.zenner.datahub.stats.common.error;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.net.URI;

/**
 * Writes {@link ProblemDetails#type()} under the deployment's base when the mapper carries
 * {@link ProblemResponseSettings} ({@link ProblemResponseSettings#applyTo}), and unchanged
 * when it does not.
 *
 * <p>Serialization is the one point every problem passes — controller bodies, servlet
 * filters and Spring Security's handlers alike — so the base cannot be honoured on one
 * path and forgotten on another. Only a URI under {@link ErrorCode#TYPE_URI_BASE} is
 * rebased; the wire code after it is kept exactly.</p>
 */
public final class ProblemTypeUriSerializer extends StdSerializer<URI> {

    private static final String CATALOGUE_PREFIX = ErrorCode.TYPE_URI_BASE + "/";

    public ProblemTypeUriSerializer() {
        super(URI.class);
    }

    @Override
    public void serialize(URI type, JsonGenerator generator, SerializerProvider provider)
            throws IOException {
        generator.writeString(
                rebased(type, provider.getAttribute(ProblemResponseSettings.SERIALIZATION_ATTRIBUTE))
                        .toString());
    }

    static URI rebased(URI type, Object settings) {
        String text = type.toString();
        if (settings instanceof ProblemResponseSettings configured && text.startsWith(CATALOGUE_PREFIX)) {
            return URI.create(configured.typeUriBase() + "/" + text.substring(CATALOGUE_PREFIX.length()));
        }
        return type;
    }
}
