package com.zenner.datahub.stats.api.cursor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenner.datahub.stats.api.cursor.CursorCodec.Binding;
import com.zenner.datahub.stats.api.cursor.CursorCodec.InvalidCursorException;
import com.zenner.datahub.stats.api.query.StatsQueryBuilder.Keyset;
import com.zenner.datahub.stats.common.model.StatType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T-042, STA-36A: a cursor is honoured only for exactly the walk it was issued for, under
 * the tenant it was issued to, before it expires, unedited. Everything else is the same
 * {@code InvalidCursorException} — and, above it, the same {@code 400 invalid_cursor}.
 */
class CursorCodecTest {

    private static final String KEY = "unit-test-cursor-signing-key-0123456789";
    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Binding WALK =
            new Binding("device-range", StatType.LORASTATS, "tenant-alpha", "04B64810652B", 1_789_000_000L, 1_789_003_600L);
    private static final Keyset POSITION = new Keyset(1_789_001_234L, "CLSCON-11d3ec4c");

    private final CursorCodec codec = codec(KEY, NOW);

    private static CursorCodec codec(String key, Instant now) {
        return new CursorCodec(key, Duration.ofMinutes(15), Clock.fixed(now, ZoneOffset.UTC), JSON);
    }

    @Test
    @DisplayName("a cursor read back under its own binding gives the position it was issued at")
    void roundTrips() {
        assertThat(codec.read(codec.issue(WALK, POSITION), WALK)).isEqualTo(POSITION);
    }

    @Test
    @DisplayName("the tenant is not in the token: only the signature knows it")
    void carriesNoTenantName() {
        String token = codec.issue(WALK, POSITION);
        String payload =
                new String(Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))), StandardCharsets.UTF_8);
        assertThat(payload).doesNotContain("tenant-alpha").doesNotContain("04B64810652B");
    }

    static Stream<Binding> otherWalks() {
        return Stream.of(
                new Binding("device-range", StatType.LORASTATS, "tenant-beta", "04B64810652B", 1_789_000_000L, 1_789_003_600L),
                new Binding("device-range", StatType.SYSSTATS, "tenant-alpha", "04B64810652B", 1_789_000_000L, 1_789_003_600L),
                new Binding("device-range", StatType.LORASTATS, "tenant-alpha", "04B64810652C", 1_789_000_000L, 1_789_003_600L),
                new Binding("device-range", StatType.LORASTATS, "tenant-alpha", "04B64810652B", 1_789_000_001L, 1_789_003_600L),
                new Binding("device-range", StatType.LORASTATS, "tenant-alpha", "04B64810652B", 1_789_000_000L, 1_789_003_601L),
                new Binding("device-range", StatType.LORASTATS, "tenant-alpha", "04B64810652B", 1_789_000_000L, null),
                new Binding("device-from", StatType.LORASTATS, "tenant-alpha", "04B64810652B", 1_789_000_000L, 1_789_003_600L),
                new Binding("list", StatType.LORASTATS, "tenant-alpha", null, 1_789_000_000L, 1_789_003_600L));
    }

    @ParameterizedTest
    @MethodSource("otherWalks")
    @DisplayName("another tenant, type, gateway, bound or operation cannot continue the walk")
    void anyChangedBindingIsRefused(Binding other) {
        String token = codec.issue(WALK, POSITION);
        assertThatThrownBy(() -> codec.read(token, other)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("an edited payload is refused even when it is well-formed")
    void anEditedPayloadIsRefused() {
        String token = codec.issue(WALK, POSITION);
        String signature = token.substring(token.indexOf('.') + 1);
        String forged =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                "{\"v\":1,\"exp\":9999999999,\"d\":4102444800,\"u\":\"x\"}"
                                        .getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> codec.read(forged + "." + signature, WALK))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("a cursor signed with another key is refused — a rotated key ends every walk")
    void anotherKeyIsRefused() {
        String token = codec("another-cursor-signing-key-0123456789abc", NOW).issue(WALK, POSITION);
        assertThatThrownBy(() -> codec.read(token, WALK)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("a cursor is refused once its time-to-live has passed, and honoured up to it")
    void expires() {
        String token = codec.issue(WALK, POSITION);
        assertThat(codec(KEY, NOW.plus(Duration.ofMinutes(15))).read(token, WALK)).isEqualTo(POSITION);
        assertThatThrownBy(() -> codec(KEY, NOW.plus(Duration.ofMinutes(15)).plusSeconds(1)).read(token, WALK))
                .isInstanceOf(InvalidCursorException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "nodot", "a.b.c", ".abc", "abc.", "!!!.???", "e30.e30"})
    @DisplayName("malformed tokens are refused without an exception of any other kind")
    void malformedTokensAreRefused(String token) {
        assertThatThrownBy(() -> codec.read(token, WALK)).isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("a token longer than the contract's 2048 characters is refused unread")
    void overlongTokensAreRefused() {
        assertThatThrownBy(() -> codec.read("a".repeat(CursorCodec.MAX_LENGTH + 1), WALK))
                .isInstanceOf(InvalidCursorException.class);
    }

    @Test
    @DisplayName("an issued cursor fits the contract's maxLength with room to spare")
    void issuedTokensFitTheContract() {
        Keyset longest = new Keyset(4_102_444_800L, "u".repeat(128));
        assertThat(codec.issue(WALK, longest)).hasSizeLessThan(CursorCodec.MAX_LENGTH / 4);
    }
}
