///usr/bin/env java --source 21 "$0" "$@"; exit $?
// Burst load against stats-input.
//
// Dependency-free: `java ops/load/BurstLoad.java --rate 13333 --duration 30`. Java 21 only —
// virtual threads carry the in-flight requests, java.net.http carries the traffic.
//
// OPEN loop, on purpose. A closed loop (N workers, each waiting for its answer) slows down
// exactly when the server does, so it measures the client's patience; an open loop keeps
// sending at the target rate whatever comes back, which is what Heimdal does during a
// replay (2,667/s agreed, unenforceable by us) and what turns a burst into 503s.
// If the server falls behind, in-flight requests pile up until --max-in-flight, after
// which the generator counts what it could not send instead of silently slowing down.
//
// What it reports, per run: achieved rate, status counts, client-side latency percentiles,
// per-second 503s -- and, from a before/after scrape of Input's /actuator/prometheus,
// the publisher-confirm histogram's delta as p50/p99/mean, computed the way Prometheus's
// histogram_quantile does. Confirm latency, not throughput, is the number the task asks
// for: it is what a 200 waits on, and a burst shows up there first.
//
// Every record gets a unique uid and ts.* re-stamped to now: duplicates would be absorbed
// by ON CONFLICT and hide the Writer's real cost, and a fixture dated 27 Aug
// would trip the clock-skew gate or the late-arrival guard.
//
// The replay drill adds the opposite mode, on purpose. --save-bodies FILE keeps every
// body this run sent, byte for byte; --replay FILE sends those bodies again, cyclically,
// at the target rate, with NO re-stamping -- a Heimdal replay is the same record again,
// same uid and same ts.device, which is exactly what ON CONFLICT (ts_device, uid) keys on.
// A replayed body that was re-stamped would be a NEW record to the Writer and would
// measure nothing about duplicate suppression. ops/drills/replay.sh runs the shape.

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BurstLoad {

    /** Log prefix: the task whose shape this run has. */
    static String TAG = "[burst-load]";

    private static final Pattern UID = Pattern.compile("\"uid\"\\s*:\\s*\"[^\"]*\"");
    private static final Pattern TS = Pattern.compile("\"(server|device|datastore|converter)\"\\s*:\\s*\\d+");
    private static final Pattern BUCKET = Pattern.compile(
            "^records_publish_confirm_seconds_bucket\\{([^}]*)\\}\\s+([0-9.eE+]+)$", Pattern.MULTILINE);
    private static final Pattern COUNT_SUM = Pattern.compile(
            "^records_publish_confirm_seconds_(count|sum)\\{([^}]*)\\}\\s+([0-9.eE+]+)$", Pattern.MULTILINE);

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parse(args);
        String url = opt.getOrDefault("url", "http://localhost:8081/internal/stats");
        int rate = Integer.parseInt(opt.getOrDefault("rate", "13333"));
        int seconds = Integer.parseInt(opt.getOrDefault("duration", "30"));
        int warmup = Integer.parseInt(opt.getOrDefault("warmup", "5"));
        int maxInFlight = Integer.parseInt(opt.getOrDefault("max-in-flight", "2000"));
        Path fixture = Path.of(opt.getOrDefault("fixture", "docs/schemas/fixtures/valid/lorastats-production-canonical.json"));
        String metrics = opt.getOrDefault("metrics", URI.create(url).resolve("/actuator/prometheus").toString());
        String template = Files.readString(fixture, StandardCharsets.UTF_8);
        String runId = Long.toHexString(System.currentTimeMillis());
        List<String> corpus = opt.containsKey("replay") ? Corpus.read(Path.of(opt.get("replay"))) : null;
        Corpus save = opt.containsKey("save-bodies") ? Corpus.open(Path.of(opt.get("save-bodies"))) : null;
        TAG = corpus != null ? "[replay]" : "[burst-load]";

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        if (corpus != null) {
            System.out.printf(Locale.ROOT, "%s REPLAY of %,d stored bodies from %s (byte-identical, no re-stamp)  target %s  rate %,d req/s  duration %d s%n",
                    TAG, corpus.size(), opt.get("replay"), url, rate, seconds);
        } else {
            System.out.printf(Locale.ROOT, "%s target %s  rate %,d req/s  duration %d s  warm-up %d s  fixture %s  run %s%n",
                    TAG, url, rate, seconds, warmup, fixture.getFileName(), runId);
        }
        if (warmup > 0 && corpus == null) {
            // The warm-up is never saved: its bodies are not part of the history a replay repeats.
            Run w = run(client, url, template, runId + "-w", Math.min(rate, 500), warmup, maxInFlight, false, null, null);
            System.out.printf(Locale.ROOT, "%s warm-up: %,d requests, %s%n", TAG, w.sent.sum(), w.statuses());
        }

        String before = scrape(client, metrics);
        Run r = run(client, url, template, runId, rate, seconds, maxInFlight, true, corpus, save);
        String after = scrape(client, metrics);
        if (save != null) {
            save.close();
            System.out.printf(Locale.ROOT, "%s saved %,d bodies to %s%n", TAG, r.sent.sum(), opt.get("save-bodies"));
        }

        long[] lat = r.latencies();
        double achieved = r.sent.sum() / (r.elapsed.toNanos() / 1e9);
        System.out.println();
        System.out.printf(Locale.ROOT, "%s RESULT  sent=%,d in %.1f s  => achieved %,.0f req/s of %,d target  (could not send: %,d over --max-in-flight %d)%n",
                TAG, r.sent.sum(), r.elapsed.toNanos() / 1e9, achieved, rate, r.shed.sum(), maxInFlight);
        System.out.printf(Locale.ROOT, "%s   statuses: %s%n", TAG, r.statuses());
        System.out.printf(Locale.ROOT, "%s   client latency (request to response): p50=%.1f ms  p90=%.1f ms  p99=%.1f ms  max=%.1f ms%n",
                TAG, pct(lat, 0.50) / 1e6, pct(lat, 0.90) / 1e6, pct(lat, 0.99) / 1e6, lat.length == 0 ? 0 : lat[lat.length - 1] / 1e6);
        System.out.printf(Locale.ROOT, "%s   per-second 503s: %s%n", TAG, r.perSecond503());
        Histogram h = Histogram.delta(before, after);
        if (h == null) {
            System.out.println(TAG + "   publisher-confirm histogram: not exported by this Input -- client latency is the only number");
        } else {
            System.out.printf(Locale.ROOT, "%s   publisher-confirm (server-side, outcome=confirmed): n=%,d  mean=%.1f ms  p50=%.1f ms  p99=%.1f ms  outcomes=%s%n",
                    TAG, h.count, h.mean() * 1e3, h.quantile(0.50) * 1e3, h.quantile(0.99) * 1e3, h.outcomes);
        }
        if (opt.containsKey("json")) {
            Files.writeString(Path.of(opt.get("json")), r.json(rate, achieved, lat, h), StandardCharsets.UTF_8);
        }
    }

    // ---- the run -----------------------------------------------------------

    static final class Run {
        final LongAdder sent = new LongAdder(), shed = new LongAdder();
        final Map<Integer, LongAdder> status = new ConcurrentHashMap<>();
        final Map<Long, LongAdder> s503 = new ConcurrentHashMap<>();
        final List<Long> lat = new ArrayList<>();
        Duration elapsed = Duration.ZERO;
        long startNanos;

        synchronized void record(long nanos) { lat.add(nanos); }

        long[] latencies() {
            long[] a; synchronized (this) { a = lat.stream().mapToLong(Long::longValue).toArray(); }
            Arrays.sort(a); return a;
        }

        String statuses() {
            return new TreeMap<>(status).entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().sum()).toList().toString();
        }

        String perSecond503() {
            return new TreeMap<>(s503).entrySet().stream().map(e -> "s" + e.getKey() + ":" + e.getValue().sum()).toList().toString();
        }

        String json(int rate, double achieved, long[] lat, Histogram h) {
            StringBuilder b = new StringBuilder("{\n");
            b.append("  \"targetRate\": ").append(rate).append(",\n  \"achievedRate\": ").append(String.format(Locale.ROOT, "%.1f", achieved)).append(",\n");
            b.append("  \"sent\": ").append(sent.sum()).append(",\n  \"shed\": ").append(shed.sum()).append(",\n");
            b.append("  \"statuses\": {").append(new TreeMap<>(status).entrySet().stream().map(e -> "\"" + e.getKey() + "\": " + e.getValue().sum()).reduce((x, y) -> x + ", " + y).orElse("")).append("},\n");
            b.append(String.format(Locale.ROOT, "  \"clientLatencyMs\": {\"p50\": %.2f, \"p90\": %.2f, \"p99\": %.2f, \"max\": %.2f},\n",
                    pct(lat, .5) / 1e6, pct(lat, .9) / 1e6, pct(lat, .99) / 1e6, lat.length == 0 ? 0 : lat[lat.length - 1] / 1e6));
            if (h != null) {
                b.append(String.format(Locale.ROOT, "  \"confirmLatencyMs\": {\"count\": %d, \"mean\": %.2f, \"p50\": %.2f, \"p99\": %.2f}\n",
                        h.count, h.mean() * 1e3, h.quantile(.5) * 1e3, h.quantile(.99) * 1e3));
            } else {
                b.append("  \"confirmLatencyMs\": null\n");
            }
            return b.append("}\n").toString();
        }
    }

    static Run run(HttpClient client, String url, String template, String runId, int rate, int seconds, int maxInFlight, boolean verbose,
                   List<String> corpus, Corpus save) throws InterruptedException {
        Run r = new Run();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicLong seq = new AtomicLong();
        ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();
        long intervalNanos = 1_000_000_000L / rate;
        long total = (long) rate * seconds;
        r.startNanos = System.nanoTime();
        long next = r.startNanos;
        for (long i = 0; i < total; i++) {
            long now = System.nanoTime();
            if (now < next) {
                LockSupportPark.until(next);
            }
            next += intervalNanos;
            if (inFlight.get() >= maxInFlight) {
                r.shed.increment();
                continue;
            }
            long n = seq.incrementAndGet();
            String body = corpus != null ? corpus.get((int) ((n - 1) % corpus.size())) : stamp(template, runId, n);
            if (save != null) {
                save.append(body);
            }
            inFlight.incrementAndGet();
            r.sent.increment();
            long sec = (now - r.startNanos) / 1_000_000_000L;
            vt.execute(() -> {
                long t0 = System.nanoTime();
                int code;
                try {
                    HttpResponse<Void> resp = client.send(HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                            HttpResponse.BodyHandlers.discarding());
                    code = resp.statusCode();
                } catch (IOException | InterruptedException e) {
                    code = 0; // transport failure: connection refused, reset, timeout
                } finally {
                    inFlight.decrementAndGet();
                }
                r.record(System.nanoTime() - t0);
                r.status.computeIfAbsent(code, k -> new LongAdder()).increment();
                if (code == 503) {
                    r.s503.computeIfAbsent(sec, k -> new LongAdder()).increment();
                }
            });
            if (verbose && i % rate == rate - 1) {
                System.out.printf(Locale.ROOT, "%s   t=%2ds  sent=%,d  in-flight=%d  %s%n", TAG, (i + 1) / rate, r.sent.sum(), inFlight.get(), r.statuses());
            }
        }
        vt.shutdown();
        vt.awaitTermination(60, TimeUnit.SECONDS);
        r.elapsed = Duration.ofNanos(System.nanoTime() - r.startNanos);
        return r;
    }

    /** A busy-free wait to an absolute nanoTime; parkNanos overshoots by ~50 µs, which the open loop absorbs. */
    static final class LockSupportPark {
        static void until(long deadline) {
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0) {
                if (remaining > 200_000) {
                    java.util.concurrent.locks.LockSupport.parkNanos(remaining - 100_000);
                } else {
                    Thread.onSpinWait();
                }
            }
        }
    }

    /**
     * The bodies a run sent, one file, separated by ASCII RS (0x1E) -- a byte that cannot
     * occur in JSON text, so pretty-printed bodies with newlines survive unchanged. What is
     * read back is what went on the wire.
     */
    static final class Corpus {
        private static final char RS = '\u001e';
        private final java.io.Writer out;

        private Corpus(java.io.Writer out) { this.out = out; }

        static Corpus open(Path path) throws IOException {
            return new Corpus(Files.newBufferedWriter(path, StandardCharsets.UTF_8));
        }

        synchronized void append(String body) {
            try {
                out.write(body);
                out.write(RS);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        synchronized void close() throws IOException { out.close(); }

        static List<String> read(Path path) throws IOException {
            String all = Files.readString(path, StandardCharsets.UTF_8);
            List<String> bodies = new ArrayList<>();
            for (String b : all.split(String.valueOf(RS))) {
                if (!b.isEmpty()) bodies.add(b);
            }
            if (bodies.isEmpty()) throw new IllegalArgumentException("no bodies in " + path);
            return bodies;
        }
    }

    static String stamp(String template, String runId, long n) {
        long now = Instant.now().getEpochSecond();
        Matcher m = UID.matcher(template);
        String out = m.replaceFirst("\"uid\": \"CLSCON-load-" + runId + "-" + n + "\"");
        return TS.matcher(out).replaceAll("\"$1\": " + now);
    }

    static long pct(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        return sorted[(int) Math.min(sorted.length - 1, Math.floor(sorted.length * p))];
    }

    // ---- the server-side histogram ---------------------------------------

    static String scrape(HttpClient client, String metricsUrl) {
        try {
            return client.send(HttpRequest.newBuilder(URI.create(metricsUrl)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            return "";
        }
    }

    static final class Histogram {
        final TreeMap<Double, Double> cumulative = new TreeMap<>(); // le -> count delta (summed over types)
        long count; double sum;
        final Map<String, Long> outcomes = new LinkedHashMap<>();

        static Histogram delta(String before, String after) {
            Map<String, Double> b = buckets(before), a = buckets(after);
            if (a.isEmpty()) return null;
            Histogram h = new Histogram();
            for (var e : a.entrySet()) {
                String key = e.getKey();
                double d = e.getValue() - b.getOrDefault(key, 0.0);
                String[] parts = key.split("\\|"); // outcome|le
                if (parts[0].equals("confirmed")) {
                    double le = parts[1].equals("+Inf") ? Double.POSITIVE_INFINITY : Double.parseDouble(parts[1]);
                    h.cumulative.merge(le, d, Double::sum);
                }
            }
            for (var e : countSum(after).entrySet()) {
                String[] parts = e.getKey().split("\\|"); // outcome|count or sum
                double d = e.getValue() - countSum(before).getOrDefault(e.getKey(), 0.0);
                if (parts[1].equals("count")) h.outcomes.merge(parts[0], (long) d, Long::sum);
                if (parts[0].equals("confirmed")) {
                    if (parts[1].equals("count")) h.count += (long) d; else h.sum += d;
                }
            }
            return h;
        }

        static Map<String, Double> buckets(String scrape) {
            Map<String, Double> out = new LinkedHashMap<>();
            Matcher m = BUCKET.matcher(scrape);
            while (m.find()) {
                String labels = m.group(1);
                String outcome = label(labels, "outcome"), le = label(labels, "le");
                out.merge(outcome + "|" + le, Double.parseDouble(m.group(2)), Double::sum);
            }
            return out;
        }

        static Map<String, Double> countSum(String scrape) {
            Map<String, Double> out = new LinkedHashMap<>();
            Matcher m = COUNT_SUM.matcher(scrape);
            while (m.find()) {
                out.merge(label(m.group(2), "outcome") + "|" + m.group(1), Double.parseDouble(m.group(3)), Double::sum);
            }
            return out;
        }

        static String label(String labels, String name) {
            Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(labels);
            return m.find() ? m.group(1) : "";
        }

        double mean() { return count == 0 ? 0 : sum / count; }

        /** Prometheus's histogram_quantile: linear interpolation inside the bucket that crosses the rank. */
        double quantile(double q) {
            if (cumulative.isEmpty()) return 0;
            double total = cumulative.lastEntry().getValue();
            if (total <= 0) return 0;
            double rank = q * total, prevLe = 0, prevCount = 0;
            for (var e : cumulative.entrySet()) {
                if (e.getValue() >= rank) {
                    if (Double.isInfinite(e.getKey())) return prevLe;
                    double inBucket = e.getValue() - prevCount;
                    return inBucket <= 0 ? e.getKey() : prevLe + (e.getKey() - prevLe) * ((rank - prevCount) / inBucket);
                }
                prevLe = e.getKey(); prevCount = e.getValue();
            }
            return prevLe;
        }
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String k = args[i].substring(2);
                String v = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
                m.put(k, v);
            }
        }
        return m;
    }
}
