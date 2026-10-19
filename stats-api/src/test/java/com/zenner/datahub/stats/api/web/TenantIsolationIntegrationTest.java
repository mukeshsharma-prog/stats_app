package com.zenner.datahub.stats.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenner.datahub.stats.api.support.ApiDatabase;
import com.zenner.datahub.stats.api.support.ApiIntegrationTest;
import com.zenner.datahub.stats.api.support.TestJwks;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * T-028 (read half), T-041, T-067 — tenant isolation on every read path, proved on rows
 * built to catch the wrong filter (STA-35, STA-35A, STA-35B; ADR-006).
 *
 * <p><strong>The fixtures are the test.</strong> Real records cannot catch a filter on
 * {@code maintenant}: it equals {@code tenant} on every tenant but one (DL-139). So:</p>
 * <ul>
 *   <li>tenant-beta's rows carry {@code maintenant = iso-alpha} — alpha's own tenant
 *       name. A filter on {@code maintenant} hands every one of them to alpha.</li>
 *   <li>the minol shape: {@code tenant = iso-minol-gateway}, {@code maintenant =
 *       iso-minol}. A filter on {@code maintenant} silently drops these rows for their
 *       own tenant, and hands them to a token that says {@code iso-minol} (DL-146).</li>
 *   <li>one gateway that moved from beta to alpha (D16, DL-142): its pre-move rows stay
 *       beta's.</li>
 *   <li>one {@code uid} held by both tenants — the key {@code (ts_device, uid)} leaves
 *       {@code tenant} out on purpose (rule 13), so this can exist.</li>
 * </ul>
 *
 * <p>This suite and the code it tests were written by the same author, which T-028 asks
 * not to happen for a security control. DL-162 records it. DL-172 records a Claude review
 * of it (four findings, all fixed, nine mutations); that review is not independent of the
 * author's tooling, and T-028 closes on Mukesh's sign-off of it.</p>
 */
class TenantIsolationIntegrationTest extends ApiIntegrationTest {

    private static final String ALPHA = "iso-alpha";
    private static final String BETA = "iso-beta";
    private static final String MINOL_GATEWAY = "iso-minol-gateway";
    private static final String MINOL = "iso-minol";

    private static final String ALPHA_GATEWAY = "0000000A1A1A";
    private static final String BETA_GATEWAY = "0000000B1B1B";
    private static final String MOVED_GATEWAY = "00000000D16D";
    private static final String MINOL_BOX = "00000000AB0C";
    private static final String UNKNOWN_GATEWAY = "0000000FFFF0";

    private static final long T0 = Instant.parse("2026-09-21T08:00:00Z").getEpochSecond();
    private static final String FROM = String.valueOf(T0);
    private static final String TO = String.valueOf(T0 + 3600);

    @BeforeAll
    static void rows() {
        ApiDatabase.insertAll(
                "lorastats",
                List.of(
                        ApiDatabase.lorastats("ISO-A-1", ALPHA, "iso-parent", ALPHA_GATEWAY, T0 + 10),
                        ApiDatabase.lorastats("ISO-A-2", ALPHA, "iso-parent", ALPHA_GATEWAY, T0 + 20),
                        ApiDatabase.lorastats("ISO-A-moved", ALPHA, ALPHA, MOVED_GATEWAY, T0 + 900),
                        ApiDatabase.lorastats("ISO-SHARED", ALPHA, ALPHA, ALPHA_GATEWAY, T0 + 30),
                        // beta: maintenant is ALPHA's tenant name — the trap.
                        ApiDatabase.lorastats("ISO-B-1", BETA, ALPHA, BETA_GATEWAY, T0 + 40),
                        ApiDatabase.lorastats("ISO-B-2", BETA, ALPHA, BETA_GATEWAY, T0 + 50),
                        ApiDatabase.lorastats("ISO-B-premove", BETA, BETA, MOVED_GATEWAY, T0 + 60),
                        ApiDatabase.lorastats("ISO-SHARED", BETA, ALPHA, BETA_GATEWAY, T0 + 70),
                        // minol: the one tenant where the two fields differ in production.
                        ApiDatabase.lorastats("ISO-M-1", MINOL_GATEWAY, MINOL, MINOL_BOX, T0 + 80),
                        ApiDatabase.lorastats("ISO-M-2", MINOL_GATEWAY, MINOL, MINOL_BOX, T0 + 90)));
    }

