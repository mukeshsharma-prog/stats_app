# DLQ manual redrive runbook

## Purpose and safety contract

Use this procedure to give selected records from one statistic-type dead-letter queue
one operator-authorised processing attempt after the recorded root cause has been fixed.
It is not a background job and it must never be scheduled. Nothing consumes a DLQ
automatically: records remain there until an operator explicitly redrives them or an
approved disposition removes them.

A manual redrive preserves the payload, `x-stats-attempt`, `x-stats-failure` and
`x-stats-permanent`. It adds audit headers and `x-stats-manual-redrive=true`. If the
Writer fails that delivery, the marker sends it directly back to the same type's DLQ;
it does not receive another three-tier retry allocation. The new DLQ copy records the
latest safe failure class while `x-stats-redrive-previous-failure` retains the reason
that existed when the operator redrove it.

The DLQ has no TTL. A record remains available for investigation and disposition, but
operations must act within 14 days. PostgreSQL retention is based on `ts.device` and is
90 days. The command refuses a record at or beyond that boundary because processing it
would only increment `records_expired_on_arrival`; it would not recover a database row.
Since T-050 (DL-147) that counter is alerted: **if `StatsAppLateArrival` fires while a
redrive is running, stop the redrive** — the records being replayed are past the window
and are being skipped, not recovered. The redriven copies also carry the original
publish timestamp and `x-stats-manual-redrive`, so they are excluded from
`queue_oldest_message_age_seconds` and a redrive does not look like a backlog.

## Required authority

Use a dedicated operations identity scoped to exactly one source DLQ and its matching
main queue. Do not use the Writer application account or a RabbitMQ administrator.
Provisioning and revocation are performed by a RabbitMQ administrator under the change
record. For `lorastats`, the minimum resource permissions are:

```bash
redrive_user='stats_redrive_lorastats'
read -rsp 'New redrive password: ' redrive_password
printf '\n'

docker exec stats-rabbitmq rabbitmqctl add_user "$redrive_user" "$redrive_password"
docker exec stats-rabbitmq rabbitmqctl set_permissions -p / "$redrive_user" \
  '^(app_statsstore\.lorastats|app_statsstore\.lorastats\.dlq)$' \
  '^app_stats_input$' \
  '^app_statsstore\.lorastats\.dlq$'
docker exec stats-rabbitmq rabbitmqctl set_topic_permissions -p / "$redrive_user" \
  app_stats_input '^lorastats$' '^$'
```

The resource permissions grant passive inspection of the two queues, write access to
the existing topic exchange, and consumption only from the selected DLQ. The topic
permission limits publication to the selected type's routing key, so exchange access
cannot be used to redrive another type. Use the equivalent exact queue names and routing
key for another approved type. Store the secret in the approved secret manager, not in
this repository, shell history, the command line or the change record. Revoke temporary
access only after the redrive and its verification are complete. Deleting the account
before the pilot makes authentication impossible.

## Preconditions — stop unless every item is true

1. Open an incident or change record and obtain the required operations approval. Its
   identifier must contain only letters, digits, `.`, `_` or `-`.
2. Name exactly one statistic type. A blanket, multi-type redrive is prohibited.
3. Record the root cause and evidence that its fix is deployed. Exercise the fixed path
   with a new valid record before touching the DLQ.
4. Confirm the Writer is ready, its consumer is available, PostgreSQL is healthy, and
   main queue depth is stable.
5. Capture DLQ depth and the duration alerts from Prometheus/Grafana. Monitoring never
   inspects or requeues a DLQ message. Investigate every record approaching the 90-day
   device-time boundary; the command will refuse an expired record.
6. Select a rate no greater than 2,667 records/second (twice nominal). Begin with a
   single-record pilot and a low rate.
7. Create a protected evidence directory. It may contain operational identifiers and
   hashes but must never contain payloads, passwords or tokens.

Example preflight:

```bash
change_id='INC-123'
stat_type='lorastats'
evidence_dir="/tmp/stats-redrive-${change_id}"
install -d -m 700 "$evidence_dir"

curl -fsS http://localhost:8082/actuator/health/readiness \
  | tee "$evidence_dir/writer-readiness-before.json"
curl -fsS http://localhost:8082/actuator/prometheus \
  | grep -E '^(queue_depth|dlq_depth|consumer_availability|records_(persisted|duplicate_suppressed|expired_on_arrival|retried|dead_lettered|write_failed|postgres_errors)_total)\{' \
  | grep "type=\"${stat_type}\"" \
  | tee "$evidence_dir/metrics-before.txt"
```

## Run one-record pilot

Load the dedicated credentials without echoing the password. If the broker is not local,
set `RABBITMQ_HOST`, `RABBITMQ_PORT` and `RABBITMQ_VHOST`, or set a credential-free
`RABBITMQ_REDRIVE_URI` and still provide the two credential variables separately.

