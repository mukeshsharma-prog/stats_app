package com.zenner.datahub.stats.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.api.support.ApiDatabase;
import com.zenner.datahub.stats.api.support.ApiIntegrationTest;
import com.zenner.datahub.stats.api.support.TestJwks;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** New readers against production migrations/grants with real JWT validation. */
class LteNetstatsReadIntegrationTest extends ApiIntegrationTest {
    private static final String BOX = "04B64810652B";
    private static final long EPOCH = Instant.parse("2026-09-21T12:00:00Z").getEpochSecond();
    private static final List<String> COUNTERS =
            List.of("tx", "rx", "mtu", "fail_count", "last_forced_reset", "last_powercycle");

    @ParameterizedTest
    @ValueSource(strings = {"ltestats-production", "ltestats-production-2g-no-rsrp", "ltestats-production-unknown-technology",
            "netstats-production-healthy", "netstats-production-wwan-down", "netstats-extra-interface", "netstats-no-apipa",
            "ltestats-live-2026-10-09", "netstats-live-2026-10-09"})
    void fixturesRoundTripOnEveryRoute(String name) throws Exception {
        ObjectNode data = data(name);
        String type = name.startsWith("lte") ? "ltestats" : "netstats";
        String tenant = "new-read-" + UUID.randomUUID();
        String uid = "READ-" + UUID.randomUUID();
        insert(type, uid, tenant, "different-parent", BOX, EPOCH, data);
        String token = TestJwks.reader(tenant);
        JsonNode record = json(mvc.perform(get(token, "/v1/stats/{type}/{uid}", type, uid))
                .andExpect(status().isOk()).andReturn());
        assertReadBack(record.path("data"), data);
        assertThat(record.path("tenant").asText()).isEqualTo(tenant);
        assertThat(record.path("maintenant").asText()).isEqualTo("different-parent");
        assertThat(record.at("/ts/converter").asLong()).isEqualTo(EPOCH + 9);
        assertThat(record.at("/ts/deviceOrig").asLong()).isEqualTo(EPOCH - 11);
        assertThat(record.at("/meta/tsDeviceCorrected").asBoolean()).isTrue();
        for (String path : List.of("/v1/stats/" + type + "?from=" + EPOCH + "&to=" + (EPOCH + 60),
                "/v1/stats/" + type + "/" + BOX.toLowerCase(java.util.Locale.ROOT) + "/" + EPOCH,
                "/v1/stats/" + type + "/" + BOX + "/" + EPOCH + "/" + (EPOCH + 60))) {
            JsonNode page = json(mvc.perform(get(token, path)).andExpect(status().isOk()).andReturn());
            assertThat(page.path("items")).hasSize(1);
            assertReadBack(page.at("/items/0/data"), data);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ltestats", "netstats"})
    void extensionsAndLargeNumbersKeepTheirWireTypes(String type) throws Exception {
        ObjectNode data = data(type.equals("ltestats") ? "ltestats-production" : "netstats-production-healthy");
        if (type.equals("ltestats")) {
            data.put("rssi", "-9223372036854775808").put("timespan", "9223372036854775807");
            data.put("imsi", "00012345678901234567").put("lac", "00ab").put("cid", "FFFFFFFFFFFFFFFF");
            data.putObject("future_info").put("source", "new modem");
        } else {
            data.putObject("ppp2").put("tx", "9223372036854775807").put("rx", "0")
                    .putObject("new_member").put("precision", "9999999999999999999999999");
            data.putObject("future setting").put("active", true);
        }
        String tenant = "bounds-" + UUID.randomUUID();
        String uid = "BOUNDS-" + UUID.randomUUID();
        insert(type, uid, tenant, "parent", BOX, EPOCH, data);
        JsonNode record = json(mvc.perform(get(TestJwks.reader(tenant), "/v1/stats/{type}/{uid}", type, uid))
                .andExpect(status().isOk()).andReturn());
        assertReadBack(record.path("data"), data);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ltestats", "netstats"})
    void tenantAndMovedGatewayIsolationIncludeEveryNewReadRoute(String type) throws Exception {
        String alpha = "alpha-" + UUID.randomUUID();
        String beta = "beta-" + UUID.randomUUID();
        String alphaUid = "ALPHA-" + UUID.randomUUID();
        String betaUid = "BETA-" + UUID.randomUUID();
        ObjectNode data = data(type.equals("ltestats") ? "ltestats-production" : "netstats-production-wwan-down");
        // Same gateway after a move. Beta's maintenant intentionally equals alpha's token tenant.
        insert(type, alphaUid, alpha, "alpha-parent", BOX, EPOCH + 1, data);
        insert(type, betaUid, beta, alpha, BOX, EPOCH, data);
        String token = TestJwks.reader(alpha);
        mvc.perform(get(token, "/v1/stats/{type}/{uid}", type, betaUid)).andExpect(status().isNotFound());
        mvc.perform(get(token, "/v1/stats/{type}/absent-uid", type)).andExpect(status().isNotFound());
        for (String path : List.of("/v1/stats/" + type + "?from=" + EPOCH + "&to=" + (EPOCH + 60),
                "/v1/stats/" + type + "/" + BOX + "/" + EPOCH,
                "/v1/stats/" + type + "/" + BOX + "/" + EPOCH + "/" + (EPOCH + 60))) {
            JsonNode page = json(mvc.perform(get(token, path)).andExpect(status().isOk()).andReturn());
            assertThat(page.path("items")).hasSize(1);
            assertThat(page.at("/items/0/uid").asText()).isEqualTo(alphaUid);
        }
        String minolGateway = "minol-gateway-" + UUID.randomUUID();
        String minol = "minol-" + UUID.randomUUID();
        insert(type, "MINOL-" + UUID.randomUUID(), minolGateway, minol, BOX, EPOCH, data);
        assertThat(json(mvc.perform(get(TestJwks.reader(minol), "/v1/stats/{type}/{box}/{from}", type, BOX, EPOCH))
                .andExpect(status().isOk()).andReturn()).path("items")).isEmpty();
        assertThat(json(mvc.perform(get(TestJwks.reader(minolGateway), "/v1/stats/{type}/{box}/{from}", type, BOX, EPOCH))
                .andExpect(status().isOk()).andReturn()).path("items")).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ltestats", "netstats"})
    void keysetPagingIsStableAndTenantBound(String type) throws Exception {
        String tenant = "pages-" + UUID.randomUUID();
        ObjectNode data = data(type.equals("ltestats") ? "ltestats-production-unknown-technology" : "netstats-production-healthy");
        for (int i = 0; i < 5; i++) insert(type, "PAGE-" + i, tenant, "parent", BOX, EPOCH, data);
        String token = TestJwks.reader(tenant);
        String path = "/v1/stats/" + type + "/" + BOX + "/" + EPOCH + "?limit=2";
        JsonNode first = json(mvc.perform(get(token, path)).andExpect(status().isOk()).andReturn());
        String firstCursor = first.path("nextCursor").asText();
        mvc.perform(get(TestJwks.reader("foreign"), path + "&cursor=" + firstCursor)).andExpect(status().isBadRequest());
        String otherType = type.equals("ltestats") ? "netstats" : "ltestats";
        mvc.perform(get(token, path.replace(type, otherType) + "&cursor=" + firstCursor)).andExpect(status().isBadRequest());
        List<String> seen = new ArrayList<>();
        JsonNode page = first;
        do {
            page.path("items").forEach(row -> seen.add(row.path("uid").asText()));
            if (page.path("nextCursor").isNull()) break;
            page = json(mvc.perform(get(token, path + "&cursor=" + page.path("nextCursor").asText()))
                    .andExpect(status().isOk()).andReturn());
        } while (seen.size() < 10);
        assertThat(seen).containsExactly("PAGE-4", "PAGE-3", "PAGE-2", "PAGE-1", "PAGE-0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ltestats", "netstats"})
    void newTablesAndTheirPartitionsAreReadOnly(String type) throws Exception {
        ObjectNode data = data(type.equals("ltestats") ? "ltestats-production-unknown-technology" : "netstats-production-healthy");
        insert(type, "GRANT-" + UUID.randomUUID(), "grant-tenant", "parent", BOX, EPOCH, data);
        try (var connection = DriverManager.getConnection(ApiDatabase.jdbcUrl(), ApiDatabase.READ_ONLY, ApiDatabase.READ_ONLY_PASSWORD);
                var statement = connection.createStatement()) {
            assertThat(statement.executeQuery("SELECT count(*) FROM " + type).next()).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM " + type))
                    .isInstanceOf(java.sql.SQLException.class);
        }
    }

    /**
     * Everything goes back exactly as stored except {@code imsi}, which leaves the API
     * masked on every route: same length, the carrier prefix and at most
     * the last four digits visible, and the raw value nowhere in the response.
     */
    private static void assertReadBack(JsonNode actual, ObjectNode stored) {
        ObjectNode expectedRest = stored.deepCopy();
        ObjectNode actualRest = ((ObjectNode) actual).deepCopy();
        expectedRest.remove("imsi");
        actualRest.remove("imsi");
        assertThat(actualRest).isEqualTo(expectedRest);
        if (!stored.has("imsi")) {
            assertThat(actual.has("imsi")).isFalse();
            return;
        }
        String raw = stored.path("imsi").asText();
        String masked = actual.path("imsi").asText();
        assertThat(masked).isNotEqualTo(raw).hasSameSizeAs(raw).matches("^[0-9]{0,6}\\*+[0-9]{0,4}$");
        assertThat(actual.toString()).doesNotContain(raw);
        String plmn = stored.path("plmn").asText();
        if (raw.startsWith(plmn) && raw.length() - plmn.length() >= 8) {
            assertThat(masked).startsWith(plmn).endsWith(raw.substring(raw.length() - 4));
        }
    }

    private ObjectNode data(String fixture) throws Exception {
        try (var input = getClass().getResourceAsStream("/valid/" + fixture + ".json")) {
            return (ObjectNode) objectMapper.readTree(input).path("data");
        }
    }

    private void insert(String type, String uid, String tenant, String parent, String box, long epoch, ObjectNode data) throws Exception {
        Map<String, Object> row = ApiDatabase.envelope(uid, tenant, parent, box, epoch);
        row.put("passthrough", "{\"ts\":{\"converter\":" + (epoch + 9) + ",\"deviceOrig\":" + (epoch - 11)
                + ",\"expiresOn\":{\"$date\":\"2026-12-01T12:00:00Z\"}},\"meta\":{\"tsDeviceCorrected\":true}}");
        ObjectNode extras = objectMapper.createObjectNode();
        if (type.equals("ltestats")) {
            List<String> numeric = List.of("rssi", "rsrp", "rsrq", "timespan");
            List<String> text = List.of("operator", "plmn", "imsi", "lac", "cid", "etxt");
            for (var entry : data.properties()) {
                if (numeric.contains(entry.getKey())) row.put(entry.getKey(), entry.getValue().asLong());
                else if (text.contains(entry.getKey())) row.put(entry.getKey(), entry.getValue().asText());
                else if (entry.getKey().equals("access_Technology")) row.put("access_technology", entry.getValue().asText());
                else extras.set(entry.getKey(), entry.getValue());
            }
        } else {
            ObjectNode interfaces = objectMapper.createObjectNode();
            row.put("timespan", data.path("timespan").asLong());
            for (var entry : data.properties()) {
                if (entry.getKey().equals("timespan")) continue;
                if (entry.getKey().matches("[A-Za-z][A-Za-z0-9_.:-]{0,14}")) {
                    ObjectNode iface = entry.getValue().deepCopy();
                    for (String field : COUNTERS) if (iface.has(field)) iface.put(field, iface.path(field).asLong());
                    interfaces.set(entry.getKey(), iface);
                } else extras.set(entry.getKey(), entry.getValue());
            }
            row.put("interfaces", objectMapper.writeValueAsString(interfaces));
        }
        if (!extras.isEmpty()) row.put("data_extra", objectMapper.writeValueAsString(extras));
        ApiDatabase.insert(type, row);
    }
}
