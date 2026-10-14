# Stats App — walk through it by hand

`README.md` starts the stack and runs the automated suite. This page is the same proof
done by hand, one command at a time, so you can see each rule hold for yourself: a
record goes in, comes back out for its own tenant only, bad records are refused or
dead-lettered, and nothing is lost or duplicated.

**Every command on this page runs from this bundle's directory** (the one holding
`README.md`, `.env` and `docker/`). The passwords are in *this* directory's `.env`,
which `./docker/start-stats-app.sh` generated on its first start. Another copy of the
Stats App, such as an older bundle or a developer checkout, has different passwords.

## 0. Start, then run the automated suite first

The bundle uses fixed container names (`stats-postgres`, `stats-keycloak`, …) and ports
8080–8083. If another Stats App stack is running on this machine, stop it first.

```bash
./docker/start-stats-app.sh      # every container prints "healthy", then "Stats App is ready"
./docker/run-postman.sh          # 40 requests, 100 assertions, 0 failed
```

Run the suite **before** the steps below, not after. Step 10 and the demo put a record
in a dead-letter queue on purpose. After that, the suite's `DLQs stayed empty` check
fails, which is the suite correctly noticing it. To get a clean stack again, see
*Start again* at the end.

## How to run the commands — pick one

**A. On this machine (needs `curl` and `jq`).** Load the helpers into your shell
(bash or zsh):

```bash
. docker/stats-helpers.sh
```

**B. Nothing installed but Docker.** Open a shell in the bundled tools container. The
helpers are already loaded there, and every command below works unchanged:

```bash
./docker/shell.sh                # type `exit` to leave
```

Either way you get:

| Helper | What it does |
|---|---|
| `tok USER` | an access token for `alpha-reader`, `beta-reader`, `no-role`, `no-tenant` or `empty-tenant` |
| `claims TOKEN` | the claims every read is filtered on: `tenant`, `aud`, roles |
| `env_get KEY` | one value from `.env` (it cannot be `source`d) |
| `pg …` | `psql` in the bundle's PostgreSQL |
| `$INPUT` `$WRITER` `$API` `$KC` `$RABBIT` | the services' base URLs |
| `$NOW` | Unix seconds, captured when the helpers loaded |

For Postman desktop or a one-off curl, `./docker/token.sh alpha-reader` prints a token
(add `--claims` to see inside it). It works from any directory and needs only `curl`.

> **zsh:** keep every URL that contains `?` inside quotes, or zsh answers
> `no matches found`. The commands below are already quoted.

## 1. All three services are up

```bash
for u in "$INPUT" "$WRITER" "$API"; do curl -s "$u/actuator/health/readiness"; echo; done
```

Expect `{"status":"UP"}` three times: Input, Writer, API.

## 2. Log in as a reader

```bash
A=$(tok alpha-reader)
claims "$A"
```

Expect `"tenant": "tenant-alpha"`, `"aud": "stats-api"`, `"roles": ["stats.readOnly"]`.
The `tenant` claim in the token is what every read is filtered on. Nothing the caller
sends in the request can change it.

## 3. Send a record (what Heimdal does)

```bash
U="MANUAL-lora-$NOW"
curl -si -X POST "$INPUT/internal/stats" -H 'Content-Type: application/json' -d "{\"uid\":\"$U\",\"tenant\":\"tenant-alpha\",\"maintenant\":\"tenant-alpha-operations\",\"clsbox\":\"04B64810652B\",\"type\":\"lorastats\",\"version\":1,\"ts\":{\"server\":$NOW,\"device\":$NOW},\"data\":{\"rx_nr_pkts\":219,\"fsk_received\":0,\"rx_crc_missing\":0,\"rx_crc_bad\":48,\"tx_peek_index_zero\":0,\"tx_success\":0,\"rx_short_pkt\":166,\"timespan\":1800,\"chip_comm_error\":0,\"tx_failed\":0,\"tx_override\":0,\"util_fopen_failed\":0,\"tx_peek\":0,\"tx_type_beacon\":0,\"tx_busy\":0,\"tx_dequeue_error\":0},\"status\":[\"conversion ok\"]}"; echo
```

Expect `HTTP/1.1 200`, an `X-Trace-Id` header, and the body
`{"status":"accepted","traceId":"…"}` with the same ID. A 200 comes back only after
RabbitMQ has confirmed it holds the record. "Accepted" does not mean "stored": the
Writer stores it asynchronously, a moment later.

## 4. Read it back

```bash
curl -s -H "Authorization: Bearer $A" "$API/v1/stats/lorastats/$U" | jq .
```

Expect the full record. That means it went Input → RabbitMQ → Writer → PostgreSQL → API.
A 404 here means the Writer hasn't committed it yet: wait two seconds and retry.

## 5. Another tenant cannot see it — the most important check

