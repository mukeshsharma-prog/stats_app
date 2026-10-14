# Stats App

A **Datahub Core** add-on at Zenner. It receives statistics pushed from the
**Heimdal Core**, buffers them through RabbitMQ, persists them per statistics type
in **PostgreSQL**, and exposes them through a read-only, tenant-scoped REST API.

**Status (13 October 2026):** all five V1 stat types are built end to end — dedicated
Writer consumers, PostgreSQL tables V1–V9 and tenant-scoped API readers (DL-164). LTE
supports measured, 2G and unknown-technology records; netstats preserves dynamic
interfaces and checks every known counter before conversion. **D-12 P3 is answered**
(DL-167): the IMSI is stored raw and **masked on every API route**, `lac`/`cid` are
returned as reported, and no LTE/netstats payload reaches a log at any level — proved at
`TRACE` in all three modules. **Since 13 October nothing is answered 2xx that PostgreSQL
cannot store** (DL-168): oversized or blank identifiers, U+0000, invalid UTF-8, duplicate
members and a `ts.server` more than an hour ahead are a 400 at Input; an unconfirmed
retry/DLQ hand-off is requeued after a pause instead of stranding the delivery; the DLQ
redrive skips what it will not move and carries on; every API read has a 10 s statement
timeout. Full build **1,213 tests, 0 failures**; the fixture gate covers **100 files and
124 assertions** (103 Java parity cases). Still owed: an **independent review** of the
isolation and security suites (T-028), ZC's confirmation of the contract (D-09), the
first-24-h histogram reading, and the actual netstats feed (Q15g). **The Input door is
still unauthenticated on `main`** — the approver's 29 Sep decision to accept Heimdal's
ForwardRule Basic credential was built on branch `feat/dl-163-input-basic-auth` and
**never merged** (see `claude/DECISIONS.md` DL-169). Earlier figures below describe
their dated milestones.

Since **8 September 2026 `lorastats` is complete end to end**, in all three of the
payload dialects production actually sends: the Writer's per-type payload gate refuses
a mismatch straight to the dead-letter queue (T-057), all three dialects normalise to
one typed row (T-063), and a non-OK upstream `status` is metered without ever being
dead-lettered (T-064). Since **5 September 2026 a `lorastats` record travels the whole
path**: `stats-input` validates the envelope and publishes only after a broker confirm,
`stats-writer` persists it idempotently, and a failed write goes through three retry
tiers to a
per-type dead-letter queue that expires nothing — with the onward copy confirmed
before the original is acknowledged (T-014 … T-018, T-024, T-032). Since
**10 September 2026 the record also leaves again**: retention detaches and drops each
daily partition past the window — never row deletion — and a record that arrives already
past it is skipped, metered as `records_expired_on_arrival{type}` and acknowledged rather
than dead-lettered (T-025, T-026; DL-132, DL-133). Since **10–11 September 2026 the path
is observable and operable**: every delivery carries a `traceId` from the HTTP response
header to the DLQ copy (T-005), the read-only role is proved read-only *by PostgreSQL*
(T-009), the Writer exports its counters and the broker depths through a scrape that
performs no I/O (T-027), and a dead-lettered record can be brought back under a guarded,
per-type, rate-capped redrive that gives it exactly one more attempt (T-052;
`docs/runbooks/dlq-redrive.md`). Since 17–19 Sep 2026 it is also **watched**: the DLQ
(T-060, `ops/prometheus/dlq-alerts.yml`) and the main queues' oldest-message age, late
arrival and upstream status (T-050, `ops/prometheus/writer-alerts.yml`). The fan-out has
started: **`sysstats` is the second type end to end** (T-035, DL-148). Since
**20 September 2026 the stack has been run end to end** — `docker/demo.sh`, ten steps
each naming the rule it proves — and the first run found two silent losses that 674
green tests had not: partition pre-creation only looked *ahead*, so a valid record from
a gateway back after three weeks offline had no partition and dead-lettered as permanent
(DL-149, now the whole window); and every Writer `WARN` carrying a `uid` was being dropped
by the ECS encoder because seven statements repeated MDC keys (DL-150, now a build-failing
guard). Since **21 September 2026 the ingest endpoint has two doors on one handler** —
`POST /internal/stats` for Heimdal Core and `POST /external/stats` for everyone else,
identical in every rule and distinguished only by a `source` label on every Input metric
(DL-151) — and the read contract carries the device-scoped paths management asked for,
`GET /v1/stats/{type}/{clsbox}/{from}[/{to}]` (T-067).
**Read DL-151's residual before exposing the external door anywhere:** it authenticates
the caller and binds nothing to the record's tenant until Q8a-2 closes (T-068). **Since 26 September 2026
`stats-api` is built** — all three operations, Keycloak-secured, tenant-scoped, GET-only
(DL-162) — ahead of ZC's confirmation of the contract (D-09), by decision: a change ZC
asks for is rework, recorded as the risk. See `claude/PROJECT_STATUS_AND_PLAN.md`
and `claude/FLOW.md` for what is BUILT versus SPECIFIED, and **`Stats_App_Tracker.xlsx`
→ the `Next Up` sheet** for the ranked answer to *what do I pick up now*.

