package com.zenner.datahub.stats.api.cursor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.api.query.StatsQueryBuilder.Keyset;
import com.zenner.datahub.stats.common.model.StatType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The signed keyset cursor (openapi.yaml {@code Cursor}).
 *
 * <p><strong>What is in it, and what is not.</strong> The token is
 * {@code base64url(payload).base64url(HMAC-SHA256)}. The payload holds only the keyset
 * position — the last row's {@code ts_device} and {@code uid} — and an expiry. The
 * <em>binding</em> — operation, stat type, tenant, {@code clsbox}, {@code from},
 * {@code to} — is not in the payload at all: it is part of what the MAC covers, and on
 * every request it is recomputed from that request. So a cursor presented with any
 * filter changed, on another path or another type, or under another tenant's token,
 * fails verification exactly as a tampered one does, and all of them are
 * {@code 400 invalid_cursor}.</p>
 *
 * <p><strong>The cursor is never the tenant.</strong> The query's tenant comes from the
 * token on every page; the cursor can only fail to verify against it.
 * No tenant name travels in the token either, so a cursor that leaks into a log or a
 * support ticket discloses one {@code uid} and one timestamp of the caller's own data.</p>
 *
 * <p>Every replica must share the key ({@code stats.api.cursor.signing-key}); rotating it
 * invalidates every open walk, which ends in {@code invalid_cursor} and a restart from
 * page one — never in a wrong page.</p>
 */
public final class CursorCodec {

    public static final int MAX_LENGTH = 2048;

    private static final int VERSION = 1;
    private static final String DOMAIN = "stats-api-cursor-v1";
    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final Duration ttl;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public CursorCodec(String signingKey, Duration ttl, Clock clock, ObjectMapper objectMapper) {
        this.key = new SecretKeySpec(
                Objects.requireNonNull(signingKey, "signingKey").getBytes(StandardCharsets.UTF_8),
                ALGORITHM);
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Everything a walk is bound to. {@code clsbox} is the canonical uppercase value, so
     * the same gateway requested in either case continues the same walk; {@code clsbox}
     * and {@code to} are {@code null} where the operation has none.
     */
    public record Binding(
            String operation, StatType type, String tenant, String clsbox, long from, Long to) {
        public Binding {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(tenant, "tenant");
        }
    }

    public String issue(Binding binding, Keyset position) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("v", VERSION);
        payload.put("exp", clock.instant().plus(ttl).getEpochSecond());
        payload.put("d", position.tsDevice());
        payload.put("u", position.uid());
        byte[] bytes = serialize(payload);
        return ENCODER.encodeToString(bytes) + "." + ENCODER.encodeToString(mac(bytes, binding));
    }

    /**
     * The position to continue from.
     *
     * @throws InvalidCursorException for anything but a genuine, unexpired cursor issued
     *     for exactly this binding — deliberately without saying which check failed
     */
    public Keyset read(String token, Binding binding) {
        if (token == null || token.isEmpty() || token.length() > MAX_LENGTH) {
            throw new InvalidCursorException();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.') || dot == token.length() - 1) {
            throw new InvalidCursorException();
        }
        byte[] payload;
        byte[] signature;
        try {
            payload = DECODER.decode(token.substring(0, dot));
            signature = DECODER.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException notBase64) {
            throw new InvalidCursorException();
        }
        if (!MessageDigest.isEqual(signature, mac(payload, binding))) {
            throw new InvalidCursorException();
        }
        JsonNode fields;
        try {
            fields = objectMapper.readTree(payload);
        } catch (IOException unreadable) {
            throw new InvalidCursorException();
        }
        if (fields == null
                || fields.path("v").asInt(-1) != VERSION
                || !fields.path("exp").canConvertToLong()
                || !fields.path("d").canConvertToLong()
                || !fields.path("u").isTextual()
                || fields.path("u").asText().isEmpty()) {
            throw new InvalidCursorException();
        }
        if (clock.instant().getEpochSecond() > fields.get("exp").asLong()) {
            throw new InvalidCursorException();
        }
        return new Keyset(fields.get("d").asLong(), fields.get("u").asText());
    }

    private byte[] mac(byte[] payload, Binding binding) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update(canonical(payload, binding));
            return mac.doFinal();
        } catch (GeneralSecurityException unavailable) {
            // HmacSHA256 is mandatory on every Java platform; this is not a request error.
            throw new IllegalStateException("HmacSHA256 unavailable", unavailable);
        }
    }

    /** Length-prefixed, so no two different bindings can serialise to the same bytes. */
    private static byte[] canonical(byte[] payload, Binding binding) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeUTF(DOMAIN);
            out.writeInt(payload.length);
            out.write(payload);
            out.writeUTF(binding.operation());
            out.writeUTF(binding.type().wireName());
            out.writeUTF(binding.tenant());
            out.writeBoolean(binding.clsbox() != null);
            if (binding.clsbox() != null) {
                out.writeUTF(binding.clsbox());
            }
            out.writeLong(binding.from());
            out.writeBoolean(binding.to() != null);
            if (binding.to() != null) {
                out.writeLong(binding.to());
            }
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return bytes.toByteArray();
    }

    private byte[] serialize(ObjectNode payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    /** Any reason a cursor cannot be honoured. Carries no detail by design. */
    public static final class InvalidCursorException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidCursorException() {
            super("Invalid cursor", null, false, false);
        }
    }
}
