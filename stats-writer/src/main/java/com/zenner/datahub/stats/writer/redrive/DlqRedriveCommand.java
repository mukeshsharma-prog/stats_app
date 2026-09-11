package com.zenner.datahub.stats.writer.redrive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import com.rabbitmq.client.LongString;
import com.zenner.datahub.stats.common.amqp.AmqpTopology;
import com.zenner.datahub.stats.common.model.StatType;
import com.zenner.datahub.stats.writer.retry.FailedDeliveryRouter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Moves a bounded batch from one dead-letter queue after confirming each replacement.
 * The command requires dedicated operator credentials and emits no payload values.
 */
public final class DlqRedriveCommand {
    static final int MAX_RATE = 2_667;
    private static final Pattern CHANGE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern SAFE_FAILURE = Pattern.compile("[A-Za-z0-9.$_]{1,200}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private DlqRedriveCommand() {}

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        ConnectionFactory factory = connectionFactory(System.getenv());
        try (Connection connection = factory.newConnection("stats-dlq-redrive:" + options.changeId());
                Channel channel = connection.createChannel()) {
            int moved = redrive(channel, options);
            System.out.printf(
                    "{\"event\":\"redrive_completed\",\"changeId\":\"%s\","
                            + "\"type\":\"%s\",\"moved\":%d}%n",
                    options.changeId(), options.type().wireName(), moved);
        }
    }

    static int redrive(Channel channel, Options options) throws Exception {
        String source = AmqpTopology.deadLetterQueue(options.type());
        String target = AmqpTopology.mainQueue(options.type());
        channel.queueDeclarePassive(source);
        channel.queueDeclarePassive(target);
        channel.confirmSelect();

        AtomicReference<String> returned = new AtomicReference<>();
        channel.addReturnListener(
                (code, text, exchange, key, properties, body) -> returned.set(code + " " + text));

        int moved = 0;
        int snapshotLimit = options.maxMessages();
        boolean snapshotTaken = false;
        long nextPublish = System.nanoTime();
        while (moved < snapshotLimit) {
            GetResponse delivery = channel.basicGet(source, false);
            if (delivery == null) {
                break;
            }
            if (!snapshotTaken) {
                // Bound this invocation to records that were ready when its first
                // delivery arrived. A fast repeat failure returns at the tail and
                // must wait for another explicit operator invocation.
                snapshotLimit = Math.min(options.maxMessages(), delivery.getMessageCount() + 1);
                snapshotTaken = true;
            }
            try {
                validate(delivery, options);
                nextPublish = pace(nextPublish, options.ratePerSecond());
                returned.set(null);
                AMQP.BasicProperties properties = redriveProperties(delivery, options, moved + 1);
                channel.basicPublish(
                        AmqpTopology.INPUT_EXCHANGE,
                        AmqpTopology.routingKey(options.type()),
                        true,
                        properties,
                        delivery.getBody());
                if (!channel.waitForConfirms(options.confirmTimeoutMillis())) {
                    throw new IllegalStateException("Broker negatively acknowledged the redrive copy");
                }
                if (returned.get() != null) {
                    throw new IllegalStateException("Redrive copy was returned: " + returned.get());
                }
                // The original leaves the DLQ only after its replacement is confirmed.
                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                moved++;
                System.out.printf(
                        "{\"event\":\"record_redriven\",\"changeId\":\"%s\","
                                + "\"type\":\"%s\",\"sequence\":%d,"
                                + "\"previousAttempt\":\"%s\","
                                + "\"previousFailure\":\"%s\","
                                + "\"bodySha256\":\"%s\"}%n",
                        options.changeId(),
                        options.type().wireName(),
                        moved,
                        safeAttempt(delivery),
                        safeFailure(delivery),
                        sha256(delivery.getBody()));
            } catch (Exception failure) {
                try {
                    channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
                } catch (Exception requeueFailure) {
                    failure.addSuppressed(requeueFailure);
                }
                throw failure;
            }
        }
        return moved;
    }

    private static void validate(GetResponse delivery, Options options) throws Exception {
        JsonNode body;
        try {
            body = JSON.readTree(delivery.getBody());
        } catch (JsonProcessingException unsafeFailure) {
            // Parser messages can quote source bytes, so this boundary replaces them
            // with a static diagnostic before they can reach an operator terminal.
            throw new IllegalArgumentException("DLQ message is not valid JSON");
        }
        if (!options.type().wireName().equals(body.path("type").asText())) {
            throw new IllegalArgumentException("DLQ message type does not match selected type");
        }
        if (!body.path("ts").path("device").canConvertToLong()) {
            throw new IllegalArgumentException("DLQ message has no usable ts.device");
        }
        Instant device = Instant.ofEpochSecond(body.path("ts").path("device").longValue());
        Instant cutoff = Instant.now().minus(options.retentionDays(), ChronoUnit.DAYS);
        if (!device.isAfter(cutoff)) {
            throw new IllegalStateException(
                    "DLQ message is outside the retention window; redrive would only increment "
                            + "records_expired_on_arrival. Use an approved disposition instead");
        }
    }