The same day also landed the work that is easy to miss because it is not on the happy
path: `clsbox` is now stored in one canonical case with a `CHECK` **every new stat-type
table must carry** (DL-115), two guards fail the build rather than asking politely — no
main source may branch on `version` (DL-113) and no throwable may reach a `stats-writer`
log line (DL-124) — `redocly lint` gates the API contract structure (DL-117), and a
separate parity gate resolves and compares all five inlined data schemas with the
authoritative validation catalogue (DL-140). Since DL-150 a third guard joins them: no
`stats-writer` log statement may repeat a key the delivery's MDC scope owns.

```
Heimdal Core ──POST──> stats-input ──AMQP──> RabbitMQ ──> stats-writer ──> PostgreSQL
                                          (queue per type)                      │
                                                                                ▼
                              consumer <──GET, Keycloak, tenant-scoped── stats-api
```

---

## Start here

| If you are… | Read, in this order |
|---|---|
| **New to the project** | `README.md` → `claude/CLAUDE.md` (§5 rules, §6 traps) → `claude/PROJECT_STATUS_AND_PLAN.md` → `claude/SKILLS.md` Skill 7 |
| **About to write code** | `claude/CLAUDE.md` → `claude/IMPLEMENTATION_RULES.md` → `claude/CODE_START_CHECKLIST.md` (incl. §A2 gate decisions) → the relevant ADR |
| **Picking up a delegated task** | `claude/PROJECT_STATUS_AND_PLAN.md` §4 → `claude/SKILLS.md` Skill 1 → `claude/DEVELOPMENT.md` |
| **Setting up locally** | **Getting started** below → `claude/DEVELOPMENT.md` §1–3 → `docs/setup/VS_CODE_SETUP.md` |
| **An AI assistant** | `claude/CLAUDE.md` loads automatically via the root `CLAUDE.md` pointer. Then `claude/IMPLEMENTATION_RULES.md` §9 for the required working sequence. |

---

## Getting started

New to Gradle or Spring Boot? This section is the whole loop, start to finish.
`claude/DEVELOPMENT.md` is the deeper reference; this is the short path.

> **What exists today (13 October 2026).** `stats-input` has two `POST` doors on one
> handler (`/internal/stats`, `/external/stats`): it validates the **envelope**, applies
> the clock-skew and server-clock checks, and returns 2xx with
> `{"status":"accepted","traceId":…}` only after the publisher confirm — 503 on a broker
> failure or an unroutable message, 400 on a bad or unstorable envelope, 413 over 256 KB,
> 429 over the rate limit. `stats-writer` consumes **all five types**, writes each
> idempotently into a daily partition, ACKs only after the commit, and routes a failed
> delivery through three retry tiers to a per-type DLQ. Migrations V1–V9 exist.
> **`stats-api` serves all three read operations** (DL-162) — ahead of ZC's confirmation
> of the contract (D-09), by decision. `docker/demo.sh` drives the whole stack.

### Prerequisites

| Tool | Version | Check |
|---|---|---|
| JDK | **21** (not 17) | `java -version` |
| Gradle | none — the repo ships the wrapper | `./gradlew --version` |
| Docker Desktop | recent, **and running** | `docker info` |
| `.env` | copied from `.env.example` | `cp .env.example .env`, then fill the blanks |