```bash
B=$(tok beta-reader)
curl -s -H "Authorization: Bearer $B" "$API/v1/stats/lorastats/$U" | jq -c '{status,detail}'
curl -s -H "Authorization: Bearer $B" "$API/v1/stats/lorastats/04B64810652B/$((NOW-60))" | jq -c .
```

Expect `{"status":404,"detail":"No record with that uid exists for this tenant."}`, then
`{"items":[],"nextCursor":null,"limit":100}`, an empty 200. Beta can't even tell that
the record or the gateway exists. The record's `maintenant` (`tenant-alpha-operations`)
differs from its `tenant` on purpose, so this check also shows the filter uses `tenant`.

## 6. The list and per-device reads

```bash
curl -s -H "Authorization: Bearer $A" "$API/v1/stats/lorastats?from=$((NOW-60))&to=$((NOW+60))&limit=5" | jq '.items | length'
curl -s -H "Authorization: Bearer $A" "$API/v1/stats/lorastats/04B64810652B/$((NOW-60))" | jq '.items[].uid'
```

Expect at least `1`, and your `"MANUAL-lora-…"` uid in the second list.

## 7. LTE: the IMSI is masked by the API, stored raw

```bash
L="MANUAL-lte-$NOW"
curl -s -X POST "$INPUT/internal/stats" -H 'Content-Type: application/json' -d "{\"uid\":\"$L\",\"tenant\":\"tenant-alpha\",\"maintenant\":\"tenant-alpha-operations\",\"clsbox\":\"A1B2C3D4E5F6\",\"type\":\"ltestats\",\"version\":1,\"ts\":{\"server\":$NOW,\"device\":$NOW},\"data\":{\"rssi\":\"-67\",\"rsrq\":\"-13\",\"access_Technology\":\"4G\",\"plmn\":\"00101\",\"rsrp\":\"-103\",\"imsi\":\"001010123456789\",\"operator\":\"Test carrier\",\"lac\":\"00aB\",\"cid\":\"61D93D\"},\"status\":[\"conversion ok\"]}"; echo; sleep 2
curl -s -H "Authorization: Bearer $A" "$API/v1/stats/ltestats/$L" | jq .data.imsi
pg -c "SELECT uid, imsi, lac, cid FROM ltestats WHERE uid='$L';"
```

Expect the API to return `"00101******6789"`: the carrier prefix and last four digits,
same length as the original. The database holds the full `001010123456789`, with
`lac` = `00aB` and `cid` = `61D93D` exactly as sent.

## 8. Bad records are refused at the door (400)

```bash
curl -s -X POST "$INPUT/internal/stats" -H 'Content-Type: application/json' -d 'hello' | jq -c .errors
curl -s -X POST "$INPUT/internal/stats" -H 'Content-Type: application/json' -d "{\"uid\":\"x$NOW\",\"maintenant\":\"x\",\"clsbox\":\"04B64810652B\",\"type\":\"lorastats\",\"ts\":{\"server\":$NOW,\"device\":$NOW},\"data\":{}}" | jq -c .errors
curl -s -X POST "$INPUT/internal/stats" -H 'Content-Type: application/json' -d "{\"uid\":\"s$NOW\",\"tenant\":\"tenant-alpha\",\"maintenant\":\"tenant-alpha\",\"clsbox\":\"04B64810652B\",\"type\":\"lorastats\",\"ts\":{\"server\":$NOW,\"device\":$((NOW+3600))},\"data\":{}}" | jq -c .errors
```

