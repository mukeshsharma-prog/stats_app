# Stats App — try it locally

Built @@BUILT@@ from `@@COMMIT@@` · images for `@@PLATFORMS@@` · infrastructure images: @@INFRA@@.

The Stats App receives gateway statistics pushed by Heimdal, buffers them in RabbitMQ,
stores them per statistics type in PostgreSQL, and serves them back through a read-only,
tenant-scoped REST API secured by Keycloak. This bundle runs the whole thing on your
machine: the three Stats App services, plus PostgreSQL, RabbitMQ and Keycloak.

```
POST /internal/stats ─► stats-input ─► RabbitMQ ─► stats-writer ─► PostgreSQL ─► stats-api ◄─ GET /v1/stats/…
     (Basic auth)       :8081          one queue    :8082           one table     :8083        (Keycloak token)
                                       per type                     per type
```

## What you need

* **Docker Desktop** (macOS / Windows) or **Docker Engine + Compose v2** (Linux).
  Nothing else: no Java, no curl, no jq.
* About **4 GB of free memory** for Docker and 3 GB of disk.
* Nothing to free up. The stack wants ports **8080–8083, 5433, 15672** on `localhost`;
  `./start.sh` checks each one first, and if something on your machine already holds it,
  moves that service to the next free port, prints a **WARNING** saying which, and saves
  the new port in `.env`. The addresses below assume the defaults. `./start.sh` prints the
  real ones when it finishes.

## Run it

From this directory:

```bash
docker load -i images.tar          # once
./start.sh                         # 1–2 minutes; returns when all six services are healthy
docker compose run --rm demo       # the end-to-end demo, ~1 minute
```

`./start.sh demo` does the last two in one go. `docker compose ps` shows the state of
every service at any time.

`./start.sh` is a bash script (macOS, Linux, WSL or Git Bash on Windows). Without bash,
`docker compose up -d --wait` does the same, except that it does not check the ports:
it uses whatever `.env` says, and fails with *"address already in use"* if a port is taken.

## What the demo proves

The demo posts real production-shaped records and prints live responses, counters,
database rows and queue depths. **Nothing is mocked.** In step 10 it really stops RabbitMQ.

| Step | What happens | What you should see |
|---|---|---|
| 0 | Health of every service | `UP` ×3, partitions listed |
| 1 | Valid records of three stat types, two `lorastats` wire dialects | `HTTP 200` each, rows in three tables, one row shape for both dialects |
| 2 | A record from a gateway that was offline three weeks | Stored in a three-week-old partition, not dead-lettered |
| 3 | The same record sent twice | `200`, row count unchanged, `records_duplicate_suppressed` goes up |
| 4 | Broken envelopes: clock 5+ min in the future, missing `tenant`, millisecond timestamps, invalid JSON | `HTTP 400` with a field code; nothing published |
| 5 | A valid envelope with a payload nobody can parse | `200` at the door, then **dead-lettered** by the writer on its first attempt |
| 6 | A record older than the 90-day retention window | `200`, skipped and counted, **not** dead-lettered |
| 7 | A record whose upstream conversion reported failure | Stored and counted |
| 8 | Queue and consumer gauges | Depths, oldest-message age, consumers |
| 9 | The read-only database user tries to `INSERT` | `permission denied` from PostgreSQL itself |
| 9b | Two tenants read the API — records deliberately have `tenant` and `maintenant` crossed | Each reader sees **only its own tenant**; foreign `uid` → `404`; foreign gateway → empty `200`; no token → `401`; no role / no tenant / empty tenant → `403`; `DELETE` → `405` |
| 10 | RabbitMQ is stopped, a record is sent, RabbitMQ is started, the record is sent again | `503` while down (never a `200` that could be lost), `200` after |

Run the demo as often as you like; each run adds to what the previous one left.

## Try it by hand

The credentials below were generated for this bundle only. They are also in `.env`.