```bash
export RABBITMQ_REDRIVE_USER="$redrive_user"
export RABBITMQ_REDRIVE_PASSWORD="$redrive_password"
unset redrive_password
set -o pipefail

./gradlew -q :stats-writer:redriveDlq \
  --args="--type=${stat_type} --max-messages=1 --rate-per-second=10 --retention-days=90 --change-id=${change_id} --root-cause-resolved=true" \
  | tee "$evidence_dir/pilot.jsonl"
```

The root-cause flag is an explicit operator assertion tied to the change ID. The command
also requires dedicated redrive credentials; either gate missing causes it to stop.
For each confirmed movement it records the type, sequence, change ID, bounded previous
attempt/failure fields and SHA-256 body hash, but never the payload. This preserves the
previous safe failure reason in the audit evidence even when processing succeeds and the
broker copy is subsequently ACKed. It ACKs the DLQ original only after RabbitMQ confirms
the replacement in the main queue. If publication or confirmation fails, it requeues
the original and exits non-zero.

Wait for the Writer outcome and verify all of the following:

- `dlq_depth{type="<type>"}` decreased by one and main queue depth returned to baseline.
- Exactly one terminal outcome increased: persisted, duplicate suppressed, expired on
  arrival, or dead-lettered again.
- No `records_retried{type="<type>",tier="1|2|3"}` counter increased for a failed
  manual redrive.
- A repeated failure is again present in the same DLQ with the manual-redrive marker,
  original audit headers and latest safe failure reason.
- Writer readiness and consumer availability remain healthy.

RabbitMQ queue inspection is the immediate source for queue state. The Prometheus
`queue_depth` and `dlq_depth` gauges are cached by the Writer and refreshed on
`stats.metrics.poll-interval` (30 seconds by default), not during a scrape. After the
broker reaches the expected state, wait for at least one successful metrics collection
before comparing the gauges; a value from the preceding collection is not residual
queue data.

An `expired_on_arrival` outcome is not successful recovery. Stop and use the approved
disposition process. If upstream replay through Stats Input receives HTTP 503, that is
backpressure working as designed; reduce or pause the replay rate rather than treating
the 503 itself as a new incident. This direct broker redrive command does not call Stats
Input, but it can contribute to the same queue pressure.

## Continue in bounded batches

After the one-record pilot passes, choose an explicitly approved batch size and increase
the rate gradually while remaining at or below 2,667/s. Every invocation is finite and
handles only the selected type. Its upper bound is also limited to the ready-message
snapshot observed at the first fetch, so a record that fails and returns to the DLQ
cannot be selected again by the same invocation:

```bash
./gradlew -q :stats-writer:redriveDlq \
  --args="--type=${stat_type} --max-messages=100 --rate-per-second=100 --retention-days=90 --change-id=${change_id} --root-cause-resolved=true" \
  | tee -a "$evidence_dir/redrive.jsonl"
```

Stop immediately if Writer readiness drops, consumer availability becomes zero,
PostgreSQL errors rise, main queue depth grows continuously, the command exits non-zero,
or the DLQ starts refilling. Re-establish the root cause and approval before another
attempt. Never put this command in cron, Kubernetes scheduling, a queue listener or an
automatic alert action.

## Close and retain evidence

Capture the same metrics and health output after the queues settle. Record the command
exit status, requested and moved counts, before/after DLQ depth, terminal outcome deltas,
operator identity, approval and change ID. Attach the protected JSONL and metric snapshots
to the approved audit system according to the organisation's retention policy, then
unset credentials and revoke temporary access where applicable:

```bash
docker exec stats-rabbitmq rabbitmqctl delete_user "$RABBITMQ_REDRIVE_USER"
unset RABBITMQ_REDRIVE_PASSWORD RABBITMQ_REDRIVE_USER
```

Do not purge the DLQ to make the numbers match. Records that cannot be recovered require
a separately approved disposition that preserves the required investigation and audit
evidence.

## Local rehearsal procedure

This section is for an isolated development environment only. It publishes synthetic
records directly to the DLQ so the operational controls can be exercised without first
waiting for a real failure. Never use the management-account publication or disposition
commands below on a production DLQ.

Start from a clean DLQ, load `.env`, create the restricted user from **Required
authority**, and export its credentials. Create a current, unique fixture:

```bash
test_uid="CLSCON-$(cat /proc/sys/kernel/random/uuid)"
now=$(date +%s)
expiry=$(date -u -d '+14 days' '+%Y-%m-%dT%H:%M:%SZ')

jq --arg uid "$test_uid" --argjson now "$now" --arg expiry "$expiry" \
  '.uid=$uid
   | .ts.server=$now
   | .ts.device=$now
   | .ts.datastore=$now
   | .ts.converter=$now
   | .ts.expiresOn=$expiry' \
  docs/schemas/fixtures/valid/lorastats-production-canonical.json \
  > /tmp/t052-current.json
```