    static AMQP.BasicProperties redriveProperties(
            GetResponse delivery, Options options, int sequence) {
        Map<String, Object> headers = new HashMap<>();
        if (delivery.getProps().getHeaders() != null) {
            headers.putAll(delivery.getProps().getHeaders());
        }
        Object attempt = headers.get(FailedDeliveryRouter.ATTEMPT_HEADER);
        Object failure = headers.get(FailedDeliveryRouter.FAILURE_HEADER);
        headers.remove("x-death");
        if (attempt != null) {
            headers.put("x-stats-redrive-previous-attempt", attempt);
            headers.put(FailedDeliveryRouter.ATTEMPT_HEADER, attempt);
        }
        if (failure != null) {
            headers.put("x-stats-redrive-previous-failure", failure);
        }
        headers.put(FailedDeliveryRouter.MANUAL_REDRIVE_HEADER, true);
        headers.put("x-stats-redrive-change-id", options.changeId());
        headers.put("x-stats-redrive-at", Instant.now().toString());
        headers.put("x-stats-redrive-sequence", sequence);
        return delivery.getProps().builder().headers(headers).build();
    }

    private static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String safeAttempt(GetResponse delivery) {
        Object attempt = header(delivery, FailedDeliveryRouter.ATTEMPT_HEADER);
        return attempt instanceof Number number
                ? Integer.toString(Math.max(0, number.intValue()))
                : "unknown";
    }

    static String safeFailure(GetResponse delivery) {
        Object failure = header(delivery, FailedDeliveryRouter.FAILURE_HEADER);
        if (failure instanceof String text) {
            return safeFailureText(text);
        }
        if (failure instanceof LongString encoded && encoded.length() <= 200) {
            return safeFailureText(
                    new String(encoded.getBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return "unknown";
    }

    private static String safeFailureText(String text) {
        return SAFE_FAILURE.matcher(text).matches() ? text : "unknown";
    }

    private static Object header(GetResponse delivery, String name) {
        Map<String, Object> headers = delivery.getProps().getHeaders();
        return headers == null ? null : headers.get(name);
    }

    private static long pace(long previous, int rate) throws InterruptedException {
        long interval = TimeUnit.SECONDS.toNanos(1) / rate;
        long scheduled = previous + interval;
        long wait = scheduled - System.nanoTime();
        if (wait > 0) {
            TimeUnit.NANOSECONDS.sleep(wait);
        }
        return Math.max(scheduled, System.nanoTime());
    }

    static ConnectionFactory connectionFactory(Map<String, String> environment) throws Exception {
        String user = required(environment, "RABBITMQ_REDRIVE_USER");
        String password = required(environment, "RABBITMQ_REDRIVE_PASSWORD");
        ConnectionFactory factory = new ConnectionFactory();
        String uri = environment.get("RABBITMQ_REDRIVE_URI");
        if (uri != null && !uri.isBlank()) {
            factory.setUri(uri);
            factory.setUsername(user);
            factory.setPassword(password);
        } else {
            factory.setHost(environment.getOrDefault("RABBITMQ_HOST", "localhost"));
            factory.setPort(Integer.parseInt(environment.getOrDefault("RABBITMQ_PORT", "5672")));
            factory.setVirtualHost(environment.getOrDefault("RABBITMQ_VHOST", "/"));
            factory.setUsername(user);
            factory.setPassword(password);
        }
        return factory;
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be set to dedicated operator credentials");
        }
        return value;
    }

    record Options(
            StatType type,
            int maxMessages,
            int ratePerSecond,
            int retentionDays,
            long confirmTimeoutMillis,
            String changeId,
            boolean rootCauseResolved) {
        Options {
            if (!rootCauseResolved) {
                throw new IllegalArgumentException(
                        "root-cause-resolved=true is required before manual redrive");
            }
        }

        static Options parse(String[] args) {
            Map<String, String> values = new HashMap<>();
            for (String argument : args) {
                if (!argument.startsWith("--") || !argument.contains("=")) {
                    throw usage();
                }
                String[] pair = argument.substring(2).split("=", 2);
                if (values.put(pair[0], pair[1]) != null) {
                    throw new IllegalArgumentException("Duplicate option --" + pair[0]);
                }
            }
            StatType type = StatType.fromWireName(required(values, "type"));
            int maximum = positive(values, "max-messages");
            int rate = positive(values, "rate-per-second");
            int retention = positive(values, "retention-days");
            String change = required(values, "change-id");
            boolean rootCauseResolved = "true".equals(values.get("root-cause-resolved"));
            if (rate > MAX_RATE) {
                throw new IllegalArgumentException("rate-per-second must not exceed " + MAX_RATE);
            }
            if (!CHANGE_ID.matcher(change).matches()) {
                throw new IllegalArgumentException("change-id must contain only letters, digits, dot, underscore or hyphen");
            }
            long timeout = Long.parseLong(values.getOrDefault("confirm-timeout-ms", "5000"));
            if (timeout <= 0 || values.keySet().stream().anyMatch(key -> !java.util.Set.of(
                    "type", "max-messages", "rate-per-second", "retention-days", "change-id",
                    "root-cause-resolved", "confirm-timeout-ms").contains(key))) {
                throw usage();
            }
            return new Options(
                    type,
                    maximum,
                    rate,
                    retention,
                    timeout,
                    change,
                    rootCauseResolved);
        }

        private static int positive(Map<String, String> values, String name) {
            int value = Integer.parseInt(required(values, name));
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return value;
        }

        private static IllegalArgumentException usage() {
            return new IllegalArgumentException(
                    "Required: --type --max-messages --rate-per-second --retention-days "
                            + "--change-id --root-cause-resolved=true");
        }
    }
}