**Send a record** (`/internal/stats` is Heimdal's door; `/external/stats` is the same handler):

```bash
NOW=$(date +%s)
curl -i -u @@BASIC_USER@@:@@BASIC_PASS@@ http://localhost:8081/internal/stats \
  -H 'Content-Type: application/json' -d '{
  "uid": "CLSCON-try-'$NOW'", "tenant": "tenant-alpha", "maintenant": "tenant-alpha",
  "clsbox": "A1B2C3D4E5F6", "type": "sysstats", "version": 1,
  "ts": {"server": '$NOW', "device": '$NOW'},
  "data": {"memfree": 70916, "idletime": 780249.35, "uptime": 2322716.22,
           "load1": 0.6, "load5": 0.69, "load15": 0.74, "runable": "1/85"}
}'
```

If you get a `400`, its body names the field and the reason. A `401` means the
credential is wrong.

**Get a token and read it back** (users `alpha-reader` / `beta-reader` belong to
`tenant-alpha` / `tenant-beta`):

```bash
TOKEN=$(curl -s http://localhost:8080/realms/statsapp/protocol/openid-connect/token \
  -d client_id=stats-dev-cli -d grant_type=password \
  -d username=alpha-reader -d password=@@ALPHA_PASS@@ | sed -E 's/.*"access_token":"([^"]+)".*/\1/')

curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8083/v1/stats/sysstats?from=$((NOW-600))&to=$((NOW+60))"          # tenant-wide, window ≤ 1 h
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8083/v1/stats/sysstats/A1B2C3D4E5F6/$((NOW-600))"                  # one gateway, open-ended
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8083/v1/stats/sysstats/CLSCON-try-$NOW"                             # one record
```

Repeat with `beta-reader` / `@@BETA_PASS@@`: the same calls return nothing of
`tenant-alpha`'s. The tenant always comes from the token and never from the request.

A record the writer refuses (change `memfree` to `"lots"`, say) is still a `200` at the
door: it passed the envelope check. It then lands in the `app_statsstore.sysstats.dlq`
queue, visible in the RabbitMQ UI, and `docker compose logs stats-writer` says why.

## What's where

| | URL | Login |
|---|---|---|
| Ingest | http://localhost:8081/internal/stats | Basic `@@BASIC_USER@@` / `@@BASIC_PASS@@` |
| Read API | http://localhost:8083/v1/stats/{type} | Bearer token from Keycloak |
| RabbitMQ UI — queues, DLQs | http://localhost:15672 | `stats` / `@@RABBIT_PASS@@` |
| Keycloak admin | http://localhost:8080 | `admin` / `@@KC_ADMIN_PASS@@` |
| Metrics | http://localhost:8081/actuator/prometheus (also :8082, :8083) | none |
| PostgreSQL | `localhost:5433`, or `docker exec -it stats-demo-postgres psql -U stats -d statsdb` | `stats` / see `POSTGRES_PASSWORD` in `.env` |

Stat types: `lorastats`, `sysstats`, `omsstats` are built end to end. `ltestats` and
`netstats` are accepted at the door and wait in their queues: their writers are not built
yet, and the API reads them as empty.

**Dashboards (optional):**
`docker compose --profile observability up -d`, then Grafana at http://localhost:3000
(`admin` / `admin`) and Prometheus at http://localhost:9090.

## Stop, reset, remove

```bash
docker compose stop                # pause; data kept
docker compose down -v             # remove containers AND data; next `up` starts clean
docker image rm stats-app/stats-input:share stats-app/stats-writer:share \
                stats-app/stats-api:share stats-app/stats-demo:share     # remove the images too
```

## If something goes wrong

* **`docker compose up --wait` fails or times out.** Run `docker compose ps` to find the
  unhealthy service, then `docker compose logs <service>`. Keycloak is the slowest: allow
  two minutes on a first start.
* **"port is already allocated" / "address already in use".** You started with
  `docker compose up` instead of `./start.sh`, or something grabbed the port after
  `./start.sh` checked it. Run `./start.sh` again. It picks a free port and saves it in
  `.env`. To choose one yourself, edit that service's `PORT_*` line in `.env`.
* **A WARNING says a port moved.** Nothing is wrong. Use the port it names (also in
  `.env`) instead of the default in the addresses above.
* **"pull access denied for stats-app/…".** `docker load -i images.tar` hasn't been run.
* **The demo can't reach Docker** (`Cannot connect to the Docker daemon`). The demo
  mounts `/var/run/docker.sock`. On Docker Desktop that works by default; on Linux,
  the socket must be at that path.
* **You changed `.env` and nothing happened.** Existing Keycloak users are managed
  through its admin console. PostgreSQL passwords must be rotated in PostgreSQL and
  application configuration together; editing `.env` alone does not rotate them.

## Please don't

* **Expose it on a network.** Every port binds to `127.0.0.1`, and the secrets in `.env`
  are for this local copy only.
* **Point it at real data.** It's a development build with a development Keycloak realm.


## Keycloak user persistence

Users created through the Keycloak admin console survive restart and container
recreation in the named `keycloak-data` volume. The realm file is a first-import
seed; editing `.env` does not rotate passwords in an existing realm. Manage users,
credentials, `stats.readOnly` and the administrator-only `tenant` profile field in
Keycloak. Before upgrading an older running bundle, run
`./migrate-keycloak-data.sh stats-demo-keycloak` to preserve its container-local H2
database. `docker compose down -v` deletes named volumes, including all users.
