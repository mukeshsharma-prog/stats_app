# Stats App

A **Datahub Core** add-on at Zenner. It receives statistics pushed from the
**Heimdal Core**, buffers them through RabbitMQ, persists them per statistics type
in **PostgreSQL**, and exposes them through a read-only, tenant-scoped REST API.

**Status (4 September 2026):** Phase 2 — the `lorastats` vertical slice, in
progress. Requirements and architecture are settled and ADRs 001–007 are approved
or accepted. The Gradle multi-project builds: five modules (`stats-common`,
`stats-testsupport` and the three applications), three deployable images, a Docker
Compose stack with PostgreSQL 17, RabbitMQ and Keycloak, and 75 schema fixture
files gated by `docs/schemas/validate-fixtures.py` at 87 assertions.

`stats-writer` persists `lorastats` idempotently and `stats-input` validates the
envelope, but **no record has yet travelled the whole path**: Input cannot publish
until T-014, and the Writer has no retry/DLQ arm until T-024. `stats-api` is a stub
awaiting the OpenAPI confirmation (D-09). See `claude/PROJECT_STATUS_AND_PLAN.md`
and `claude/FLOW.md` for what is BUILT versus SPECIFIED.

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

> **What exists today.** The three modules are Spring Boot skeletons — one
> `main()` class each, no controllers, no consumers, no migrations yet. The build
> succeeds and all three applications start. There is no `POST /stats` endpoint
> and no `GET /v1/stats/...` endpoint until the `lorastats` vertical slice lands,
> so the smoke test in `claude/DEVELOPMENT.md` §3.4 describes the target, not the
> current state.

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
also deletes the volumes — which you **must** do after editing
`docker/postgres-init/` or `docs/setup/keycloak-realm-statsapp.json`, because
both only run against an empty volume.

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

Or run all three as containers, infrastructure included:

```bash
cd docker && docker compose --profile apps up -d --build
```

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
| A realm or database change had no effect | Both init paths only run on an empty volume. `docker compose down -v`, then up again. |
| `Port 8081 already in use` | An earlier `bootRun` is still alive: `lsof -ti:8081 \| xargs kill`. |

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
| `claude/FLOW.md` | **The execution flow** — a record from Heimdal to an API response, every failure exit, and the eleven windows where one could be lost. Says what is built versus specified |
| `claude/IMPLEMENTATION_RULES.md` | Detailed implementation rules, code structure, and the required working sequence |
| `claude/PROJECT_STATUS_AND_PLAN.md` | What is answered, what is open and who owns it, the phased plan, and the delegation split |
| `claude/DEVELOPMENT.md` | Build, run, test, configure, troubleshoot |
| `claude/SKILLS.md` | Repeatable procedures — add a stat type, review tenant isolation, write tests, diagnose a queue, change retention |
| `claude/CODE_START_CHECKLIST.md` | Stop/go decision register and phase entry criteria |
| `claude/OPEN_QUESTIONS_STATUS_29AUG2026.md` | Every open question and decision as at 29 Aug 2026: what was decided that day and why, what actually blocks which task, and the tracker inconsistencies found |
| `Stats_App_Tracker.xlsx` (repository root) | **The working task tracker.** 56 tasks across phases 1–5 with acceptance criteria, requirement IDs and blockers; plus what is already done, the open questions by owner, every decision, the 18 known traps with the test that catches each and whether that test exists yet, and a change log. Start here if you are picking up work. |

### Specifications — `docs/`

| Path | Contents |
|---|---|
| `docs/requirements/` | SRS, BRD, and ARB v1.2 amendments — the normative requirements |
| `docs/decisions/` | `ANSWER_RECONCILIATION.md` (what ZC answered), `2B-ANSWERS-18AUG2026.md` (the seven §2b answers and every decision derived from them), and the PostgreSQL baseline |
| `docs/architecture/adr/` | ADR-001 … **ADR-007**. **`ADR-nnn` always means one of these.** The ARD carries its own ADR-001…010 with different meanings — cite those as `ARD-ADR-nn`. **Start with ADR-007** if you have not read this repository since 29 Aug 2026: it rebuilt the envelope on the production wire format and amends ADR-002, ADR-003 and ADR-004. |
| `docs/architecture/Stats_App_Flow.png` | End-to-end flow diagram |
| `docs/contracts/` | Heimdal inbound HTTP contract, AMQP topology, and **`openapi.yaml`** — the API contract, OpenAPI 3.1, GET-only, draft pending ZC confirmation |
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

1. **`tenant` vs `maintenant`.** They hold the same value in most sample data.
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

Two items are **risk accepted rather than answered**, and both must be re-read
before any shared deployment:

1. **The Input endpoint is unauthenticated** (Q8a-2). Auth is deferred by
   decision, behind `stats.input.auth.mode` with no default in code, a `WARN` on
   every `none` startup, and a test that refuses to start under `prod`. Until ZC
   answers, anything that can reach the Input port can write.
2. **No RPO/RTO number exists** (D5a). ADR-003 D5 has the framing to take to
   product and operations. Engineering's proposal, awaiting a decision: **RPO ≤ 5 min**
   via continuous WAL archiving, **RTO 1 h per partition / 24 h full cluster**. RPO is
   the tight one because a record we answered `200` and then lost is unrecoverable —
   Heimdal retries only on a non-2xx.
3. **The late-arrival policy has engineering agreement but no product signature**
   (D5b). Skip the write, meter `records_expired_on_arrival`, ACK, and alert when the
   counter is non-zero. It is a deliberate data-loss-by-design path, so T-026 stays
   blocked until it is signed.

The most valuable open question is **Q13b — do gateways report on synchronised
10-minute boundaries or jitter across them?** We design for the synchronised case
(13,333/s, 10× average). Jitter would drop the peak, and the hardware, by 10×. It is
also *measurable* rather than askable: a histogram of `ts.server mod 600` over 24 h
answers it.

The one item that genuinely gates work is **ZC's confirmation of
`docs/contracts/openapi.yaml`** — nine tasks sit behind it, essentially all of Phase 4.

**Closed on 29 August 2026 — do not reopen:** Q14a (clock skew — rejected at Input
beyond a configurable tolerance), Q3a (topology declared by broker-connected modules
only), Q5a (volume is 115.2M/day), DLQ retention (no TTL, ever), and `pg_partman`
(never a dependency). Reasoning:
`claude/OPEN_QUESTIONS_STATUS_29AUG2026.md`.

Full register: `claude/CODE_START_CHECKLIST.md` §A (with §A3 for the 29 Aug
decisions) and `claude/PROJECT_STATUS_AND_PLAN.md` §2b-2 and §2c.