    @Test
    @DisplayName("alpha's list is exactly alpha's rows — none of beta's, though beta's maintenant is 'iso-alpha'")
    void alphaSeesExactlyItsOwnRows() throws Exception {
        JsonNode page = list(TestJwks.reader(ALPHA));
        assertThat(uids(page)).containsExactly("ISO-A-moved", "ISO-SHARED", "ISO-A-2", "ISO-A-1");
        page.get("items").forEach(item -> assertThat(item.get("tenant").asText()).isEqualTo(ALPHA));
    }

    @Test
    @DisplayName("beta's list is exactly beta's rows, maintenant and all")
    void betaSeesExactlyItsOwnRows() throws Exception {
        JsonNode page = list(TestJwks.reader(BETA));
        assertThat(uids(page)).containsExactly("ISO-SHARED", "ISO-B-premove", "ISO-B-2", "ISO-B-1");
        assertThat(page.at("/items/0/maintenant").asText()).isEqualTo(ALPHA);
    }

    @Test
    @DisplayName("minol: the gateway tenant sees its rows; a token naming the maintenant sees none")
    void minolIsScopedOnTenantNotMaintenant() throws Exception {
        assertThat(uids(list(TestJwks.reader(MINOL_GATEWAY)))).containsExactly("ISO-M-2", "ISO-M-1");
        assertThat(uids(list(TestJwks.reader(MINOL)))).isEmpty();
    }

    @Test
    @DisplayName("a uid held only by another tenant is a 404 identical to a uid that exists nowhere (T-041)")
    void aForeignUidIsIndistinguishableFromAMissingOne() throws Exception {
        String alpha = TestJwks.reader(ALPHA);
        MvcResult foreign = mvc.perform(get(alpha, "/v1/stats/lorastats/ISO-B-1")).andReturn();
        MvcResult missing = mvc.perform(get(alpha, "/v1/stats/lorastats/ISO-NOWHERE")).andReturn();

        assertThat(foreign.getResponse().getStatus()).isEqualTo(404).isEqualTo(missing.getResponse().getStatus());
        assertThat(foreign.getResponse().getContentType()).isEqualTo(missing.getResponse().getContentType());
        assertThat(new TreeSet<>(foreign.getResponse().getHeaderNames()))
                .isEqualTo(new TreeSet<>(missing.getResponse().getHeaderNames()));
        assertThat(normalised(foreign, "ISO-B-1")).isEqualTo(normalised(missing, "ISO-NOWHERE"));
    }

    @Test
    @DisplayName("a uid both tenants hold returns each tenant its own row")
    void aSharedUidIsResolvedPerTenant() throws Exception {
        JsonNode alpha = json(mvc.perform(get(TestJwks.reader(ALPHA), "/v1/stats/lorastats/ISO-SHARED")).andReturn());
        JsonNode beta = json(mvc.perform(get(TestJwks.reader(BETA), "/v1/stats/lorastats/ISO-SHARED")).andReturn());
        assertThat(alpha.get("tenant").asText()).isEqualTo(ALPHA);
        assertThat(alpha.at("/ts/device").asLong()).isEqualTo(T0 + 30);
        assertThat(beta.get("tenant").asText()).isEqualTo(BETA);
        assertThat(beta.at("/ts/device").asLong()).isEqualTo(T0 + 70);
    }