Publish it with representative DLQ headers:

```bash
jq -n --rawfile payload /tmp/t052-current.json --arg uid "$test_uid" '{
  properties: {
    content_type: "application/json",
    delivery_mode: 2,
    message_id: $uid,
    headers: {
      "x-stats-attempt": 4,
      "x-stats-failure": "RehearsalRootCause",
      "x-stats-permanent": false
    }
  },
  routing_key: "app_statsstore.lorastats.dlq",
  payload: $payload,
  payload_encoding: "string"
}' > /tmp/t052-publish.json

curl -fsS --user "$RABBITMQ_DEFAULT_USER:$RABBITMQ_DEFAULT_PASS" \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/t052-publish.json \
  http://localhost:15672/api/exchanges/%2F/amq.default/publish
```

The response must be `{"routed":true}`. Two queue inspections separated by a pause
must both show one ready DLQ record and zero DLQ consumers, proving that no automatic
processing exists:

```bash
docker exec stats-rabbitmq rabbitmqctl list_queues name messages_ready consumers \
  | grep 'app_statsstore.lorastats'
sleep 10
docker exec stats-rabbitmq rabbitmqctl list_queues name messages_ready consumers \
  | grep 'app_statsstore.lorastats'
```

Before the successful pilot, omit `--root-cause-resolved=true` once and try a rate of
`2668` once. Both commands must exit non-zero, report the relevant guard, and leave the
DLQ depth unchanged. Then run the one-record pilot from the main procedure and validate
its two JSON lines:

```bash
jq -s -e '
  length == 2
  and .[0].event == "record_redriven"
  and .[0].type == "lorastats"
  and .[0].previousAttempt == "4"
  and .[0].previousFailure == "RehearsalRootCause"
  and (.[0].bodySha256 | test("^[0-9a-f]{64}$"))
  and .[1].event == "redrive_completed"
  and .[1].moved == 1
' "$evidence_dir/pilot.jsonl"
```

RabbitMQ can expose AMQP string headers to the Java client as `LongString`, including
headers created through the management API. The command accepts bounded Java `String`
and AMQP `LongString` values; other or unsafe values become `unknown` rather than being
printed. `previousFailure` must therefore be `RehearsalRootCause`, not `unknown`, in this
rehearsal.

Verify persistence and zero queue depth:

```bash
docker exec -e PGPASSWORD="$POSTGRES_PASSWORD" stats-postgres psql \
  -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -Atc "SELECT count(*) FROM lorastats WHERE uid = '$test_uid';"
docker exec stats-rabbitmq rabbitmqctl list_queues name messages_ready consumers \
  | grep 'app_statsstore.lorastats'
```

The query must return `1`; main, retry and DLQ ready counts must all be zero. The
following focused suite proves the paths that are unsafe to create during an approved
live redrive: repeat transient failure goes directly to the DLQ, the same invocation
cannot select that returned record again, and the restricted broker role can complete a
real hand-off:

```bash
./gradlew :stats-writer:test \
  --tests '*FailedDeliveryRouterTest.failedManualRedriveNeverReceivesAnotherRetryTier' \
  --tests '*DlqRedriveCommandTest.doesNotPickUpARecordThatReturnsDuringTheSameInvocation' \
  --tests '*DlqRedriveIntegrationTest'
```

The command must end with `BUILD SUCCESSFUL` and all three named tests must pass.

Exercise the retention-age refusal with another synthetic fixture whose `ts.device` is
91 days old. Publishing is the same as above. Redrive must exit non-zero with `DLQ
message is outside the retention window`, the database query for its UID must return
zero, and the record must remain in the DLQ for approved disposition. In local testing
only, consume that one known synthetic record with:

```bash
jq -n '{count:1, ackmode:"ack_requeue_false", encoding:"auto", truncate:50000}' \
  > /tmp/t052-dispose.json
curl -fsS --user "$RABBITMQ_DEFAULT_USER:$RABBITMQ_DEFAULT_PASS" \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/t052-dispose.json \
  http://localhost:15672/api/queues/%2F/app_statsstore.lorastats.dlq/get \
  > /tmp/t052-disposed.json
jq 'length' /tmp/t052-disposed.json
```

The length must be `1`. A quorum queue can take a moment to expose the acknowledgement;
poll `rabbitmqctl list_queues` until the immediate DLQ depth is zero. The Writer gauges
can still report the preceding value for one collection interval (30 seconds by
default). Only compare `dlq_depth` after the broker is empty and a subsequent successful
metrics collection has occurred.