### The five terms you need

| Term | Meaning |
|---|---|
| `./gradlew` | The **Gradle wrapper**. Downloads and runs the right Gradle version itself — always use it, never a globally installed `gradle`. `gradlew.bat` on Windows. |
| `:module:task` | Addresses one task in one module: `:stats-input:bootRun` is "run `bootRun` in `stats-input`". No prefix means every module. |
| `bootRun` | Compile and start the application now. Holds the terminal; **Ctrl+C** stops it. |
| `bootJar` | Package the module into one self-contained runnable jar, in `<module>/build/libs/`. |
| `gradle/libs.versions.toml` | The **version catalog** — the single place dependency versions are declared. Module build files name *what* they need, never *which version* (G6). |

### 1. Build

From the repository root:

```bash
./gradlew build          # compile every module, run tests, produce the jars
```

Other tasks worth knowing:

```bash
./gradlew clean build            # rebuild from scratch
./gradlew :stats-input:build     # one module only
./gradlew test                   # tests only
./gradlew projects               # list the modules
./gradlew tasks                  # list the available tasks
```

### 2. Start the infrastructure

PostgreSQL, RabbitMQ and Keycloak are **infrastructure dependencies, not Stats
App deployables** — they run as containers, never as part of the build.

```bash
cd docker
docker compose up -d
docker compose ps          # wait for every service to report healthy
```

| Service | Port | Notes |
|---|---|---|
| PostgreSQL | 5432 | database `statsdb` |
| RabbitMQ | 5672 | management UI on **15672** |
| Keycloak | 8080 | realm `statsapp`, imported at first start |

`docker compose down` stops the stack and keeps the data. `docker compose down -v`
also deletes the volumes, which you **must** do after editing `docker/postgres-init/`,
because it runs only against an empty database volume. Keycloak is different. It has
no volume and re-imports `docs/setup/keycloak-realm-statsapp.json` with the values in
`.env` whenever its container is **recreated**: by `up -d` after a `.env` change, or by
`up -d --force-recreate keycloak` after a change to the realm file alone. A plain
restart, or `up -d` with nothing changed, keeps the old realm.

### 3. Run the modules

One terminal per module, from the repository root. Spring Boot does **not** read
`.env`, so export it yourself — and override the two hostnames that are written
for the compose network, because from your machine the services are on
`localhost`:

```bash
set -a && source .env && set +a
export POSTGRES_URL=jdbc:postgresql://localhost:5432/statsdb
export RABBITMQ_HOST=localhost

./gradlew :stats-input:bootRun     # Module 1 — port 8081
./gradlew :stats-writer:bootRun    # Module 2 — port 8082
./gradlew :stats-api:bootRun       # Module 3 — port 8083
```

Sourcing `.env` matters even locally: the broker is created with the
`RABBITMQ_DEFAULT_USER`/`RABBITMQ_DEFAULT_PASS` account from that file, which is
**not** `guest`/`guest`, so a module started without it cannot authenticate.
`KEYCLOAK_ISSUER` needs no override — it is `localhost` on purpose, and a
containerised `stats-api` fetches signing keys over the compose network through
`SPRING_SECURITY_..._JWK_SET_URI` instead.

Start `stats-writer` before sending traffic through `stats-input`, or messages
simply queue up — which is the buffer working as designed, not a fault.
`stats-writer` applies its Flyway migrations at startup, so it needs PostgreSQL
reachable before it will start at all.

To run a packaged jar instead — which is what the Docker image does:

```bash
java -jar stats-input/build/libs/stats-input-0.1.0-SNAPSHOT.jar
```

Or run all three as containers, infrastructure included, as the **dev stack**:

```bash
docker compose -p stats-app --env-file .env -f docker/docker-compose.yml --profile apps up -d --build
```

Use the project name `stats-app` for the dev stack. `stats-app-bundle` belongs to the
shareable bundle: its scripts, its `.env` and its data volumes. Starting the dev stack
under that name mixes the two stacks' credentials. *Run everything end to end* below is
the complete developer loop.

To produce a single offline archive for another Docker host, including the
application, infrastructure and Postman/Newman images:

```bash
./docker/package-bundle.sh
```

The recipient extracts the generated archive from `dist/` and runs:

