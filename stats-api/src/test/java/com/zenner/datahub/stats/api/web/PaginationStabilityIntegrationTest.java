package com.zenner.datahub.stats.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.zenner.datahub.stats.api.support.ApiDatabase;
import com.zenner.datahub.stats.api.support.ApiIntegrationTest;
import com.zenner.datahub.stats.api.support.TestJwks;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-046, STA-36A: a keyset walk visits every row that existed when it started exactly
 * once, skips none, and never repeats one — while the Writer keeps inserting into the
 * same range, including rows that sort above and below the walk's current position.
 *
 * <p>Four rows share every second of {@code ts_device}, so the {@code uid} tie-breaker
 * does real work: without it, a page boundary falling inside one second would repeat or
 * drop rows, and nothing else in this suite would notice.</p>
 */
class PaginationStabilityIntegrationTest extends ApiIntegrationTest {

    private static final int ROWS = 400;
    private static final int PAGE = 37;

    @Test
    @DisplayName("tenant-wide list: every pre-existing row exactly once, in strict order, under concurrent inserts")
    void tenantWideWalkIsStable() throws Exception {
        long t0 = Instant.parse("2026-09-22T06:00:00Z").getEpochSecond();
        String tenant = "page-list";
        walkUnderLoad(tenant, "00000000AB01", t0, "/v1/stats/lorastats?from=" + t0 + "&to=" + (t0 + 3600));
    }

    @Test
    @DisplayName("device path, open-ended: every pre-existing row exactly once, in strict order, under concurrent inserts")
    void deviceWalkIsStable() throws Exception {
        long t0 = Instant.parse("2026-09-22T09:00:00Z").getEpochSecond();
        String gateway = "00000000AB02";
        walkUnderLoad("page-device", gateway, t0, "/v1/stats/lorastats/" + gateway + "/" + t0);
    }

    private void walkUnderLoad(String tenant, String gateway, long t0, String firstPage) throws Exception {
        List<Map<String, Object>> existing = new ArrayList<>();
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < ROWS; i++) {
            String uid = String.format("PG-%s-%05d", tenant, i);
            existing.add(ApiDatabase.lorastats(uid, tenant, "page-parent", gateway, t0 + i / 4));
            expected.add(uid);
        }
        ApiDatabase.insertAll("lorastats", existing);

        String token = TestJwks.reader(tenant);
        AtomicBoolean walking = new AtomicBoolean(true);
        AtomicInteger inserted = new AtomicInteger();
        ExecutorService writer = Executors.newSingleThreadExecutor();
        Future<?> load =
                writer.submit(
                        () -> {
                            while (walking.get()) {
                                int n = inserted.getAndIncrement();
                                // Spread across the whole range: some above, some below the walk.
                                long ts = t0 + (n * 7L) % (ROWS / 4);
                                ApiDatabase.insert(
                                        "lorastats",
                                        ApiDatabase.lorastats(
                                                String.format("PG-%s-new-%05d", tenant, n), tenant, "page-parent", gateway, ts));
                            }
                        });

        List<JsonNode> seen = new ArrayList<>();
        try {
            String cursor = null;
            int pages = 0;
            do {
                String path = firstPage + (firstPage.contains("?") ? "&" : "?") + "limit=" + PAGE
                        + (cursor == null ? "" : "&cursor=" + cursor);
                JsonNode page = json(mvc.perform(get(token, path)).andExpect(status().isOk()).andReturn());
                page.get("items").forEach(seen::add);
                cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
                pages++;
            } while (cursor != null && pages < 1000);
        } finally {
            walking.set(false);
            load.get();
            writer.shutdown();
        }

        assertThat(inserted.get()).as("rows inserted during the walk").isGreaterThan(0);
        List<String> uids = seen.stream().map(item -> item.get("uid").asText()).toList();
        assertThat(uids).as("no row twice").doesNotHaveDuplicates();
        assertThat(uids).as("every row that existed at the start").containsAll(expected);
        for (int i = 1; i < seen.size(); i++) {
            JsonNode previous = seen.get(i - 1);
            JsonNode current = seen.get(i);
            long previousTs = previous.at("/ts/device").asLong();
            long currentTs = current.at("/ts/device").asLong();
            boolean descending =
                    previousTs > currentTs
                            || (previousTs == currentTs
                                    && previous.get("uid").asText().compareTo(current.get("uid").asText()) > 0);
            assertThat(descending).as("strictly (ts_device DESC, uid DESC) at position %d", i).isTrue();
        }
    }
}