Expect, in order: `malformed_json` on `/`, `missing` on `/tenant`, and
`clock_skew_exceeded` on `/ts/device` (the device clock is an hour ahead of the
server's). In each case nothing is published to RabbitMQ.

## 9. API security

```bash
curl -s -o /dev/null -w '%{http_code}\n' "$API/v1/stats/lorastats?from=1&to=2"                                                       # 401 no token
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $(tok no-role)" "$API/v1/stats/lorastats?from=1&to=2"            # 403 no read role
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $(tok no-tenant)" "$API/v1/stats/lorastats?from=1&to=2"          # 403 no tenant claim
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $(tok empty-tenant)" "$API/v1/stats/lorastats?from=1&to=2"       # 403 blank tenant claim
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$API/v1/stats/lorastats"                                                          # 405 the API is read-only
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $A" "$API/v1/stats/lorastats?from=200&to=100"                    # 400 inverted range
```

Expect `401 403 403 403 405 400`. The 405 comes back before any authentication runs.

## 10. A bad payload is dead-lettered, not lost

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$INPUT/internal/stats" -H 'Content-Type: application/json' -d "{\"uid\":\"MANUAL-dlq-$NOW\",\"tenant\":\"tenant-alpha\",\"maintenant\":\"tenant-alpha\",\"clsbox\":\"04B64810652B\",\"type\":\"lorastats\",\"ts\":{\"server\":$NOW,\"device\":$NOW},\"data\":{\"rx_nr_pkts\":\"not-a-number\"}}"
sleep 6
curl -s -u "$(env_get RABBITMQ_USER):$(env_get RABBITMQ_PASSWORD)" "$RABBIT/api/queues/%2F" | jq -r '.[] | select(.name|test("^app_statsstore\\.lorastats")) | "\(.name)  \(.messages)"'
curl -s "$WRITER/actuator/prometheus" | grep -E '^records_(payload_rejected|dead_lettered)_total.*lorastats'
```

Expect a `200`, because the envelope is fine and Input checks only the envelope. Then
`app_statsstore.lorastats.dlq` goes up by one, and so do
`records_payload_rejected_total{reason="payload_schema",type="lorastats"}` and
`records_dead_lettered_total{reason="PermanentFailureException",type="lorastats"}`.
The second counter appears after the first dead-letter.
RabbitMQ's management API refreshes its queue counts every few seconds. If the DLQ
still shows the old number while the counters have moved, run the queue command again. The three retry queues stay
at 0: a permanent failure skips the retries and goes straight to the dead-letter
queue, which never expires anything.

## 11. A duplicate is stored once

Run the step 3 command again exactly as it is (same `$U`), then:

```bash
pg -tAc "SELECT count(*) FROM lorastats WHERE uid='$U'"
curl -s "$WRITER/actuator/prometheus" | grep 'records_duplicate_suppressed_total{type="lorastats"'
```

Expect another `200`. The count stays at `1`, and the duplicate counter goes up by one.
Heimdal resends after any failure, so a duplicate is normal. It's absorbed and
counted, never silently dropped.

## 12. Look around in the browser

| What | URL | Login |
|---|---|---|
| RabbitMQ: 25 queues, a consumer on each main queue, your DLQ message | http://localhost:15672 | `stats` / `env_get RABBITMQ_PASSWORD` |
| Prometheus targets: all 4 UP | http://localhost:9090/targets | none |
| Keycloak: realm `statsapp`, its users | http://localhost:8080 | `admin` / `env_get KC_BOOTSTRAP_ADMIN_PASSWORD` |
| Grafana dashboards (start it first, below) | http://localhost:3000 | `admin` / `admin` |

```bash
docker compose -p stats-app-bundle --env-file .env -f docker/docker-compose.yml --profile apps --profile observability up -d --no-build
```

Keycloak's admin screens don't show the `tenant` attribute. To see it, open
**Clients → stats-dev-cli → Client scopes → Evaluate**, pick a user, and look at the
generated access token.

## 13. Logs

```bash
docker logs stats-writer 2>&1 | grep -o '"event":"[a-z_]*"' | sort | uniq -c
for c in stats-input stats-writer stats-api; do printf '%s ' $c; docker logs $c 2>&1 | grep -c 001010123456789; done
```

Expect events such as `payload_rejected` and `dead_lettered` from step 10. Expect `0`
for all three services: the IMSI from step 7 is not in any log. No LTE or netstats
payload is ever written to a log, at any level.

## 14. The scripted demo (optional)

```bash
./docker/run-demo.sh
```

Ten steps, each naming the rule it proves, run from the tools container. It needs
nothing on this machine but Docker. It covers more than the steps above: all five
types, a gateway back after three weeks offline, a late record skipped rather than
dead-lettered, and a non-OK upstream status. **Step 10 stops RabbitMQ** for a few
seconds to show that Input answers 503, never a false 200, and then starts it again.
Like step 10 above, the demo leaves records in the lorastats dead-letter queue.

## Start again

Delete everything this bundle stored, then start fresh. The credentials in `.env` are
kept.

```bash
docker compose -p stats-app-bundle -f docker/docker-compose.yml --profile apps --profile observability down -v
./docker/start-stats-app.sh
```

## When something does not match

| You see | Why | Do |
|---|---|---|
| `tok: Keycloak did not issue a token … Invalid user credentials` | The Keycloak on :8080 was started from another directory, whose `.env` has other passwords. The helpers warn about this when they load. | Run the commands from that directory, or stop that stack and start this one |
| `grep: .env: No such file or directory` / `stats-helpers: no .env` | You are not in the bundle directory | `cd` into it and load the helpers again |
| `zsh: no matches found` | An unquoted URL with `?` | Quote the URL |
| `jq: parse error` right after `tok` | The token request failed. Older copies of these commands piped the error message straight into the decoder. | Use `tok` and `claims` from the helpers. They print Keycloak's reason instead. |
| Step 4 returns 404 | The Writer hasn't committed yet | Wait two seconds and retry |
| `run-postman.sh`: `DLQs stayed empty` fails | Step 10 or the demo dead-lettered a record, as intended | *Start again*, then run the suite first |
| `./docker/shell.sh` cannot reach the Docker socket | Your Docker runtime keeps its socket somewhere other than `/var/run/docker.sock` (some Colima or rootless setups) | Use option A, or link the socket to that path |