```bash
./docker/start-stats-app.sh
./docker/run-postman.sh
```

The runtime includes eight independently managed containers; the archive is the
single shareable delivery unit. `stats-common` is compiled into each application
that uses it and is not a service. Prometheus and PostgreSQL exporter start
automatically with the apps; the startup script waits for all four scrape targets
to be UP. Open http://localhost:9090/targets. See `docker/BUNDLE.md` for ports,
lifecycle, credential generation, and the local-only security boundary.

### 4. Verify

```bash
curl http://localhost:8081/actuator/health     # stats-input
curl http://localhost:8082/actuator/health     # stats-writer
curl http://localhost:8083/actuator/health     # stats-api
```

A module reporting `DOWN` while its infrastructure is stopped is **correct**:
readiness probes only that module's real dependencies (D13), so `stats-input`
is `DOWN` without RabbitMQ and `stats-api` is `DOWN` without PostgreSQL and
Keycloak. `stats-api` has no broker connection at all, deliberately — Q3a closed on 29 Aug 2026:
only broker-connected modules declare topology (ADR-002 §11).

### Troubleshooting

| Symptom | Cause |
|---|---|
| `Cannot connect to the Docker daemon` | Docker Desktop is not running. |
| A module cannot reach PostgreSQL or RabbitMQ | You sourced `.env` without the two `localhost` overrides above. Those values name compose hostnames. |
| `stats-api` will not start | `KEYCLOAK_ISSUER`, `STATS_API_AUDIENCE`, `STATS_READ_ONLY_ROLE` and `STATS_TENANT_CLAIM` have **no defaults in code**, on purpose — the module refuses to start rather than guess a security posture (ADR-006). Source `.env`. |
| Every token is rejected as an invalid issuer | The token's `iss` and `KEYCLOAK_ISSUER` disagree. Compose pins `KC_HOSTNAME` to `http://localhost:8080`, so `KEYCLOAK_ISSUER` must be `http://localhost:8080/realms/statsapp`. |
| A realm change had no effect | Keycloak was not recreated. `up -d` recreates it after a `.env` change; after a change to the realm file alone, add `--force-recreate keycloak`. |
| A `docker/postgres-init/` change had no effect | It runs only against an empty volume: `docker compose down -v`, then up again. |
| `Invalid user credentials` from a token request | The password came from a different `.env` than the one the running Keycloak was started with, typically the bundle's versus this checkout's. Run commands from the directory whose stack is running. `. docker/stats-helpers.sh` warns when they differ. |
| `Bind for 0.0.0.0:8080 failed: port is already allocated` | The bundle stack (or another dev stack) is running. The two use the same container names and ports, so stop one first. |
| `Port 8081 already in use` | An earlier `bootRun` is still alive: `lsof -ti:8081 \| xargs kill`. |

---

## Run everything end to end

The developer loop, from this checkout. Every block was pasted into an interactive zsh
and checked on 14 October 2026. The blocks carry no `#` comments on purpose, because
an interactive zsh passes a pasted comment to the command as more arguments.