    @Test
    @DisplayName("another tenant's gateway is an empty 200 identical to an unknown gateway (T-067)")
    void aForeignGatewayIsIndistinguishableFromAnUnknownOne() throws Exception {
        String alpha = TestJwks.reader(ALPHA);
        MvcResult foreign = mvc.perform(get(alpha, "/v1/stats/lorastats/{c}/{f}", BETA_GATEWAY, FROM)).andReturn();
        MvcResult unknown = mvc.perform(get(alpha, "/v1/stats/lorastats/{c}/{f}", UNKNOWN_GATEWAY, FROM)).andReturn();
        assertThat(foreign.getResponse().getStatus()).isEqualTo(200).isEqualTo(unknown.getResponse().getStatus());
        assertThat(foreign.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(json(foreign).get("items")).isEmpty();
    }

    @Test
    @DisplayName("a gateway that moved tenant: its pre-move rows stay with the old tenant (D16)")
    void aMovedGatewayKeepsItsHistoryWithTheOldTenant() throws Exception {
        JsonNode alpha =
                json(mvc.perform(get(TestJwks.reader(ALPHA), "/v1/stats/lorastats/{c}/{f}", MOVED_GATEWAY, FROM)).andReturn());
        JsonNode beta =
                json(mvc.perform(get(TestJwks.reader(BETA), "/v1/stats/lorastats/{c}/{f}", MOVED_GATEWAY, FROM)).andReturn());
        assertThat(uids(alpha)).containsExactly("ISO-A-moved");
        assertThat(uids(beta)).containsExactly("ISO-B-premove");
    }

    @Test
    @DisplayName("a tenant sent by the caller — query, header, anywhere — is ignored")
    void aCallerSuppliedTenantIsIgnored() throws Exception {
        String alpha = TestJwks.reader(ALPHA);
        List<JsonNode> pages = new ArrayList<>();
        pages.add(json(mvc.perform(get(alpha, "/v1/stats/lorastats?from={f}&to={t}&tenant=" + BETA, FROM, TO)).andReturn()));
        pages.add(
                json(
                        mvc.perform(
                                        get(alpha, "/v1/stats/lorastats?from={f}&to={t}", FROM, TO)
                                                .header("X-Tenant", BETA)
                                                .header("tenant", BETA))
                                .andReturn()));
        for (JsonNode page : pages) {
            assertThat(uids(page)).containsExactly("ISO-A-moved", "ISO-SHARED", "ISO-A-2", "ISO-A-1");
        }
    }

    @Test
    @DisplayName("beta's cursor under alpha's token is invalid_cursor — the walk ends, it never continues as beta")
    void aCursorCannotCrossTenants() throws Exception {
        JsonNode betaPage =
                json(
                        mvc.perform(get(TestJwks.reader(BETA), "/v1/stats/lorastats?from={f}&to={t}&limit=1", FROM, TO))
                                .andExpect(status().isOk())
                                .andReturn());
        String cursor = betaPage.get("nextCursor").asText();
        MvcResult alpha =
                mvc.perform(get(TestJwks.reader(ALPHA), "/v1/stats/lorastats?from={f}&to={t}&limit=1&cursor={c}", FROM, TO, cursor))
                        .andReturn();
        assertThat(alpha.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(alpha).get("type").asText()).endsWith("/invalid_cursor");
    }

    @Test
    @DisplayName("a walk one row per page stays inside the caller's tenant on every page, not only the first")
    void everyPageOfAWalkIsTheCallersTenant() throws Exception {
        // The first page is the one every other test reads. A keyset predicate joined by OR
        // instead of AND leaves page one correct and hands over the other tenants' rows from
        // page two on (T-028 review, finding 3; DL-172).
        assertThat(walk(TestJwks.reader(ALPHA))).containsExactly("ISO-A-moved", "ISO-SHARED", "ISO-A-2", "ISO-A-1");
        assertThat(walk(TestJwks.reader(BETA))).containsExactly("ISO-SHARED", "ISO-B-premove", "ISO-B-2", "ISO-B-1");
    }

    private List<String> walk(String token) throws Exception {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            String path = "/v1/stats/lorastats?from={f}&to={t}&limit=1" + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = json(mvc.perform(get(token, path, FROM, TO)).andExpect(status().isOk()).andReturn());
            seen.addAll(uids(page));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
        } while (cursor != null && seen.size() <= 10);
        return seen;
    }

    private JsonNode list(String token) throws Exception {
        return json(mvc.perform(get(token, "/v1/stats/lorastats?from={f}&to={t}", FROM, TO)).andExpect(status().isOk()).andReturn());
    }

    /** The body with what legitimately differs per request removed: the trace id and the uid in instance. */
    private JsonNode normalised(MvcResult result, String uid) throws Exception {
        ObjectNode body = (ObjectNode) json(result);
        body.remove("traceId");
        body.put("instance", body.get("instance").asText().replace(uid, "<uid>"));
        return body;
    }

    private static List<String> uids(JsonNode page) {
        List<String> uids = new ArrayList<>();
        page.get("items").forEach(item -> uids.add(item.get("uid").asText()));
        return uids;
    }
}
