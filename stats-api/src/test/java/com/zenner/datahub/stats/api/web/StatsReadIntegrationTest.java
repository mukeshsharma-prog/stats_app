package com.zenner.datahub.stats.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.zenner.datahub.stats.api.support.ApiDatabase;
import com.zenner.datahub.stats.api.support.ApiIntegrationTest;
import com.zenner.datahub.stats.api.support.TestJwks;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.test.web.servlet.MvcResult;

/**
 * T-040, T-041, T-044, T-067 against the Writer's real tables, read as {@code stats_ro}:
 * the record each type returns, the bounds, the window, every error the contract
 * catalogues, and a cursor walk to its end.
 */
class StatsReadIntegrationTest extends ApiIntegrationTest {

    private static final String TENANT = "read-alpha";
    private static final String PARENT = "read-parent";
    private static final String GATEWAY = "04B64810652B";
    private static final String WALK_GATEWAY = "04B6481064F9";
    private static final long T0 = Instant.parse("2026-09-20T12:00:00Z").getEpochSecond();
    private static final String TOKEN = TestJwks.reader(TENANT);

    @BeforeAll
    static void rows() {
        Map<String, Object> lora = ApiDatabase.lorastats("CLSCON-read-lora", TENANT, PARENT, GATEWAY, T0);
        lora.put("record_version", (short) 1);
        lora.put("rx_nr_pkts", 219L);
        lora.put("rx_crc_bad", 48L);
        lora.put("rx_short_pkt", 166L);
        lora.put("fsk_received", 0L);
        lora.put("timespan", 1800L);
        lora.put(
                "passthrough",
                "{\"ts\":{\"datastore\":" + T0 + ",\"converter\":" + T0
                        + ",\"expiresOn\":\"2026-08-27T12:28:59.074Z\"},\"status\":[\"conversion ok\"]}");
        lora.put("data_extra", "{\"new_counter\":7}");
        ApiDatabase.insert("lorastats", lora);

        Map<String, Object> sys = ApiDatabase.envelope("CLSCON-read-sys", TENANT, PARENT, GATEWAY, T0);
        sys.put("memfree", 123_456L);
        sys.put("uptime", 98_765.5);
        sys.put("idletime", 12.25);
        sys.put("load1", 0.5);
        sys.put("load5", 0.25);
        sys.put("load15", 0.125);
        sys.put("runable", "2/139");
        sys.put("temp", 62_269L);
        sys.put("wan_if", "wwan");
        sys.put("timespan", 300L);
        sys.put(
                "passthrough",
                "{\"ts\":{\"deviceOrig\":" + (T0 - 3) + ",\"expiresOn\":{\"$date\":\"2026-09-20T12:00:00Z\"}},"
                        + "\"meta\":{\"tsDeviceCorrected\":true}}");
        ApiDatabase.insert("sysstats", sys);

        Map<String, Object> oms = ApiDatabase.envelope("CLSCON-read-oms", TENANT, PARENT, GATEWAY, T0);
        oms.put("rx_nr_pkts", 10L);
        oms.put("rx_valid_nr_pkts", 9L);
        oms.put("rx_crc_bad", 1L);
        oms.put("device_count", 3L);
        oms.put("timespan", 3600L);
        ApiDatabase.insert("omsstats", oms);

        // The list's bounds: at from (in), inside, at to (out).
        ApiDatabase.insertAll(
                "lorastats",
                List.of(
                        ApiDatabase.lorastats("RANGE-at-from", TENANT, PARENT, "0000000000A1", T0 + 7200),
                        ApiDatabase.lorastats("RANGE-inside", TENANT, PARENT, "0000000000A1", T0 + 7300),
                        ApiDatabase.lorastats("RANGE-at-to", TENANT, PARENT, "0000000000A1", T0 + 10_800)));

        // Five rows on one gateway for a walk, one of them with a clock 2 minutes fast.
        List<Map<String, Object>> walk = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            walk.add(ApiDatabase.lorastats("WALK-" + i, TENANT, PARENT, WALK_GATEWAY, T0 + 20_000 + i));
        }
        walk.add(
                ApiDatabase.lorastats(
                        "WALK-future", TENANT, PARENT, WALK_GATEWAY, Instant.now().getEpochSecond() + 120));
        ApiDatabase.insertAll("lorastats", walk);
    }

    @Test
    @DisplayName("lorastats: the production envelope, passthrough and data_extra back verbatim; unset counters omitted")
    void lorastatsRecord() throws Exception {
        String expected =
                """
                {"uid":"CLSCON-read-lora","tenant":"read-alpha","maintenant":"read-parent",
                 "clsbox":"04B64810652B","type":"lorastats","version":1,
                 "ts":{"server":%1$d,"device":%1$d,"datastore":%1$d,"converter":%1$d,
                       "expiresOn":"2026-08-27T12:28:59.074Z"},
                 "status":["conversion ok"],
                 "data":{"rx_nr_pkts":219,"rx_crc_missing":0,"rx_crc_bad":48,"tx_success":0,
                         "tx_failed":0,"tx_peek":0,"tx_peek_index_zero":0,"tx_override":0,
                         "tx_busy":0,"tx_dequeue_error":0,"tx_type_beacon":0,"chip_comm_error":0,
                         "util_fopen_failed":0,"rx_short_pkt":166,"fsk_received":0,
                         "timespan":1800,"new_counter":7}}
                """
                        .formatted(T0);
        MvcResult result =
                mvc.perform(get(TOKEN, "/v1/stats/lorastats/CLSCON-read-lora"))
                        .andExpect(status().isOk())
                        .andReturn();
        JSONAssert.assertEquals(expected, result.getResponse().getContentAsString(), JSONCompareMode.STRICT);
    }

    @Test
    @DisplayName("sysstats: timespan back as the string it arrived as; $date expiresOn, deviceOrig and meta preserved")
    void sysstatsRecord() throws Exception {
        String expected =
                """
                {"uid":"CLSCON-read-sys","tenant":"read-alpha","maintenant":"read-parent",
                 "clsbox":"04B64810652B","type":"sysstats",
                 "ts":{"server":%1$d,"device":%1$d,"deviceOrig":%2$d,
                       "expiresOn":{"$date":"2026-09-20T12:00:00Z"}},
                 "meta":{"tsDeviceCorrected":true},
                 "data":{"memfree":123456,"uptime":98765.5,"idletime":12.25,"load1":0.5,
                         "load5":0.25,"load15":0.125,"runable":"2/139","temp":62269,
                         "wan_if":"wwan","timespan":"300"}}
                """
                        .formatted(T0, T0 - 3);
        MvcResult result =
                mvc.perform(get(TOKEN, "/v1/stats/sysstats/CLSCON-read-sys")).andExpect(status().isOk()).andReturn();
        JSONAssert.assertEquals(expected, result.getResponse().getContentAsString(), JSONCompareMode.STRICT);
    }

    @Test
    @DisplayName("omsstats: five integers, and no converter-derived member anywhere (ADR-004)")
    void omsstatsRecord() throws Exception {
        String expected =
                """
                {"uid":"CLSCON-read-oms","tenant":"read-alpha","maintenant":"read-parent",
                 "clsbox":"04B64810652B","type":"omsstats","ts":{"server":%1$d,"device":%1$d},
                 "data":{"rx_nr_pkts":10,"rx_valid_nr_pkts":9,"rx_crc_bad":1,"device_count":3,"timespan":3600}}
                """
                        .formatted(T0);
        MvcResult result =
                mvc.perform(get(TOKEN, "/v1/stats/omsstats/CLSCON-read-oms")).andExpect(status().isOk()).andReturn();
        JSONAssert.assertEquals(expected, result.getResponse().getContentAsString(), JSONCompareMode.STRICT);
    }

    @Test
    @DisplayName("the list is [from, to): the record at from is in, the record at to is out, newest first")
    void theListIsHalfOpen() throws Exception {
        JsonNode page =
                json(
                        mvc.perform(get(TOKEN, "/v1/stats/lorastats?from={f}&to={t}", T0 + 7200, T0 + 10_800))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(uids(page)).containsExactly("RANGE-inside", "RANGE-at-from");
        assertThat(page.get("limit").asInt()).isEqualTo(100);
        assertThat(page.has("nextCursor")).isTrue();
        assertThat(page.get("nextCursor").isNull()).isTrue();
    }

    @Test
    @DisplayName("query_window_exceeded above the window on the tenant-wide list; exactly the window is fine")
    void theWindowIsEnforcedOnTheListOnly() throws Exception {
        mvc.perform(get(TOKEN, "/v1/stats/lorastats?from={f}&to={t}", T0, T0 + 3600)).andExpect(status().isOk());
        JsonNode problem =
                problem(mvc.perform(get(TOKEN, "/v1/stats/lorastats?from={f}&to={t}", T0, T0 + 3601)).andReturn(), 400);
        assertThat(problem.get("type").asText()).isEqualTo("https://stats.example/errors/query_window_exceeded");
        assertThat(problem.at("/errors/0/field").asText()).isEqualTo("to");
        // No window on the device paths: from the year 2000, open-ended.
        mvc.perform(get(TOKEN, "/v1/stats/lorastats/{c}/946684800", GATEWAY)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("from >= to is invalid_time_range, on the list and on the bounded device path")
    void anEmptyRangeIsInvalid() throws Exception {
        JsonNode list = problem(mvc.perform(get(TOKEN, "/v1/stats/lorastats?from={f}&to={f}", T0, T0)).andReturn(), 400);
        assertThat(list.get("type").asText()).endsWith("/invalid_time_range");
        assertThat(list.get("statType").asText()).isEqualTo("lorastats");
        JsonNode device =
                problem(mvc.perform(get(TOKEN, "/v1/stats/lorastats/{c}/{f}/{t}", GATEWAY, T0 + 1, T0)).andReturn(), 400);
        assertThat(device.at("/errors/0/code").asText()).isEqualTo("invalid_time_range");
    }

    @Test
    @DisplayName("a millisecond timestamp is invalid_parameter — never reinterpreted as seconds")
    void millisecondsAreRejected() throws Exception {
        JsonNode problem =
                problem(
                        mvc.perform(get(TOKEN, "/v1/stats/lorastats?from={f}&to={t}", T0 * 1000, T0 * 1000 + 1)).andReturn(),
                        400);
        assertThat(problem.get("type").asText()).endsWith("/invalid_parameter");
        assertThat(problem.findValuesAsText("field")).containsExactly("from", "to");
    }

    @Test
    @DisplayName("every malformed parameter is reported at once, by field, without its value")
    void everyBadParameterIsNamed() throws Exception {
        JsonNode problem = problem(mvc.perform(get(TOKEN, "/v1/stats/lorastats?to=abc&limit=0")).andReturn(), 400);
        assertThat(problem.findValuesAsText("field")).containsExactly("from", "to", "limit");
        assertThat(problem.toString()).doesNotContain("abc");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1001", "-1", "ten", "1.5"})
    @DisplayName("limit outside 1..1000 is invalid_parameter")
    void limitIsBounded(String limit) throws Exception {
        JsonNode problem =
                problem(
                        mvc.perform(get(TOKEN, "/v1/stats/lorastats?from={f}&to={t}&limit={l}", T0, T0 + 60, limit))
                                .andReturn(),
                        400);
        assertThat(problem.at("/errors/0/field").asText()).isEqualTo("limit");
    }

    @Test
    @DisplayName("an unknown stat type is unknown_stat_type and names no type back")
    void unknownTypes() throws Exception {
        JsonNode problem = problem(mvc.perform(get(TOKEN, "/v1/stats/wifistats?from=1&to=2")).andReturn(), 400);
        assertThat(problem.get("type").asText()).isEqualTo("https://stats.example/errors/unknown_stat_type");
        assertThat(problem.has("statType")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ltestats", "netstats"})
    @DisplayName("an empty table returns an empty page and 404 by uid")
    void emptyTables(String type) throws Exception {
        JsonNode page =
                json(mvc.perform(get(TOKEN, "/v1/stats/{ty}?from={f}&to={t}", type, T0, T0 + 60)).andExpect(status().isOk()).andReturn());
        assertThat(page.get("items")).isEmpty();
        mvc.perform(get(TOKEN, "/v1/stats/{ty}/{c}/{f}", type, GATEWAY, T0)).andExpect(status().isOk());
        problem(mvc.perform(get(TOKEN, "/v1/stats/{ty}/CLSCON-read-lora", type)).andReturn(), 404);
    }

    @Test
    @DisplayName("the open-ended device read returns a record from a clock running fast — never to = now")
    void openEndedIncludesTheNearFuture() throws Exception {
        JsonNode page =
                json(mvc.perform(get(TOKEN, "/v1/stats/lorastats/{c}/{f}", WALK_GATEWAY, T0)).andExpect(status().isOk()).andReturn());
        assertThat(uids(page)).containsExactly("WALK-future", "WALK-3", "WALK-2", "WALK-1", "WALK-0");
    }

    @Test
    @DisplayName("the bounded device read is [from, to), and clsbox is matched in either case")
    void boundedDeviceRange() throws Exception {
        JsonNode page =
                json(
                        mvc.perform(get(TOKEN, "/v1/stats/lorastats/{c}/{f}/{t}", WALK_GATEWAY.toLowerCase(), T0 + 20_001, T0 + 20_003))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(uids(page)).containsExactly("WALK-2", "WALK-1");
        assertThat(page.at("/items/0/clsbox").asText()).isEqualTo(WALK_GATEWAY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"04B64810652", "04B64810652BX", "ZZB64810652B"})
    @DisplayName("a clsbox that is not 12 hex characters is invalid_parameter before any query")
    void clsboxIsValidated(String clsbox) throws Exception {
        JsonNode problem = problem(mvc.perform(get(TOKEN, "/v1/stats/lorastats/{c}/{f}", clsbox, T0)).andReturn(), 400);
        assertThat(problem.at("/errors/0/field").asText()).isEqualTo("clsbox");
    }

    @Test
    @DisplayName("a walk follows nextCursor to a null, visiting every row once, in order")
    void aCursorWalkEnds() throws Exception {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            String path = "/v1/stats/lorastats/" + WALK_GATEWAY + "/" + T0 + "?limit=2"
                    + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = json(mvc.perform(get(TOKEN, path)).andExpect(status().isOk()).andReturn());
            seen.addAll(uids(page));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactly("WALK-future", "WALK-3", "WALK-2", "WALK-1", "WALK-0");
    }

    @Test
    @DisplayName("a cursor presented with a changed filter, or on another path, is invalid_cursor")
    void aCursorIsBoundToItsWalk() throws Exception {
        JsonNode first =
                json(mvc.perform(get(TOKEN, "/v1/stats/lorastats/{c}/{f}?limit=1", WALK_GATEWAY, T0)).andReturn());
        String cursor = first.get("nextCursor").asText();
        JsonNode changedFrom =
                problem(
                        mvc.perform(get(TOKEN, "/v1/stats/lorastats/{c}/{f}?limit=1&cursor={k}", WALK_GATEWAY, T0 + 1, cursor))
                                .andReturn(),
                        400);
        assertThat(changedFrom.get("type").asText()).endsWith("/invalid_cursor");
        problem(
                mvc.perform(
                                get(TOKEN, "/v1/stats/lorastats/{c}/{f}/{t}?limit=1&cursor={k}", WALK_GATEWAY, T0, T0 + 30_000, cursor))
                        .andReturn(),
                400);
        problem(
                mvc.perform(get(TOKEN, "/v1/stats/sysstats/{c}/{f}?limit=1&cursor={k}", WALK_GATEWAY, T0, cursor)).andReturn(),
                400);
    }

    @Test
    @DisplayName("every problem is application/problem+json, catalogue-typed, and carries the X-Trace-Id")
    void problemsCarryTheTraceId() throws Exception {
        MvcResult result = mvc.perform(get(TOKEN, "/v1/stats/lorastats/NOPE-nothing")).andReturn();
        JsonNode problem = problem(result, 404);
        assertThat(problem.get("type").asText()).isEqualTo("https://stats.example/errors/record_not_found");
        assertThat(problem.get("instance").asText()).isEqualTo("/v1/stats/lorastats/NOPE-nothing");
        assertThat(problem.get("traceId").asText()).isEqualTo(result.getResponse().getHeader("X-Trace-Id"));
    }

    @Test
    @DisplayName("a 200 carries X-Trace-Id too")
    void successCarriesTheTraceId() throws Exception {
        mvc.perform(get(TOKEN, "/v1/stats/omsstats/CLSCON-read-oms"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    private JsonNode problem(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        return json(result);
    }

    private static List<String> uids(JsonNode page) {
        List<String> uids = new ArrayList<>();
        page.get("items").forEach(item -> uids.add(item.get("uid").asText()));
        return uids;
    }
}