**One stack at a time.** The dev stack (Compose project `stats-app`, this checkout's
`.env`) and the shareable bundle (project `stats-app-bundle`, the bundle folder's `.env`)
use the same container names and ports. Stop the bundle first:

```bash
docker compose -p stats-app-bundle -f docker/docker-compose.yml --profile apps --profile observability --profile tools down
```

**1. Build from source and start the dev stack.**

```bash
docker compose -p stats-app --env-file .env -f docker/docker-compose.yml --profile apps up -d --build
for c in stats-postgres stats-rabbitmq stats-keycloak stats-input stats-writer stats-api stats-prometheus; do printf '%-18s' $c; until [ "$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' $c 2>/dev/null)" = healthy ]; do printf .; sleep 3; done; echo healthy; done
./docker/check-monitoring.sh
```

Expect seven `healthy` lines and `UP (Input, Writer, API, PostgreSQL)`.

**2. Call it by hand.** The helpers work against the dev stack from the repository
root. They read this checkout's `.env`.

```bash
. docker/stats-helpers.sh
claims "$(tok alpha-reader)"
```

Expect the helpers to load **with no WARNING**, then `tenant-alpha`, `stats-api`,
`stats.readOnly`. Every step of `docker/MANUAL.md` (steps 1–13) then works unchanged:
paste its blocks here.

**3. The acceptance suite, the demo and the tools container.**

```bash
docker compose -p stats-app --env-file .env -f docker/docker-compose.yml --profile apps --profile test run --rm postman-tests
./docker/demo.sh
docker build -f docker/Dockerfile.tools -t stats-app/stats-tools:dev .
docker compose -p stats-app --env-file .env -f docker/docker-compose.yml --profile apps --profile tools run --rm stats-tools
```

Expect 100 assertions, 0 failed. Run the suite **before** the demo: the demo dead-letters
a record on purpose, and the suite's `DLQs stayed empty` then fails, correctly. The demo
ends `Done.`, and its step 10 stops RabbitMQ for a few seconds. The last line opens a
`stats-tools $` shell with the helpers loaded and curl, jq and docker inside, for working
without them on the host. `./docker/run-postman.sh`, `./docker/shell.sh` and
`./docker/run-demo.sh` are the **bundle's** commands: they target `stats-app-bundle`, not
this stack.

**4. The checks CI runs.**

```bash
python3 docs/schemas/validate-fixtures.py | tail -3
python3 docs/contracts/test_schema_drift.py 2>&1 | tail -1
python3 docs/contracts/check_schema_drift.py | tail -1
python3 docker/tests/test-start-stats-app.py 2>&1 | tail -1
python3 docker/tests/test-manual-tools.py 2>&1 | tail -1
python3 ops/grafana/check-dashboards.py | tail -1
docker run --rm --entrypoint promtool -v "$PWD/ops/prometheus:/work:ro" -w /work prom/prometheus:v3.5.0 test rules dlq-alerts.test.yml writer-alerts.test.yml postgres-alerts.test.yml | tail -2
npx --yes @redocly/cli@2.51.2 lint 2>&1 | grep -E 'validated|valid\.|error'
./gradlew build --rerun-tasks
```

Expect, in order:

* `124 assertions checked, 0 failed`
* `OK`
* `OpenAPI schema drift check passed …`
* `OK`
* `OK`
* `3 dashboards, 83 expressions, …`
* `SUCCESS`
* both contracts `validated` and `Woohoo! Your API descriptions are valid.`
* `35 actionable tasks: 35 executed` and `BUILD SUCCESSFUL`. On 14 Oct 2026 this was
  1,197 JUnit tests with 0 failures: stats-common 278, stats-input 95,
  stats-writer 557, stats-api 267.

`--rerun-tasks` is what makes the tests run. An unchanged checkout otherwise answers
`35 up-to-date` in two seconds. Reports are at `<module>/build/reports/tests/test/index.html`.

**5. Credentials, tenants and users on the dev stack.** The rules are the bundle's, applied
to this checkout's files. Edit `.env` (or `docs/setup/keycloak-realm-statsapp.json`),
then recreate. Test-user passwords, tenants (`STATS_TENANT_A_ID`, `STATS_TENANT_B_ID`),
user names and client secrets can change at any time. `POSTGRES_*` and `RABBITMQ_*`
passwords live in the data volumes and need `down -v`. A change made in the Keycloak
admin console is lost at the next recreate. The realm file is committed, so a user added
there is added for everyone. `docker/BUNDLE.md`, *Credentials* and *Changing a user's
tenant…*, has the worked examples. The commands are the same with `-p stats-app`.

```bash
docker compose -p stats-app --env-file .env -f docker/docker-compose.yml --profile apps up -d
docker compose -p stats-app --env-file .env -f docker/docker-compose.yml --profile apps up -d --force-recreate --wait keycloak
```

Use the first after a `.env` change and the second after a realm-file-only change.

**6. Package a bundle to hand over.**

```bash
BUILDX_FLAGS='--add-host release-assets.githubusercontent.com:185.199.110.133' ./docker/package-bundle.sh
```

Expect `Bundle ready:` with the `.tar.gz` and its `.sha256` in `dist/` (about 2 GB, both
architectures, 10–20 minutes). The archive is named after `HEAD`: commit first if the name
must match the contents. `BUILDX_FLAGS` works around one unreachable GitHub download
address on the office network (DL-170). Leave it out elsewhere. What the recipient does is
in `docker/BUNDLE.md` (shipped as the bundle's `README.md`) and `docker/MANUAL.md`.

**7. Stop.**

```bash
docker compose -p stats-app --env-file .env -f docker/docker-compose.yml --profile apps --profile observability --profile tools down
```

Data is kept. `down -v` deletes it.

---

## Repository map

Everything needed to work on this project is in this directory. Nothing lives
outside it.

### Working documents — `claude/`

Every working document lives in `claude/`. The repository root holds only this
`README.md` and a one-line `CLAUDE.md` pointer that imports
`claude/CLAUDE.md` so it still auto-loads.

| File | What it is for |
|---|---|
| `claude/CLAUDE.md` | Project context, non-negotiable rules, known traps, open questions. Auto-loaded via the root `CLAUDE.md` pointer. |
| `claude/DECISIONS.md` | **Every meaningful decision and why** — architecture, libraries, conventions, and the choices too small for an ADR. `DL-nnn` IDs. The answer to *why is it like this?* |
| `claude/FLOW.md` | **The execution flow** — a record from Heimdal to an API response, every failure exit, and the sixteen windows where one could be lost — twelve of them silent. Says what is built versus specified |
| `claude/IMPLEMENTATION_RULES.md` | Detailed implementation rules, code structure, and the required working sequence |
| `claude/PROJECT_STATUS_AND_PLAN.md` | What is answered, what is open and who owns it, the phased plan, and the delegation split |
| `claude/DEVELOPMENT.md` | Build, run, test, configure, troubleshoot |
| `claude/SKILLS.md` | Repeatable procedures — add a stat type, review tenant isolation, write tests, diagnose a queue, change retention |
| `claude/CODE_START_CHECKLIST.md` | Stop/go decision register and phase entry criteria |
| `claude/OPEN_QUESTIONS_STATUS_29AUG2026.md` | Every open question and decision as at 29 Aug 2026: what was decided that day and why, what actually blocks which task, and the tracker inconsistencies found |
| `Stats_App_Tracker.xlsx` (one level above the checkout, beside it — it is not committed) | **The working task tracker.** 71 tasks across phases 1–5 with acceptance criteria, requirement IDs and blockers; plus what is already done, the open questions by owner, every decision, the 34 known traps with the test that catches each and whether that test exists yet, and a change log. Refreshed 21 Sep 2026 (DL-152). **Read the `Next Up` sheet first** — added 5 Sep 2026, it is the ranked sequence across both owners with the gate on each row; the Tasks sheet is the backlog behind it. |
| `Stats_App_Work_Brief_Mukesh_05SEP2026.pdf` (not in this checkout — generated 5 Sep 2026 and handed over; the tracker supersedes it) | **The delegation brief.** The same information aimed at one person: what Mukesh owns, in what order, the procedure for each block of work, the traps with the test that catches each, and the definition of done. Generated 5 Sep 2026; the tracker is the live source, this is the readable snapshot. `Stats_App_Developer_Guide.pdf` sits beside it (built 18 Sep 2026 — it predates DL-147 onward). |

### Specifications — `docs/`

| Path | Contents |
|---|---|
| `docs/requirements/` | SRS, BRD, and ARB v1.2 amendments — the normative requirements |
| `docs/decisions/` | `ANSWER_RECONCILIATION.md` (what ZC answered), `2B-ANSWERS-18AUG2026.md` (the seven §2b answers and every decision derived from them), and the PostgreSQL baseline |
| `docs/architecture/adr/` | ADR-001 … **ADR-007**. **`ADR-nnn` always means one of these.** The ARD carries its own ADR-001…010 with different meanings — cite those as `ARD-ADR-nn`. **Start with ADR-007** if you have not read this repository since 29 Aug 2026: it rebuilt the envelope on the production wire format and amends ADR-002, ADR-003 and ADR-004. |
| `docs/architecture/Stats_App_Flow.png` | End-to-end flow diagram |
| `docs/contracts/` | Heimdal inbound HTTP contract (`heimdal-input-contract-v1.md`, with its OpenAPI twin **`openapi-input.yaml`**), AMQP topology, **`writer-metrics-v1.md`** (every Writer metric and its measurement boundary), and **`openapi.yaml`** — the read API contract, OpenAPI 3.1, GET-only. Both contracts are draft pending ZC confirmation; `build-html.sh` renders them onto one page (DL-145). Since 21 Sep 2026 (DL-151) the inbound contract has two doors (`/internal/stats`, `/external/stats`) and the read contract three operations — the device-scoped `/{type}/{clsbox}/{from}[/{to}]` joined the list and the `uid` lookup |
| `docs/runbooks/` | **`dlq-redrive.md`** — the guarded per-type DLQ redrive (since DL-168 it skips what it will not move and carries on); **`partition-restore.md`** (T-051); **`load-test-burst.md`** (T-048); **`replay-drill.md`** (T-049) |
| `ops/prometheus/` | **`dlq-alerts.yml`** (T-060), **`writer-alerts.yml`** (T-050; `StatsAppFailureHandoffUnconfirmed` since DL-168) and **`postgres-alerts.yml`** (T-051) with their `promtool` unit tests, plus `prometheus.yml` and a README with the Q13b histogram query — all validated in CI |
| `ops/grafana/`, `ops/postgres-exporter/`, `ops/drills/`, `ops/load/` | Generated Writer / Input / PostgreSQL dashboards and their CI checker (T-056); the one custom exporter query (`pg_settings_archive_mode`, DL-160); the Phase 5 drills with their measured numbers; the open-loop `BurstLoad.java` generator |
| `docker/` | Compose stack, Dockerfile, `postgres-init/`, **`demo.sh`** — the end-to-end demo against the running stack (T-069), each step naming the rule it proves — and **`share/`**, the offline bundle (`docker/BUNDLE.md`) |
| `postman/` | The Postman collection — all five types, exact wire read-back, byte-identical duplicate replay |
| `docs/schemas/` | JSON Schema catalogue, per-type schemas, and fixtures |
| `docs/prompts/` | Prompt templates for working on this repo |
| `docs/setup/` | Local IDE setup and `keycloak-realm-statsapp.json`, the importable dev realm whose four users are each a specific test |
| `docs/source/` | Original `.docx` material — **historical provenance only, superseded** |
| `docs/source/Datahub_Core_StatsApp_ARD_v1_1.pdf` | Architecture & Requirements Design — **the current ARD**, superseding `ARD_v1_0.pdf`. The most detailed design document we have, but **derived from the v1.0 sources** — it does not outrank the v1.2 amendments. Reconcile, do not adopt wholesale. Its ADRs are cited `ARD-ADR-nn`. |

> **On `docs/source/`:** these are the v1.0 documents. They say MongoDB, and the
> SRS `omsstats` section describes a `ts.converter` field. Both are superseded.
> Read them only to understand how a decision was reached — never to decide what
> to build. The one exception is `Stats_App_Answers_v1_0.docx`, which is the
> source of every answered decision.

---

## The five-line version of the architecture

1. **`stats-input`** validates and publishes. It returns 2xx **only after** the
   RabbitMQ publisher confirms. Broker down means 503, never a 200.
2. **RabbitMQ** has one durable queue, three retry tiers, and a DLQ **per stat type**, so a
   poison message in one type cannot stall another.
3. **`stats-writer`** is the only component that writes to the database. It
   acknowledges only after the PostgreSQL transaction commits.
4. **PostgreSQL** has one table per stat type. Retention keys on `ts.device`,
   default 90 days.
5. **`stats-api`** is GET-only, Keycloak-secured, and derives the tenant from the
   validated token — never from the request.

Stat types: `lorastats`, `ltestats`, `sysstats`, `omsstats`, `netstats`.

---

## Four things that are easy to get wrong

1. **`tenant` vs `maintenant`.** They hold the same value in most sample data — and on every live tenant but minol, where a filter on the wrong field *drops* rows instead of leaking them (DL-146).
   Filtering on the wrong one passes every test and leaks data in production.
   `tenant` is the isolation field. Always write fixtures where the two differ.
2. **Unix seconds, not milliseconds.** Treating `ts.device` as millis gives 1970.
3. **Unsorted pagination is not stable.** Always sort on an indexed field with a
   unique tie-breaker, not only when the caller asks.
4. **Never swallow an exception.** Return an error, dead-letter, or log at
   WARN/ERROR with `uid` and stat type. Silence is the one unacceptable outcome.

Full list with reasoning: `claude/CLAUDE.md` §6.

---

## Current constraints

| | |
|---|---|
| Java | 21 |
| Spring Boot | **3.5.16** — pinned in `gradle/libs.versions.toml`. Final; Q1a closed 18 Aug 2026 |
| Database | PostgreSQL only. No MongoDB, anywhere. |
| Deployables | Three Docker images: `stats-input`, `stats-writer`, `stats-api` |
| Volume | ~267 records/sec/type, ~1,333/sec average across five types; up to 115.2M records/day. **Design target 13,333/s** — a 10× synchronised burst, assumed until Heimdal telemetry says otherwise (Q13b). |

## What is open

Nothing blocks the build. The v1.0 blockers and the six §2b items were all closed
on 18 August 2026 (`docs/decisions/2B-ANSWERS-18AUG2026.md`), and the OpenAPI
contract — which was always ours to write, not ZC's to supply — was drafted on
21 August as `docs/contracts/openapi.yaml`.

One item is **risk accepted rather than answered**, and it must be re-read before any
shared deployment:

1. **The Input endpoint is unauthenticated on `main`** (Q8a-2). Auth is deferred by
   decision, behind `stats.input.auth.mode` with no default in code, a `WARN` on
   every `none` startup, and a test that refuses to start under `prod`. Anything that
   can reach the Input port can write. The approver decided on 29 Sep 2026 to build
   against Heimdal's ForwardRule (Basic credentials from the URL, `maxRetry = 3`); that
   implementation sits on the unmerged branch `feat/dl-163-input-basic-auth` (commit
   `1a4f28a`) and is **not in this checkout** — DL-169 records why.
2. ~~**No RPO/RTO number exists** (D5a).~~ **CLOSED — D5a was SIGNED on 4 September
   2026 (DL-111) at RPO ≤ 5 min / RTO 1 h per partition, and both are now drilled and
   alerted** (T-051, DL-155): a 200,000-row day restores and re-attaches in 1.23 s, and
   `postgres-alerts.yml` pages when WAL archiving fails or stalls.
3. ~~**The late-arrival policy has engineering agreement but no product signature**~~
   **CLOSED — D5b was SIGNED on 4 September 2026 (DL-112) and BUILT on 10 September
   (T-026, DL-133).** Skip the write, meter `records_expired_on_arrival{type}`, ACK.
   It is still a deliberate data-loss-by-design path — the signature is what makes it a
   decision rather than a defect — and the *alert* on that counter, which is the half
   that separates a systemic gateway backlog from a harmless one-off, is **built**:
   `StatsAppLateArrival` (T-050, DL-147).

**Q13b — do gateways report on synchronised 10-minute boundaries or jitter across
them? — was answered on 4 September 2026 (DL-104): they jitter.** The 13,333/s design
target survives as *configured headroom*, not the expected case; expected steady state is
the ~1,333/s average. But an answer is a statement, not a measurement, and hardware is
not sized down until the `ts.server mod 600` histogram over the first 24 h of ingest
confirms it — since T-050 (DL-147) that histogram exists,
`records_ts_server_cycle_offset_seconds{type}` on `stats-input`, with the query in
`ops/prometheus/README.md`. Reading its first 24 h is the next step, not building it.

**ZC's confirmation of `docs/contracts/openapi.yaml`** (D-09) no longer gates work: the
approver built Phase 4 past it on 26 Sep 2026 (DL-162), so it is now a **rework risk** —
a change ZC asks for costs code as well as YAML. The contract carries the device-scoped
read paths (DL-151), the masked `imsi` (DL-167) and the DL-168 envelope bounds.

**Closed on 29 August 2026 — do not reopen:** Q14a (clock skew — rejected at Input
beyond a configurable tolerance), Q3a (topology declared by broker-connected modules
only), Q5a (volume is 115.2M/day), DLQ retention (no TTL, ever), and `pg_partman`
(never a dependency). Reasoning:
`claude/OPEN_QUESTIONS_STATUS_29AUG2026.md`.

Full register: `claude/CODE_START_CHECKLIST.md` §A (with §A3 for the 29 Aug
decisions) and `claude/PROJECT_STATUS_AND_PLAN.md` §2b-2 and §2c.
