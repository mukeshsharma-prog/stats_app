package com.zenner.datahub.stats.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zenner.datahub.stats.api.support.ApiDatabase;
import com.zenner.datahub.stats.api.support.ApiIntegrationTest;
import com.zenner.datahub.stats.api.support.TestJwks;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

/**
 * An {@code ltestats} or {@code netstats} payload reaches no API log line
 * at ANY level — the read half of the rule {@code stats-input} and {@code stats-writer}
 * each test for their own side.
 *
 * <p>Rows carrying distinctive values in every sensitive column are read back on every
 * route, with this project's loggers, Spring Web, Spring JDBC and Spring Security at
 * {@code TRACE}, and the captured console is searched for each value. The raw IMSI
 * would be a failure even though the response itself only ever carries the masked
 * form.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "logging.level.com.zenner=TRACE",
        "logging.level.org.springframework.web=TRACE",
        "logging.level.org.springframework.http=TRACE",
        "logging.level.org.springframework.jdbc=TRACE",
        "logging.level.org.springframework.security=TRACE"
})
class LteNetstatsPayloadsAreNeverLoggedIntegrationTest extends ApiIntegrationTest {

    private static final String BOX = "04B64810652C";
    private static final long EPOCH = Instant.parse("2026-09-22T12:00:00Z").getEpochSecond();

    private static final String IMSI = "262017364019283";
    private static final String LAC = "5EC7AB0D";
    private static final String CID = "5EC7ED1";
    private static final String OPERATOR = "SentinelCarrier41";
    private static final String IP = "198.51.100.231";
    private static final String DNS = "192.0.2.199";
    private static final String EXTENSION = "payload-marker-5f1c";
    private static final List<String> SENTINELS = List.of(IMSI, LAC, CID, OPERATOR, IP, DNS, EXTENSION);

    @Test
    void noReadRouteLogsAPayloadValue(CapturedOutput output) throws Exception {
        String tenant = "log-guard-" + UUID.randomUUID();
        String lteUid = "LOG-GUARD-LTE-" + UUID.randomUUID();
        String netUid = "LOG-GUARD-NET-" + UUID.randomUUID();

        Map<String, Object> lte = ApiDatabase.envelope(lteUid, tenant, "log-guard-parent", BOX, EPOCH);
        lte.put("rssi", -67L);
        lte.put("access_technology", "4G");
        lte.put("operator", OPERATOR);
        lte.put("plmn", "26201");
        lte.put("imsi", IMSI);
        lte.put("lac", LAC);
        lte.put("cid", CID);
        lte.put("data_extra", "{\"future_member\":\"" + EXTENSION + "\"}");
        ApiDatabase.insert("ltestats", lte);

        Map<String, Object> net = ApiDatabase.envelope(netUid, tenant, "log-guard-parent", BOX, EPOCH);
        net.put("timespan", 300L);
        net.put("interfaces", "{\"eth0\":{\"tx\":1,\"rx\":2,\"ip\":\"" + IP + "\",\"dns\":[\"" + DNS + "\"]},"
                + "\"wwan\":{\"tx\":3,\"rx\":4}}");
        net.put("data_extra", "{\"a_member_longer_than_an_interface_name\":\"" + EXTENSION + "\"}");
        ApiDatabase.insert("netstats", net);

        String token = TestJwks.reader(tenant);
        for (String[] read : List.of(new String[] {"ltestats", lteUid}, new String[] {"netstats", netUid})) {
            String type = read[0];
            String body = mvc.perform(get(token, "/v1/stats/{type}/{uid}", type, read[1]))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(body).as("the row was really read back").contains(read[1]);
            mvc.perform(get(token, "/v1/stats/" + type + "?from=" + EPOCH + "&to=" + (EPOCH + 60)))
                    .andExpect(status().isOk());
            mvc.perform(get(token, "/v1/stats/" + type + "/" + BOX + "/" + EPOCH)).andExpect(status().isOk());
            mvc.perform(get(token, "/v1/stats/" + type + "/" + BOX + "/" + EPOCH + "/" + (EPOCH + 60)))
                    .andExpect(status().isOk());
        }

        String logs = output.getAll();
        // Proof the capture saw the levels this test asks for, not an empty console.
        assertThat(logs).contains("org.springframework.web");
        for (String sentinel : SENTINELS) {
            assertThat(logs).as("a log line carries the payload value %s", sentinel).doesNotContain(sentinel);
        }
    }
}
