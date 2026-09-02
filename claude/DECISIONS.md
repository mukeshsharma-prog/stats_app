# Stats App — Decision Log

Every meaningful decision taken on this project, with the reasoning that
produced it. One entry per decision. If you cannot say *why*, it is not a
decision yet — it is a habit.

> **Created 29 August 2026.** Entries dated before that are **back-filled**:
> the decision was taken earlier and recorded elsewhere (an ADR, a gate, a
> commit, or only in the code), and this file is where the reasoning now
> lives in one place. Provenance is marked on every entry so you can tell a
> ratified decision from one that was only ever implied by code.

---

## 1. What this file is, and what it is not

This is the **running ledger**. It answers one question: *why is it like
this?* — for architecture, for libraries, for conventions, and for the small
implementation choices that never justified an ADR but would still cost a day
to re-derive.

| File | Answers |
|---|---|
| **`claude/DECISIONS.md`** (this file) | **Why** — every decision and its reasoning, in one list |
| `docs/architecture/adr/ADR-nnn` | The **full argument** for a structural decision, with alternatives and consequences |
| `claude/CODE_START_CHECKLIST.md` §A | **Whether we may start** — the stop/go register, owners and blockers |
| `claude/OPEN_QUESTIONS_STATUS_29AUG2026.md` | **What is still open**, who owns it, and what it blocks |
| `claude/FLOW.md` | **How it runs** — the execution flow, step by step |

**Precedence.** An ADR outranks this file on anything architectural. If they
disagree, the ADR is right and the entry here is stale — fix the entry. This
file never becomes the place a decision is *made*; it is the place every
decision is *found*.

**Rule.** A decision that changes code, contract, or operational behaviour gets
an entry here **in the same change** that implements it. Not afterwards.

---

## 2. Entry format

```
### DL-nnn — <one-line decision>

**Date** · **Status** · **Provenance** · **Supersedes**

**Decision.**       What was decided, in the imperative.
**Why.**            The reasoning. For a library: why this library.
**Rejected.**       What else was considered and the specific reason it lost.
**Consequence.**    What this costs, or what silently breaks if reversed.
```

### Decision-ID namespaces

The repository already had four schemes using a bare `D`, and they collided
(`claude/CLAUDE.md` §8). **`DL-nnn` is a fifth namespace and is deliberately
distinct.** Write it with the `DL-` prefix, always.

| Form | Means |
|---|---|
| `DL-nnn` | **An entry in this file** |
| `STA-nn`, `NFR-nn` | A requirement (SRS + v1.2 amendment) |
| `Dn` *(no hyphen)* | A decision question from `Stats_App_Answers_v1_0.docx` |
| `D-nn` *(hyphen)* | A stop/go register row in `CODE_START_CHECKLIST.md` §A |
| `ADR-003 Dn` | A decision internal to an ADR — always write the ADR prefix |
| `Gn` | A gate decision of 18 August 2026 |
| `Qn`, `Qna` | An open question to ZC / Heimdal |

### Provenance legend

Provenance says **how much authority an entry carries**, which is not the same
as how confident it sounds.

| Marker | Meaning |
|---|---|
| `RATIFIED` | Approved by a named approver on a named date. An ADR, a gate, or a register row. |
| `ANSWERED` | Decided by ZC / Heimdal / product. Not ours to change. |
| `ENGINEERING` | Ours, taken deliberately, recorded. Reversible by a new entry. |
| `FROM CODE` | **Observed in the repository and written down here for the first time.** The behaviour is real; the deliberation may never have happened. Treat as a proposal to ratify, not as settled law. |
| `PROPOSED` | Written down, not yet agreed. Someone else has to sign. |

---

## 3. Process and documentation

### DL-001 — Working documents live in `claude/`; the repository root holds a pointer only

**16 Aug 2026** · `RATIFIED` · `claude/CLAUDE.md` §10, `README.md`

**Decision.** Every working document lives in `claude/`. The root `CLAUDE.md`
contains a single `@claude/CLAUDE.md` import and nothing else.

**Why.** Claude Code auto-loads the root `CLAUDE.md` only. Keeping the content
there would mean the project context and the twelve other working documents
live in two different places and drift. The pointer gives auto-loading *and* one
directory.

**Rejected.** Content in the root `CLAUDE.md` — splits the working set across
two locations. A symlink — invisible in a diff, and does not survive every
checkout cleanly.

**Consequence.** Edit `claude/CLAUDE.md`; never the root file except to change
the import path.

---

### DL-002 — Requirement and ADR IDs are cited in code, commits, and configuration

**16 Aug 2026** · `RATIFIED` · `IMPLEMENTATION_RULES.md` §11

**Decision.** Behaviour driven by a requirement names its `STA-nn` and the
governing ADR — in the commit message, in a comment on the class or method, and
in `application.properties` next to the property.

**Why.** This project's hardest bugs are invisible ones: filtering on
`maintenant`, a TTL on the DLQ, timestamps read as milliseconds. Every one of
them looks like reasonable code. The citation is what tells the next reader that
a line is load-bearing before they simplify it away.

**Rejected.** A separate traceability matrix — it is maintained by someone
remembering to, which is to say it is not maintained.

**Consequence.** A change with no requirement basis is not "quick", it is
undecided. That is the point.

---

### DL-003 — The ARD's ADRs are cited as `ARD-ADR-nn`

**18 Aug 2026** · `RATIFIED` · Gate **G5**

**Decision.** Repository ADRs keep the bare `ADR-nnn` form and stay
authoritative. `Datahub_Core_StatsApp_ARD_v1_1.pdf` carries its own
ADR-001…010, which mean different things; cite those as `ARD-ADR-nn`.

**Why.** The ARD's ADR-004 is at-least-once delivery. Ours is OMS timestamp
normalisation. Two documents, one label, opposite subjects — someone was going
to implement the wrong one.

**Rejected.** Renumbering the repository ADRs — they are cited from the SRS
amendment, the OpenAPI contract, and the code.

**Consequence.** An unqualified "ADR-004" always means this repository.

---

### DL-004 — `DECISIONS.md` and `FLOW.md` are added as working documents

**29 Aug 2026** · `ENGINEERING`

**Decision.** Add this file and `claude/FLOW.md` to `claude/`, in the
existing uppercase naming convention, and index both in `README.md` and
`claude/CLAUDE.md` §10.

**Why.** The reasoning behind this project is real and unusually good, but it is
spread across six ADRs, two gate tables, a stop/go register, a 23,000-word open
questions status, and a lot of exceptionally load-bearing code comments. There
was no single list of *what was decided and why*, and no single description of
*how a record actually travels through the system*. Both are the first things a
new contributor — or a returning one — needs, and both were reconstructable only
by reading everything.

**Rejected.** Lowercase `decisions.md` / `flow.md` as literally requested —
`claude/` is uniformly uppercase (`CLAUDE.md`, `SKILLS.md`,
`DEVELOPMENT.md`); one lowercase pair would look like a stray file. Putting the
ledger in `docs/decisions/` — that directory holds *dated decision records* from
specific sittings, and this is a continuous index across all of them.

**Consequence.** Two more documents to keep current. DL-002's rule now covers
them: a decision is not implemented until it is logged.

---

### DL-005 — Changes are quizzed before they are made

**29 Aug 2026** · `ENGINEERING` · lands in `IMPLEMENTATION_RULES.md` §9

**Decision.** Before implementing a change, Claude quizzes the requester on the
decisions that change depends on. A wrong or unsure answer is not a failure to
move past — it is answered with an explanation, and then the quiz is asked
again. Implementation starts after the quiz is passed.

**Why.** On this project the expensive failures are silent: a query on
`maintenant` passes every test written against the supplied samples; a
timestamp read as milliseconds writes 1970 without an error; a TTL on the DLQ
deletes the last copy of a record and reports success. Code review does not
catch these, because the code looks correct — only someone who *knows the
trap* catches them. The quiz makes the reviewer's understanding a precondition
of the change rather than a hope.

There is a second reason. The requester is the approver on the 18 and 29 August
decision sets. An approver who cannot restate the reasoning is signing, not
deciding.

**Rejected.** Explaining the change without checking comprehension — the failure
mode being guarded against is exactly a confident, wrong mental model, and an
explanation cannot detect one. Quizzing only on request — it would be skipped
precisely on the days it matters.

**Consequence.** Changes take longer to start. That is the trade being bought
deliberately: the traps in `CLAUDE.md` §6 each cost far more than a quiz.

---

## 4. Platform and build

### DL-010 — Java 21

**13 Aug 2026** · `RATIFIED` · ADR-001

**Decision.** Java 21, pinned through a Gradle toolchain rather than inherited
from whatever JDK is on `PATH`.

**Why.** Records, pattern matching, and virtual threads are all directly useful
here — the envelope is a record, and the Input module is an I/O-bound HTTP
front end. Java 21 is the current LTS, so the choice does not need revisiting
inside V1. The toolchain pin matters as much as the version: a build that
compiles differently on two machines is not a build.

**Rejected.** Kotlin — the team and the wider Datahub Core work are Java; a
second language for one add-on is a maintenance tax with no offsetting gain
here. Java 17 — the SRS implied it, but it buys nothing and expires sooner.

**Consequence.** CI, images, and developer machines all need JDK 21.

---

### DL-011 — Spring Boot 3.5.16, pinned exactly, in one place

**18 Aug 2026** · `RATIFIED` · Gate **G6**, ADR-001

**Decision.** `springBoot = "3.5.16"` as a single entry in
`gradle/libs.versions.toml`. Every module imports the Boot BOM. **No module
build file declares a version of anything Boot manages.**

**Why.** ZC answered "> 3.5.x", which is not a version — it is a range, and a
range does not reproduce a build. G6 pinned an exact one. The single-entry rule
is the more valuable half: bumping `springBoot` moves the whole dependency set
as one tested, coherent unit, instead of a pile of independently drifting pins
that nobody has ever tested together.

**Rejected.** Leaving it provisional pending Q1a — Q1a was answered the same
day (**there is no internal reference repo**), so there was never anything to
reconcile against and the pin is final. Per-module versions — that is how a
multi-module build acquires two Jackson versions and a `NoSuchMethodError` at
runtime.

**Consequence.** A future ZC platform standard is a one-line change. That is
the whole reason for the single entry.

---

### DL-012 — Gradle, Kotlin DSL, wrapper only, version catalog

**13 Aug 2026** · `RATIFIED` · ADR-001

**Decision.** Gradle multi-module with the Kotlin DSL, the Gradle Wrapper as
the only supported entry point, and `gradle/libs.versions.toml` as the single
source of dependency versions.

**Why.** Gradle was a given from the requirements. Kotlin DSL over Groovy for
type-safe accessors and real IDE completion — a mistyped Groovy property fails
at execution time, a mistyped Kotlin one fails at compile time. Wrapper only
means no developer needs a matching Gradle installed, and CI runs the same
Gradle every machine does.

**Rejected.** Maven — not what the requirements specify. Groovy DSL — silently
tolerates typos. A globally installed Gradle — reintroduces "works on my
machine" at the build-tool layer.

**Consequence.** One Kotlin DSL quirk is already load-bearing and commented in
the root build: the generated `libs` accessor resolves against the project whose
scope it is evaluated in, so inside `subprojects { }` it must be captured from
the root first (`val catalog = libs`).

---

### DL-013 — Four Gradle modules, three Docker images

**13 Aug 2026** · `ANSWERED` (Q2) · ADR-001, `settings.gradle.kts`

**Decision.** `stats-common` (library), `stats-input`, `stats-writer`,
`stats-api` (images). Each application module may depend on `stats-common`;
**never on another application module.**

**Why.** The three application modules have genuinely different scaling and
failure profiles — Input is sized for a 10× synchronised burst, Writer for
sustained drain, the API for read concurrency. Separate images let each scale
and fail alone. The dependency rule is what keeps that true: one import from
`stats-api` to `stats-writer` would put a database write path in the read-only
service and quietly break the "only Module 2 writes" rule (STA-32).

**Rejected.** One image with profiles — a poison payload crash-loops all three
roles at once. A shared "core" module between application modules — that is the
import that breaks the write-isolation rule; `stats-common` is the only shared
node and it is deliberately narrow (DL-014).

**Consequence.** The Spring Boot plugin is declared at the root and applied
only in the three application modules; `stats-common` is a plain `java-library`
and produces no executable.

---

### DL-014 — `stats-common` holds exactly five things

**24 Aug 2026, extended 29 Aug 2026** · `RATIFIED` · `CLAUDE.md` §2, decision **D15**

**Decision.** The envelope model, the stat-type enum, the AMQP topology
constants, RFC 9457 Problem Details with the error catalogue, and Draft 2020-12
schema validation. **No module-specific application logic, ever.**

**Why.** Each of the five is shared *by construction*, not by convenience. The
topology constants are the sharpest case: two modules declaring the same queue
with different arguments does not warn — RabbitMQ rejects the mismatched
declaration with `RESOURCE_LOCKED` and the module fails to start. Declaring
them once is the fix, not the tidy-up.

The validator was the contested addition. It belongs here because
`docs/schemas/` is normative for the whole project and the Java oracle must stay
in parity with the Python CI gate — a shared capability, not Input's business
logic.

**Rejected.** A general "common" or "util" module — this is exactly how a shared
module becomes a dumping ground that every module must be rebuilt against.
Duplicating the topology constants per module — the `RESOURCE_LOCKED` failure
above.

**Consequence.** Adding anything to `stats-common` needs an argument that it is
common to all three application modules. The build file names the five and says
so.

---

### DL-015 — CI is `./gradlew build` plus Testcontainers on every push

**18 Aug 2026** · `ENGINEERING` · ADR-001, `2B-ANSWERS-18AUG2026.md` §1

**Decision.** Every push runs the full build and the Testcontainers integration
tests. **The build fails on a failed schema-fixture assertion.**

**Why.** With no reference project to inherit conventions from (Q1a), these had
to be chosen rather than copied. The fixture gate is the specific one worth
naming: `docs/schemas/` is normative, and the fixtures are the only mechanism
that keeps the Java validator and the Python validator agreeing about what a
valid record is. A drift there is silent in both directions.

**Rejected.** Integration tests on a schedule or pre-merge only — the failures
being guarded against (topology mismatch, retention boundaries, tenant leakage)
are precisely the ones a unit test cannot see.

**Consequence.** CI needs a Docker daemon. Accepted; there is no way to test
partitions, quorum queues, or `ON CONFLICT` semantics against a mock and learn
anything true.

---

## 5. Libraries — why each one is here

> **Standing rule (`CLAUDE.md` §9):** prefer what Spring Boot already brings
> in. A new dependency needs an explanation of why the platform libraries
> cannot do the job. Every entry below carries one.

### DL-020 — Jackson `databind` is a compile-time `api` dependency of `stats-common`

**24 Aug 2026** · `FROM CODE` · `stats-common/build.gradle.kts`

**Decision.** `stats-common` exposes Jackson on its public API
(`api(libs.jackson.databind)`), not merely as an implementation detail.

**Why.** Two envelope decisions force it into the public contract. `data` is a
`JsonNode`, because the D14 unknown-field policy requires unknown members inside
`data` to be **preserved** — a typed DTO would silently drop them. And
`Timestamps` carries Jackson converters so the wire keeps Unix seconds while
Java sees `Instant`. A consumer of `StatsEnvelope` cannot use it without
Jackson, so hiding it behind `implementation` would be a lie the compiler would
eventually catch.

**Rejected.** `Map<String, Object>` for `data` — loses ordering and node types
and makes byte-identical republication harder. A generated typed DTO per stat
type at the envelope level — breaks the additive-new-type rule (STA-14A).

**Consequence.** `stats-common` still has no Spring context, no broker client
and no JDBC driver. That boundary is the one being protected; Jackson is not on
the wrong side of it.

---

### DL-021 — NetworkNT `json-schema-validator` for Draft 2020-12

**24 Aug 2026** · `ENGINEERING` · `libs.versions.toml`, `StatsSchemaValidator`

**Decision.** `com.networknt:json-schema-validator`, kept **private behind our
own `ValidationResult` API**.

**Why this library.** Jackson can parse and represent a JSON tree but cannot
evaluate a JSON Schema — that is not something it does. Draft 2020-12 support is
required because the schema catalogue uses it, and NetworkNT is the mature JVM
implementation with it. It is wrapped rather than exposed so the validator can
be replaced without touching every caller.

**Rejected.** Hand-written validation — five schemas with `patternProperties`,
`$defs`, conditional requirements and the deliberate `ts.converter` rejection;
hand-rolling this guarantees divergence from the normative files and from the
Python gate. Bean Validation annotations alone — cannot express the schema
catalogue, and would put the normative rules in two places.

**Consequence.** NetworkNT error messages are **deliberately discarded** — they
can embed the offending value, and echoing attacker-controlled input into a
400 response is how a validator becomes a reflection gadget. Only JSON Pointers,
machine-readable codes and reviewed `SafeMessage` constants cross the public
boundary (STA-03A).

---

### DL-022 — NetworkNT **2.x**, not 3.x — pinned at 2.0.4

**24 Aug 2026** · `ENGINEERING` · `libs.versions.toml`

**Decision.** Pin `jsonSchemaValidator = "2.0.4"` and do not take the 3.x line.

**Why.** NetworkNT 3.x is built on **Jackson 3**. This is a Spring Boot 3
project and Spring Boot 3 manages **Jackson 2**. Taking 3.x would put two
Jackson major versions on one classpath — the failure is a runtime
`NoSuchMethodError` or `NoClassDefFoundError`, not a build error, so it would
be found in a container rather than in CI.

**Rejected.** Latest-is-best — here it is a classpath conflict wearing the
word "upgrade". Shading — a large hammer for a dependency that is doing fine.

**Consequence.** This pin is coupled to DL-011. When Spring Boot moves to
Jackson 3, NetworkNT 3.x becomes the correct version and **not before**. Note
that ordering here; a routine dependency bump in the wrong order breaks
validation at runtime.

---

### DL-023 — Joni, for ECMA-262 regex semantics

**24 Aug 2026** · `ENGINEERING` · `libs.versions.toml`, `StatsSchemaValidator`

**Decision.** Add `org.jruby.joni:joni` and configure NetworkNT to use
`JoniRegularExpressionFactory`.

**Why this library.** JSON Schema specifies **ECMA-262** regular expressions.
Java's `java.util.regex` is not ECMA-262 — it differs on anchors, character
classes, and escapes. The Python CI gate validates the same fixtures with
Python's engine. Leaving the Java validator on Java regex means two validators
that agree on the fixture corpus today and disagree on the first pattern that exercises
a difference — accepting in one and rejecting in the other, with no error
anywhere. Joni is the ECMA-262-compatible engine NetworkNT integrates with, and
this is the one dependency whose entire job is *making two implementations
agree*.

**Rejected.** Java regex — silent cross-implementation divergence, which is the
failure the fixture parity gate exists to prevent. Writing patterns in a
"safe subset" both engines share — unwritable and unenforceable; nothing checks
it.

**Consequence.** One extra dependency, and `clsbox` / counter patterns behave
identically in `validate-fixtures.py` and `StatsSchemaValidator`. That parity is
asserted by `SchemaFixtureParityTest`.

---

### DL-024 — Flyway, not Liquibase

**24 Aug 2026** · `RATIFIED` · ADR-003 **D6**

**Decision.** Flyway, with `flyway-database-postgresql` (mandatory from Flyway
10 onward for PostgreSQL support).

**Why this library.** The migrations here are partition DDL, a composite
primary key with a specific column order, and index definitions — plain
PostgreSQL, some of it not expressible in a database-agnostic abstraction at
all (`DETACH PARTITION CONCURRENTLY`, `pg_try_advisory_lock`). Flyway runs plain
`.sql` files, so a migration is reviewable as the SQL that will actually
execute.

**Rejected.** Liquibase — its XML/YAML changelogs buy database portability this
project explicitly does not want (PostgreSQL is the only V1 persistence
technology), and they obscure exactly the DDL that most needs reading. It was
provisional only pending Q1a; Q1a closed with no reference project mandating it.
Hand-run SQL scripts — no version tracking, no repeatable environment.

**Consequence.** Migrations are owned by `stats-writer` and reviewed like code.

---

### DL-025 — Testcontainers for PostgreSQL and RabbitMQ; no mocks for either

**13 Aug 2026** · `RATIFIED` · ADR-001, `IMPLEMENTATION_RULES.md` §10

**Decision.** Integration tests run against real PostgreSQL and real RabbitMQ
in containers. Retention and index tests **must** run against a real
PostgreSQL — a mock is not acceptable evidence.

**Why this library.** Every behaviour that actually matters here is a real
engine behaviour: `ON CONFLICT (ts_device, uid) DO NOTHING` returning zero
affected rows, partition pruning, `RESOURCE_LOCKED` on a mismatched queue
declaration, quorum-queue TTL dead-lettering, advisory locks. A mock returns
whatever the test author believed — which is the belief under test.

**Rejected.** H2 or an embedded PostgreSQL — no real partitioning, different
`ON CONFLICT` behaviour, so a green test proves nothing about production. A
mocked `RabbitTemplate` — cannot exhibit a publisher confirm, a return callback,
or a TTL bounce, which is to say it cannot test any of the reliability design.

**Consequence.** CI needs Docker (DL-015), and the test suite is slower. Both
accepted.

---

### DL-026 — Spring Boot Actuator for separate liveness and readiness probes

**24 Aug 2026** · `RATIFIED` · Decision **D13**

**Decision.** Actuator, with `management.endpoint.health.probes.enabled=true`.
**Readiness probes only that module's real dependencies.**

**Why this library.** It is in the platform already and gives liveness,
readiness and Prometheus metrics from one starter. The important half is the
*separation*: a single health endpoint that checks everything means a broker
blip restarts the container instead of removing it from the load balancer for
thirty seconds.

**Rejected.** A hand-written `/health` — reimplements what the platform ships,
and always ends up as one combined check. One shared probe definition across
modules — Input's readiness must **not** include the database (it has no
database dependency at all, by rule 2), and the API's must not include the
broker (it has no broker connection at all, ADR-002 §11).

**Consequence.** Each module's readiness set is written per module and is part
of the review; a copied readiness block is a bug.

---

### DL-027 — No MongoDB, and no `pg_partman`

**24 & 29 Aug 2026** · `RATIFIED` · `CLAUDE.md` §3, ADR-003 **D1**

**Decision.** MongoDB is out of scope entirely. `pg_partman` is **never** a
dependency; partition pre-creation is a scheduled task inside `stats-writer`
behind the D3 advisory lock.

**Why.** MongoDB is a v1.0 document artefact — the decision went to PostgreSQL
and is not deferred. It is listed here because the superseded `.docx` sources
still say otherwise and a cached memory of them is a live risk.

`pg_partman` fails on its own terms: managed PostgreSQL frequently does not
expose the extension, so building on it imports a platform risk for no gain —
and the advisory lock is needed **regardless**, because D8 deploys N writer
replicas per stat type and they would otherwise race. Given the lock exists, the
extension adds a dependency and removes nothing.

**Rejected.** Making `pg_partman` conditional — a conditional dependency is two
code paths, one of which is exercised only in the environment you did not test.

**Consequence.** Partition pre-creation is ours to write, test and monitor. If
partitions are not pre-created, **inserts fail** — this is on the critical path,
not a maintenance nicety.

---

## 6. Messaging

### DL-030 — One durable queue per stat type; never a shared queue

**13 Aug 2026** · `RATIFIED` · ADR-002, STA-10A

**Decision.** `app_stats_input` topic exchange, routing key = the exact stat
type literal, and a dedicated durable main queue `app_statsstore.<type>` per
type.

**Why.** Head-of-line blocking. On a shared queue, one poison `netstats` message
stalls `lorastats`, `ltestats`, `sysstats` and `omsstats` behind it — five
types sharing one failure domain and one scaling knob. Per-type queues also make
consumer concurrency, prefetch and replica count independently tunable, which
STA-11A/20A/26A require.

**Rejected.** One `app_statsstore` queue as the implementation brief named —
shared failure domain and shared bottleneck, and it conflicts with STA-10/20.
Input writing PostgreSQL directly — destroys the buffer and the single-writer
architecture.

**Consequence.** There must be a test proving a poison message in one type does
not delay another. Without it this decision is only an intention.

---

### DL-031 — Three retry tiers plus a DLQ, all delays externalised

**18 Aug 2026** · `RATIFIED` · Gates **G1/G4**, ADR-002

**Decision.** `app_statsstore.<type>.retry.<tier>` for tiers 1–3 and
`app_statsstore.<type>.dlq`, sharing the main queue's prefix. Delays and the
maximum attempt count live in `application.properties` — starting values 30 s /
5 min / 30 min. **Immediate requeue is never used for a persistent failure.**

**Why.** Three tiers separate three genuinely different failures: a
momentary blip (30 s), a short dependency outage (5 min), and a longer one
(30 min). A single tier forces one delay to serve all three, so it is either
too aggressive for the outage or too slow for the blip. Immediate requeue for a
persistent failure is a hot loop that burns broker and database capacity while
achieving nothing.

Externalising the delays matters because the right values are an operational
finding, not a design one — they will be tuned against a real incident, and a
hard-coded value makes that a rebuild.

**Rejected.** One retry queue (the original position) — cannot express the
three cases. A backoff multiplier — ADR-006 fixes three explicit tiers so the
delay a message will experience is readable from configuration rather than
computed.

**Consequence.** Adding a type adds four queues. All names derive from
`StatType.wireName()` so it cannot leave a partially enumerated set behind.

---

### DL-032 — Quorum queues for main, retry, and dead-letter

**24 Aug 2026** · `RATIFIED` · ADR-002 §8

**Decision.** Every queue declares `x-queue-type: quorum`.

**Why.** The dev and target broker is RabbitMQ 4, where classic mirrored queues
no longer exist — quorum is *the* replicated durable queue type. Declaring
messages persistent (STA-13A) means little if the queue holding them does not
survive a node loss. Quorum supports both mechanisms the retry design depends
on: queue-level `x-message-ttl` for tier delays and `x-dead-letter-exchange` for
the bounce back.

**Rejected.** Classic queues — not replicated, and on RabbitMQ 4 the mirroring
that used to compensate is gone. Streams — built for replay-from-offset
consumption, not per-message ack with dead-lettering.

**Consequence.** Quorum queues hold more state resident; a synchronised burst
leaves roughly 12,000 messages per type queued (Q13a), which is comfortable —
and if measurement disagrees the lever is `x-max-in-memory-length`, **not** a
change of queue type. **A queue's type is fixed at declaration**: changing one
means deleting and redeclaring it, and a mismatched redeclaration is the
`RESOURCE_LOCKED` startup failure.

---

### DL-033 — Publish with `mandatory=true` and a `ReturnsCallback`

**24 Aug 2026** · `RATIFIED` · ADR-002 §9

**Decision.** Input publishes with the `mandatory` flag and registers a
`ReturnsCallback`. A returned message is a **failed acceptance**: 503,
`records_unroutable{type}` incremented, `ERROR` logged with the routing key.

**Why.** **A publisher confirm does not close this hole.** A confirm says the
*broker* accepted the message, not that any queue wanted it. If a binding is
missing — a new stat type deployed before its queue, a typo in a routing key, a
half-applied topology change — the broker ACKs the publish and drops the message
on the floor. Input would return 200, Heimdal would mark it delivered and never
retry, and the record would be gone with every component reporting success.

**Rejected.** Confirms alone — precisely the gap above. Treating a return as a
warning — the message is already gone; a warning makes the loss quiet rather
than absent.

**Consequence.** There must be a test that publishes to a routing key with no
bound queue and asserts 503 plus the counter. A test asserting only the confirm
would pass while the record is silently discarded.

---

### DL-034 — The DLQ carries no TTL and no onward route — enforced by the type system

**29 Aug 2026** · `RATIFIED` · ADR-002 §12, `AmqpTopology`

**Decision.** `deadLetterQueueArguments()` returns exactly
`{x-queue-type: quorum}` and **has no parameter to set a TTL or a dead-letter
exchange with**.

**Why.** A TTL'd dead-letter queue is silent data loss. A message that reached
the DLQ has already survived three retry tiers — it is the only remaining copy
of a record Heimdal believes it delivered and will never resend. Deleting it on
a timer violates rule 9 outright. A DLQ that dead-letters *onward* is the same
failure wearing a different name.

The design move worth noting is **where** this is enforced. It is not a comment
and not a review checklist item: the method takes no arguments, so the policy is
*unrepresentable* rather than merely documented. Bounding broker disk is an
alerting and disposition problem — alert on depth > 0 **and** oldest-message
age, manual disposition within 14 days.

**Rejected.** A long TTL "as a safety valve" — a safety valve that discards the
last copy of the data is not one. Documenting the rule and trusting review — the
next person adding a TTL would be doing something that looks entirely
reasonable.

**Consequence.** Redrive is manual, by an authorised operations role, explicit
per type, capped at 2× nominal, root cause first. **And the trap for the
runbook:** a message that sat in the DLQ long enough becomes
`expired_on_arrival` the moment it is redriven, because its `ts.device` has aged
past the retention window. **A redrive after ~90 days is a no-op that increments
a counter** — operators must know this before attempting one, or a failed
recovery looks like a successful one.

---

### DL-035 — Only broker-connected modules declare topology; `stats-api` has no broker connection

**29 Aug 2026** · `RATIFIED` · ADR-002 §11 (closes **Q3a**)

**Decision.** `stats-input` and `stats-writer` declare the topology, from the
shared `stats-common` constants, identically and idempotently. `stats-api` holds
**no RabbitMQ connection at all**.

**Why.** ZC's answer read *"every module have to declare the exchanges, queues
and bindings."* Taken literally, that hands the read-only API a broker
dependency it has no use for, and adds a third declaring module to a set where
any argument mismatch is a `RESOURCE_LOCKED` **startup failure** rather than a
warning. Read as *every broker-connected module*, the answer and the service
boundaries agree.

**Rejected.** Literal compliance — a broker dependency in the API means its
readiness probe, its failure modes and its restart behaviour all now include a
system it never talks to. Waiting for a platform sign-off — T-032, the
declaration-conflict test, proves at build time what a sign-off would only
assert in prose.

**Consequence.** `stats-api` starting with no broker connection is itself an
assertion. The platform acknowledgement is a courtesy; do not wait on it, and do
not give the API a broker connection in the meantime.

---

## 7. Persistence

### DL-040 — PostgreSQL 17, one table per stat type

**24 Aug 2026** · `ANSWERED` + `RATIFIED` · ADR-003, STA-21A

**Decision.** PostgreSQL 17 is the only V1 persistence technology. One table
per stat type; types are never mixed.

**Why.** Answered, not deferred — the v1.0 SRS said MongoDB and the answer set
changed it. One table per type follows from the same isolation argument as the
per-type queues, and it is what makes DL-041's typed columns possible: a shared
table would force `jsonb` or a union of every type's columns.

**Rejected.** MongoDB — decided against; do not introduce drivers,
`MongoRepository`, TTL indexes, or the words "collection" and "document". A
shared table with a `type` discriminator — kills typed columns and makes adding
a type a change to existing types (STA-14A).

**Consequence.** Five tables, five sets of partitions, five migrations. Adding a
type is additive across all of it.

---

### DL-041 — Typed columns for four types; hybrid `jsonb` for `netstats`

**24 Aug 2026** · `RATIFIED` · ADR-003 **D2**

**Decision.** Typed columns for `lorastats`, `ltestats`, `sysstats`,
`omsstats`. For `netstats`, typed columns for the stable fields and **`jsonb`
for the interface map**.

**Why.** `jsonb` stores every key name in every row. `lorastats` has 13 keys
averaging ~14 characters — roughly 180 bytes per row of nothing but repeated key
strings, on the order of **375 GB per type** of pure overhead at steady state.
Typed columns eliminate it and are directly indexable.

`netstats` is the exception on purpose, and the reason survives Q4a being
answered. Its schema is deliberately **open at both levels**: any interface name
is legal (`eth1`, `ppp0`), and unknown fields on an interface are accepted and
preserved. Fixed `eth0_*` / `wwan_*` columns would **silently drop** an
interface the schema permits — directly violating the D14 unknown-field policy.
A `jsonb` map preserves them by construction, and no migration against two
billion rows is needed the first time a gateway reports `eth1`.

**Rejected.** `jsonb` everywhere — the 375 GB/type overhead and JSON path scans
on the dominant filters. Typed columns everywhere including `netstats` — silent
data loss on any interface not anticipated.

**Consequence.** Counters are **`BIGINT`, not `INTEGER`** — the SRS says
"integer", but these are monotonic counters over long device uptimes and `int4`
overflow is silent corruption; `bigint` costs four bytes. And because the
schema's digit pattern caps at 19 digits, which is **wider** than signed
`BIGINT` — a gap not expressible as a regex — **the Writer must range-check
before conversion** (STA-04C). An unchecked overflow corrupts the value with no
error anywhere.

---

### DL-042 — Daily `RANGE (ts_device)` partitions; retention is detach-and-drop

**24 Aug 2026** · `RATIFIED` · ADR-003 **D1**

**Decision.** Every stat-type table is `PARTITION BY RANGE (ts_device)` with
**daily** partitions. Retention is `DETACH PARTITION CONCURRENTLY` then
`DROP TABLE` — **not** row deletion.

**Why.** At 115.2M records/day, retention is not a periodic cleanup — it is a
second write path running permanently at full ingestion rate (267 rows/sec/type).
Row-by-row deletion would generate ~115 million dead tuples per day across tables
of ~2 billion rows each, which autovacuum will not keep up with: table and index
bloat, lengthening deletes, degrading writer. Dropping a partition is a catalog
operation — milliseconds, no dead tuples, no vacuum debt.

Daily granularity gives 90 live partitions per type, 450 across five — comfortably
within what the PG17 planner handles. Hourly gives 10,800 and buys nothing.

**The alignment worth protecting:** the retention basis and the dominant query
pattern are *both* `ts_device`, so one partition key serves both — retention is a
drop, and time-ranged queries prune partitions. Do not give that up lightly.

**Rejected.** Batched deletion — properties remain defined for a fallback path
only. Monthly partitions — a 90-day window would drop three months at a time.

**Consequence.** **Partitions must be pre-created ahead of ingestion or inserts
fail** (DL-027).

---

### DL-043 — `PRIMARY KEY (ts_device, uid)`, in that column order

**18 Aug 2026** · `RATIFIED` · Gate **G2**, ADR-003, STA-21B

**Decision.** `PRIMARY KEY (ts_device, uid)` — `ts_device` first — scoped per
partition.

**Why.** Two hard constraints and one preference. A **global** unique index is
not creatable on a partitioned table, so per-partition scoping is not a choice.
`ts_device` must lead so the key includes the partition key and matches the
mandatory `(ts_device DESC, uid DESC)` sort. Rule 13 originally said
`(uid, ts_device)`, which describes an object PostgreSQL cannot create here.

Per-partition scoping is still sufficient, and this is the subtle part: a
**redelivered record carries the same `ts_device`**, so it lands in the same
partition and collides with the same key. That guarantee comes from ADR-004 —
publication is byte-identical, so nothing rewrites a timestamp in flight.

**Rejected.** `(uid, ts_device)` — wrong order for both the partition key and
the sort. Including `tenant` in the key — deliberately excluded, see DL-044.

**Consequence.** **This decision is load-bearing on ADR-004.** If anything ever
transforms a record in flight, per-partition duplicate suppression stops working
and the failure is silent. Check any future in-flight transformation proposal
against this first. A secondary `(tenant, uid)` index carries the `uid`-only
lookup, which cannot prune partitions.

---

### DL-044 — Duplicate suppression is metered, never silent; `tenant` is out of the key

**24 Aug 2026** · `RATIFIED` · ADR-005, STA-27A

**Decision.** `INSERT … ON CONFLICT (ts_device, uid) DO NOTHING`. The writer
reads the affected-row count and increments `records_duplicate_suppressed{type}`
on zero.

**Why.** At-least-once delivery means duplicates are normal, not exceptional —
a replay is duplicates by definition. Suppressing them without a counter would
violate the no-silent-error-handling rule, and it would hide something worse:
because `tenant` is deliberately **not** in the key, a cross-tenant `uid`
collision would be absorbed as a duplicate. **The counter is the only thing that
would ever reveal one.**

The `ON CONFLICT` form is safe without a field-by-field comparison only because
ADR-004 guarantees byte-identical publication (DL-043).

**Rejected.** `ON CONFLICT DO UPDATE` — rewrites a row with identical content
and generates a dead tuple per duplicate. Including `tenant` in the key — makes
a cross-tenant collision invisible instead of merely rare.

**Consequence.** Replay spikes this counter **by design** — do not alert on the
count alone. A sustained non-replay increase is the cross-tenant signal, and if
it ever appears, `tenant` goes into the key.

---

### DL-045 — `stats-writer` owns retention and pre-creation, behind a per-type advisory lock

**24 Aug 2026** · `RATIFIED` · ADR-003 **D3**

**Decision.** `stats-writer` owns partition creation and retention, guarded by
`pg_try_advisory_lock(hashtext('stats.retention:' || :statType))`. A replica
that does not acquire the lock skips the run and logs at DEBUG. The five types
are staggered, not fired together.

**Why.** The writer is the only component permitted to write (rule 2) — giving
retention to anything else creates a second writer. But horizontal writer
scaling is a MUST (STA-06/26/39), so with three replicas you would otherwise get
three concurrent retention jobs racing on the same partitions.
`pg_try_advisory_lock` is the boring solution and **adds no dependency**.

**Rejected.** ShedLock or Quartz — both add a dependency and a schema for
something one PostgreSQL built-in already does. A dedicated maintenance
deployment (the ARD's proposal) — adds a deployment to operate and still needs
the same lock; keep the lock, drop the instance (ADR-002 §10).

**Consequence.** Retention correctness now depends on the lock being taken on
*every* path that touches partitions, pre-creation included.

---

### DL-046 — `schema_version` is stored on every row; `ingested_at` is not added

**24 Aug 2026** · `RATIFIED` · ADR-003 **D7**

**Decision.** Add `schema_version smallint NOT NULL`, set from the schema that
actually validated the record. Reject the ARD's `ingested_at` as a rename of the
existing `received_at`.

**Why.** Adding a column to a ~2 billion row table is genuinely expensive, and
the moment a schema changes — which STA-14A guarantees will happen — the first
question of any backfill is *which rows were written under which rules*. Without
this column that question is answered by joining against deployment dates, which
is to say it is not answered. Two bytes now against a table rewrite later is not
a close call. `NOT NULL` with no default, so a row can never claim a version
nothing verified.

**Rejected.** Deriving the version from `received_at` and a deployment log —
approximate, and wrong across a rolling deploy. `ingested_at` — the same value
as `received_at` under a different name; a rename buys nothing and costs a
migration.

**Consequence.** The Writer must pass through which schema validated the record,
so validation and persistence are coupled by one small contract.

---

### DL-047 — One Writer image, deployed once per stat type

**24 Aug 2026** · `RATIFIED` · ADR-003 **D8**

**Decision.** `stats-writer` stays a single Docker image, deployed once per stat
type, with `STAT_TYPE` selecting the queue. `stats.writer.stat-type=${STAT_TYPE:all}`
— `all` is a **development convenience only**; production sets exactly one.

**Why.** Per-type consumer concurrency, prefetch and replica count are a MUST
(STA-11A, STA-20A, STA-26A) and are only independently tunable if the instances
are separate. It also means a crash loop caused by one type's payload cannot
take down consumption of the other four — the per-type queue isolation argument
carried one layer up.

**Rejected.** A fourth image per type — Q2 fixed three images; this is a
deployment topology, not a new artefact. One instance consuming all five in
production — loses both the tuning and the isolation.

**Consequence.** Five writer deployments to operate. The `all` default is the
one place this could go wrong in production, which is why it is called out here
and in the properties file.

---

### DL-048 — Individual inserts first, behind a batch-shaped interface

**18 Aug 2026** · `RATIFIED` · Gate **G3** (amends **D11**)

**Decision.** Bounded micro-batching (100–500 records or 50–200 ms, whichever
first) with single-record fallback is the **target**. Build **individual
inserts first**, behind a batch-shaped interface.

**Why.** The two positions were never really opposed. D11 wanted individual
writes to preserve per-record DLQ precision — a batch that fails wholesale
cannot say which record was poison. The single-record fallback preserves exactly
that. Building individual first behind the batch-shaped interface makes batching
a **benchmarked tuning change against real numbers, rather than a rewrite** —
and avoids optimising throughput before anything has been measured.

**Rejected.** Batching from the start — optimises before measuring and risks
losing per-record DLQ precision. Individual writes permanently — will not hold
the drain rate.

**Consequence.** The insert interface must be batch-shaped from the first
commit. Getting this wrong is cheap now and expensive later.

---

## 8. Validation, errors, and the record contract

### DL-050 — No transformation: the published message is byte-identical to what was received

**Aug 2026** · `RATIFIED` · ADR-004

**Decision.** The Stats App performs **no timestamp normalisation** and no
transformation of any kind. What is published to RabbitMQ is byte-identical to
what arrived over HTTP.

**Why.** The v1.0 SRS describes an `omsstats`-specific `ts.converter` field and
implies normalisation. That description is out of date — Heimdal already emits
the canonical `ts.server` for `omsstats` exactly as for every other type, so
there is nothing to normalise.

**The consequence is larger than the decision.** Byte-identical publication is
what makes ADR-003's per-partition duplicate suppression work: a redelivered or
replayed record carries the same `ts_device`, lands in the same partition, and
collides with the same `(ts_device, uid)` key. **If Input ever rewrote a
timestamp, duplicate suppression would stop working and the failure would be
silent.**

**Rejected.** Supporting `ts.converter` as a "harmless" backward-compatibility
gesture — explicitly out of scope; reopening requires a new approved
compatibility ADR.

**Consequence.** A record carrying `ts.converter` is **invalid** — 400, nothing
published. `envelope-v1.schema.json` encodes this directly: `ts` keeps
`additionalProperties: true` but adds `"not": {"required": ["converter"]}`, so
`ts.converter` fails while other unknown `ts` members are still tolerated. That
asymmetry is deliberate — `ts.converter` is not merely unrecognised, it is a
field whose presence means the sender believes in a step this system does not
perform.

---

### DL-051 — Unknown fields: accept and preserve in `data`, reject invalid envelope fields

**24 Aug 2026** · `ENGINEERING` · decision **D14** (renamed from `D6`)

**Decision.** Unknown fields inside `data` are accepted and preserved. Envelope
fields are validated strictly.

**Why.** The two halves serve different risks. `data` is the type-specific
payload and it will grow — Q4a explicitly confirmed unanticipated `netstats`
interfaces are legal — so rejecting unknown members there would break on
Heimdal's next additive change, and *dropping* them silently would lose data
we were told to expect. The envelope is a contract of seven fields with security
meaning (`tenant`) and storage meaning (`ts.device`); an unrecognised field
there is a signal that the sender and this system disagree about the contract.

**The renaming is itself part of the decision.** This was labelled `D6`, but
answer-set `D6` already means byte-identical publication and ADR-003's `D6` is
the migration tool. Three meanings for one label is the failure `ARD-ADR-nn` was
introduced to fix.

**Rejected.** Whatever Jackson happens to do by default — this is a real
decision about data loss and contract drift, not a deserialiser setting.
Strict everywhere — breaks on the first additive Heimdal change.

**Consequence.** `data` is a `JsonNode` (DL-020), and `netstats` interfaces are
`jsonb` (DL-041). Both follow from this entry.

---

### DL-052 — Clock skew is rejected at Input, in code, not in the schema

**29 Aug 2026** · `RATIFIED` · ADR-003 **D9** (closes **Q14a**)

**Decision.** A record whose `ts.device` exceeds
`ts.server + stats.input.validation.max-clock-skew` (default `PT5M`) is
rejected at Input with 400 and the field code `clock_skew_exceeded`, metered as
`records_clock_skew_rejected{type}`. Anything inside the tolerance is accepted.

**Why.** A `ts.device` in the future is not a plausible reading — it is a
misconfigured clock, and the record it produces lands in a **future partition**
where retention will never reach it. It has to be caught somewhere, and Input is
the only place that can tell the sender.

Why *code* and not the schema: the check compares two fields, and JSON Schema
cannot express a cross-field relation. That is also why the five
`edge/<type>-clock-skew-future.json` fixtures stay **schema-valid** and carry
`$expect: "input-reject"` — they are testing the Input rule, not the schema.

The tolerance exists because a few seconds of NTP drift is normal and must not
be an outage. It is externalised, never hard-coded (the G4 precedent).

**Rejected.** Accepting and letting it land in a future partition — silent
unbounded retention. Rejecting at the Writer — the sender has already been told
200 and will never retry. A schema rule — not expressible.

**Consequence.** This was filed as a question for someone else for weeks. It was
never a product question, which is the note worth keeping: check whether a
blocker is actually yours before waiting on it.

---

### DL-053 — Validation errors never echo the offending value

**24 Aug 2026** · `RATIFIED` · STA-03A, `StatsSchemaValidator`, `FieldError`

**Decision.** Only JSON Pointers, machine-readable codes and reviewed
`SafeMessage` constants cross the public boundary. NetworkNT's own messages are
discarded. `FieldError` has no public constructor and accepts message text only
through the `SafeMessage` enum. `StatType.fromWireName` throws
`"Unknown stat type literal"` **without the literal**.

**Why.** Validator messages embed the value that failed. That value is
attacker-controlled and is about to be placed in a response body — reflecting it
turns the validator into a reflection gadget, and at minimum leaks internal
schema detail to whoever is probing. The `SafeMessage` enum makes safety a type
constraint rather than a review habit: constructing an unreviewed message is not
possible.

**Rejected.** Sanitising the value before echoing — a filter that must be right
every time, versus a design where the unsafe path does not exist. Passing
NetworkNT messages through — convenient, and exactly the leak.

**Consequence.** Debugging a rejected record needs the JSON Pointer plus the
code, not a quoted value. That trade is deliberate.

---

### DL-054 — `docs/schemas/` is the single source; the build copies it, nothing is checked in twice

**24 Aug 2026** · `FROM CODE` · `stats-common/build.gradle.kts`

**Decision.** `processResources` copies `docs/schemas/*.schema.json` into
`stats-common`'s generated output, and the test source set reads
`docs/schemas/fixtures` directly. **No second copy is checked in.**

**Why.** The schemas are normative and are validated by two independent
implementations — `validate-fixtures.py` in CI and `StatsSchemaValidator` in
Java. A checked-in second copy is a file that *can* drift, and a drift here means
the two validators disagree about what a valid record is, silently, in opposite
directions. Making the copy a build step makes drift impossible rather than
unlikely.

**Rejected.** Checking schemas into `src/main/resources` — the drift above.
Loading them from the filesystem at runtime — the container would need
`docs/` shipped in the image.

**Consequence.** Schemas are edited in `docs/schemas/` only. The Gradle test
task also passes `stats.repositoryRoot` so `StatTypeFanOutTest` can read the
other modules' sources to prove no code special-cases an individual stat type
(STA-22).

---

### DL-085 — The production wire format is the contract; the SRS field names are withdrawn

**29 Aug 2026** · `ACCEPTED` · ADR-007 §1 · Supersedes the envelope naming in SRS v1.0

**Decision.** The device identifier is **`clsbox`** and the arrival clock is
**`ts.server`**, used identically on the wire, in the schema catalogue, in the Java
model, in the PostgreSQL column, and in API responses. **No rename layer anywhere.**

**Why.** Hasan supplied 15 real production records on 29 Aug 2026. Zero validated
against the schemas as they stood, and the reason was that every envelope name came
from SRS v1.0 and **no production record has ever used one of them**. Given that
production is the wire format, one vocabulary end to end is the only shape with no
translation step to get wrong.

**Rejected.** Renaming at the Input boundary and keeping the SRS vocabulary
downstream — a much smaller diff. Two specific reasons it lost. ADR-004 requires the
published message to be byte-identical to what was received and ADR-003's
per-partition duplicate suppression depends on that, so a rename breaks it. And a
field-rename layer sitting beside `tenant`/`maintenant` is exactly the place
`CLAUDE.md` §6 identifies as where a field is silently swapped — a failure that
passes every test written against sample data where the two are equal, which is all
of the sample data we have.

**Consequence.** 102 files changed. Affordable only because the three application
modules are still empty shells; after Phase 2 this would have been a data migration.
The *word* "gateway" survives in prose for the physical device — do not mechanically
rewrite it.

---

### DL-086 — `ts.converter` is accepted, ignored, and metered; ADR-004's rejection clause is withdrawn

**29 Aug 2026** · `ACCEPTED` · ADR-007 §2 · Amends ADR-004

**Decision.** A record carrying `ts.converter` is **valid**. The member is stored,
returned verbatim, and never read. Presence is metered as
`records_converter_ts_present{type}`. `ts.datastore` and `ts.expiresOn` are treated
the same way; `ts.expiresOn` is typed as a **string**, because production sends an
ISO-8601 instant there rather than epoch seconds.

**Why.** ADR-004 declared such a record invalid and the envelope schema enforced it
with `not: {required: [converter]}`. Production sends `ts.converter` on **100% of
records**. The rule rejected every record Heimdal sends.

**Why the original reasoning was wrong, precisely.** ADR-004 read `ts.converter` as
evidence that the sender believes in a normalisation step this system does not
perform. That reading was correct about the *sender* and wrong about the *remedy*.
Heimdal does run a converter — `status: ["conversion ok"]` says so on every record —
and the Stats App still performs no normalisation of its own. Rejecting the record
punished us for upstream behaviour we had already decided to ignore. **Accepting a
field is not the same as acting on it**, and conflating the two cost 100% of ingest.

**Rejected.** Keeping the rejection (rejects all traffic). Accepting without a metric
(loses the signal for when Heimdal stops sending it).

**Consequence.** ADR-004's core decision — no timestamp normalisation — is unchanged
and still binding. The fixture that asserted the old rule moved from `invalid/` to
`valid/` and is now the regression guard for the whole envelope shape.

---

### DL-087 — `ts.server` is the canonical arrival clock

**29 Aug 2026** · `ACCEPTED` · ADR-007 §3

**Decision.** Of `ts.server`, `ts.datastore` and `ts.converter` — all present and
identical in the sample — **`ts.server` is canonical**. Clock skew (ADR-003 D9,
Q14a) compares `ts.device` against `ts.server`, and the API exposes `ts.server` as
the processing time.

**Why.** It is Heimdal's own receipt clock, the direct analogue of the SRS's
`ts.processor`, and the only one of the three that still means anything once the
legacy Mongo datastore is retired.

**Rejected.** `ts.datastore` — the legacy store's write clock, describing a system
we are replacing. Deferring the choice — would have blocked the clock-skew check and
the API's processing-time field for no gain, since the three are identical anyway.

**Consequence.** Retention still keys on `ts.device`, unchanged (STA-23B). Note the
open question this leaves: `ts.device` equals `ts.server` in 14 of 15 records, so it
is not established that `ts.device` is genuinely device-assigned (ADR-007 Q15b).

---

### DL-088 — Two validation gates: envelope at Input, payload at the Writer

**29 Aug 2026** · `ACCEPTED` · ADR-007 §5 · Refines STA-03A

**Decision.** `stats-input` validates the **envelope only**; a failure is a 400 and
nothing is published. `stats-writer` validates the **envelope and the per-type
payload**; a failure there is a **dead-letter**. In Java,
`StatsSchemaValidator.validateEnvelope()` and `.validate()`.

**Why.** The costs are asymmetric and irreversible in one direction. Heimdal retries
only on a non-2xx, so a 400 issued for a payload disagreement destroys the record
permanently; a dead-letter is recoverable by redrive. Production sends `lorastats`
in three incompatible dialects today, so this is not hypothetical.

**Rejected.** Validating everything at Input (the previous behaviour) — turns every
dialect disagreement into unrecoverable loss. Validating nothing at Input — gives up
the 400 for genuinely malformed envelopes, which *is* the sender's bug to fix.

**Consequence.** **"Invalid" no longer implies "400".** Anything reading a fixture
from `invalid/` and asserting a 400 is now wrong. The fixture gate gained a
`$expect: "writer-dlq"` marker that asserts both halves — passes the envelope, fails
the payload — and `validate-fixtures.py` checks the envelope half explicitly.

---

### DL-089 — One `lorastats` dialect is encoded; the others dead-letter

**29 Aug 2026** · `ACCEPTED, ON AN ASSUMPTION` · ADR-007 §4 · Open question Q15a

**Decision.** The `lorastats` schema encodes **one** payload dialect — bare field
names, integer values. The two other dialects observed in production —
`lora_`-prefixed names, and bare names with string values — are **dead-lettered**.

**Why.** Three consecutive production records used three mutually incompatible
shapes, and **all three carried `version: 1`**, so nothing on the record
discriminates them. Bare/integer was chosen because it matches `omsstats`, which
shares a converter family.

**Rejected.** A permissive union accepting every spelling and both value types. It
would have made all 15 records pass — which is the problem. It silently ratifies a
contract nobody understands, and a union only ever grows. Dead-lettering makes the
disagreement visible and recoverable instead.

**Consequence.** This is **an assumption, not an answer**, and it dead-letters
roughly two thirds of `lorastats`. Two things follow. `version` must never be used
to select a parser. And ADR-002 §12 forbids a DLQ TTL, so at 115.2M records/day this
accumulates on the order of 15M messages/day with no expiry — which makes Q15a
urgent rather than routine, and makes DLQ depth alerting a Phase-2 blocker. **Do not
resolve a DLQ spike by widening the schema**; that is the option this entry
rejected.

---

### DL-090 — A field absent because the device is broken is optional, not invalid

**29 Aug 2026** · `ACCEPTED` · ADR-007 §6

**Decision.** Where production shows a field legitimately absent because of **device
state**, the requirement is corrected. Where production shows **two incompatible
encodings of the same thing**, one is encoded and the rest dead-letter (DL-089).
Under the first rule: `netstats` interfaces now require only `tx`/`rx`; `sysstats`
gains optional `temp`, `rtt`, `wan_if`, `rebootcause`, `timespan`; `ltestats`
numerics become strings and the field becomes `access_Technology`.

**Why.** `netstats` required `gw`/`ip`/`mask`/`dns` on every interface, derived from
one healthy sample. Production sends a `wwan` that never came up — `need_init:
"qmi"`, a `fail_count`, and no address at all — so the schema dead-lettered
**exactly the record operations most needs to see**. That is a wrong requirement,
not a dialect disagreement.

**Rejected.** Treating these as dialect cases and dead-lettering them — would
discard broken-device telemetry, which is the highest-value telemetry there is.
Blanket permissiveness — strictness still earns its place: an address that *is*
present must still be well-formed IPv4, and counters must still be digits.

**Consequence.** The `ltestats` corrections are transcription fixes, not widenings:
each of the two — integer-instead-of-string, and the lowercase `access_technology` —
alone dead-lettered 100% of `ltestats`. Before making a field required, ask what the
record looks like when the thing it describes is *failing*.

---

### DL-091 — The typed view must be lossless

**29 Aug 2026** · `ACCEPTED` · ADR-007 §2 · Enables ADR-004

**Decision.** `StatsEnvelope` and `Timestamps` model **every** production member,
including `version`, `status`, `ts.datastore`, `ts.converter` and `ts.expiresOn`,
and carry `@JsonInclude(NON_NULL)` so absent members do not serialise as `null`.
`Timestamps` also carries `@JsonIgnoreProperties(ignoreUnknown = true)`.

**Why.** ADR-004 requires byte-identical publication and ADR-003's per-partition
duplicate suppression depends on it. A typed view that silently dropped
`ts.expiresOn` would break that **without failing anything** — the classic silent
failure this project keeps finding. `NON_NULL` is what lets the pre-ADR-007 fixture
corpus still round-trip: without it every one of those records would gain four null
members and stop matching what was received.

**Rejected.** Ignoring the passthrough members and letting `additionalProperties`
carry them — works for the raw JSON path but breaks the moment anything round-trips
through the typed view. Typing `expiresOn` as epoch seconds — throws on every
production record, because it is an ISO-8601 string.

**Consequence.** Guarded by
`StatsEnvelopeTest.preservesTheWholeProductionTimestampBlockThroughTheTypedView` and
`.omitsAbsentOptionalMembersRatherThanSerialisingThemAsNull`. In PostgreSQL the
unread members land in a single nullable `passthrough jsonb` column (ADR-003) rather
than three columns, because nothing queries them.

---

### DL-092 — Shared Testcontainers support lives in `stats-common` Java test fixtures

**31 Aug 2026** · `ENGINEERING` · T-008, DL-014, DL-015, DL-025

**Decision.** Shared PostgreSQL 17 and RabbitMQ 4 Testcontainers support lives in
`stats-common`'s `java-test-fixtures` source set. It is reusable build/test code,
not production code.

**Why.** Persistence and broker tests need the same real services, image versions,
and Spring Boot service-connection wiring. Test fixtures reuse this infrastructure
without adding a sixth `stats-common/src/main` responsibility or allowing module
copies to drift.

**Rejected.** A separate Gradle project — it would look like a fourth deployable
module. Per-module support copies — image and lifecycle configuration would drift.

**Consequence.** Each support owns one static container per test JVM. This reuse is
within a Gradle worker/JVM only; it is not Docker-level reuse across workers or
separate JVMs, and `.withReuse(true)` remains prohibited.

---

## 9. Security and tenancy

### DL-060 — Isolation is on `tenant`, from a validated token claim, applied in the query

**Aug 2026** · `RATIFIED` · ADR-006, STA-35A

**Decision.** The isolation field is **`tenant`**, never `maintenant`. The
effective tenant comes **only** from a validated Keycloak claim. The predicate is
applied in the **database query** — every query, count, cursor and future
aggregate. Never filter across tenants in application memory.

**Why.** This is the highest-risk item in the project, and the reason is that
the wrong implementation *passes every test*. The supplied samples very often set
`tenant` and `maintenant` to the same value, so code filtering on `maintenant`
is green in CI and leaks in production. `maintenant` is the *managing* tenant and
has no security meaning.

In-memory filtering fails differently and just as badly: it means the database
returned another tenant's rows, so pagination counts, cursors and limits are all
computed over data the caller may not see — and one missed filter is a full leak.

**Rejected.** A `tenant` query parameter or header — never trusted; controllers
accept no override on any normal path. Filtering after fetch — above.

**Consequence.** Every fixture must have `tenant != maintenant`. A test using
the supplied samples as-is proves nothing about isolation. This needs an
integration test that is a **release gate**, not a unit test.

---

### DL-061 — A missing or empty `tenant` claim is 403, not 401 — and never "all tenants"

**Aug 2026, corrected 29 Aug 2026** · `RATIFIED` · ADR-006 §6

**Decision.** A token that authenticates but carries no usable `tenant` claim —
absent or empty — is rejected with **403**. `openapi.yaml` said 401; ADR-006
wins and the contract is corrected.

**Why.** The distinction is real: the caller *is* authenticated (401 would be
wrong), but is not authorised to see anything, because there is no tenant to
scope to. The load-bearing half is what an absent claim must **not** mean:
"unscoped" or "all tenants". Fail-closed here is by construction — there is no
default value to fall back to — rather than by a default that a later refactor
could change.

**Rejected.** 401 — misstates what happened and invites a token refresh that
will not fix it. Treating an absent claim as unscoped — a full cross-tenant
leak from a misconfigured client.

**Consequence.** The Keycloak `tenant` claim is **single-valued String on
purpose**. A multi-valued claim turns isolation into `tenant IN (...)`, and one
misconfigured user then reads several tenants with every test still green.

---

### DL-062 — A cross-tenant identifier returns 404, indistinguishable from not-found

**Aug 2026** · `RATIFIED` · ADR-006 §5, STA-35B

**Decision.** A `uid` belonging to another tenant returns **404**, identical in
every observable respect — status, body, headers, timing class — to a `uid` that
does not exist at all.

**Why.** A 403 for "exists but not yours" versus a 404 for "does not exist"
turns the API into an oracle: an attacker enumerates `uid`s and learns which
records exist in other tenants without ever reading one. The information leak is
the *difference*, so the responses must not differ — including timing class,
which is why this cannot be a late check on a fetched row.

**Rejected.** 403 for cross-tenant — the oracle. A distinct error body — the
same oracle in the payload.

**Consequence.** Cross-tenant lookup by `uid` is a **named release gate**. An
authenticated caller with no data in range gets an empty `items` array and
**200** — never 403 or 404, either of which would disclose the same thing from
the other direction.

---

### DL-063 — Input authentication is deferred by decision, behind a config switch with two guards

**18 Aug 2026** · `ANSWERED` (Q8a) · ADR-006, `stats-input/application.properties`

**Decision.** Build Input without Heimdal auth. Auth is a config-selected
filter: `stats.input.auth.mode=none|mtls|oauth2`, with **no default in code**,
a `WARN` on **every** startup when `none`, and a test asserting the application
**refuses to start** under the `prod` profile with `none`.

**Why.** ZC could not yet say whether inbound auth is mTLS or OAuth2 client
credentials. Blocking Input entirely would have stalled the reference vertical
slice on a question that only changes a filter implementation. Structuring it as
a mode makes the eventual answer a filter plus a config change, never a rewrite
of the endpoint.

The guards are the substance. **No default in code** means a deployment that
does not set it fails to start — `none` has to be typed by a person. The
`prod`-profile test is what actually stops it reaching production; the WARN is
what stops it becoming invisible in dev.

**Rejected.** A `none` default — the missing-configuration case would silently
become the insecure case. Blocking Phase 2 — stalls the slice on a filter.

**Consequence.** **Risk accepted and recorded:** until Q8a-2 closes, anything
that can reach the Input port can write statistics. Acceptable on a local dev
stack and nowhere else. Re-read this before any shared deployment.

---

### DL-064 — The Keycloak realm was built rather than waited for

**18 Aug 2026** · `PROPOSED` (Q8b; awaiting Q8b-2) · ADR-006, `docs/setup/keycloak-realm-statsapp.json`

**Decision.** Realm `statsapp`; clients `stats-api` (confidential resource
server, no flows), `stats-input` (service account), `stats-dev-cli` (public,
dev only); roles **`stats.readOnly`** and `stats.ingest`; audience `stats-api`;
tenant claim `tenant`, single-valued String. Committed as an importable realm.

**Why.** Q8b was answered by building it. A proposal that runs is a
configuration change away from correct if ZC disagrees; a proposal in a document
is a meeting away from anything. It unblocked the whole dev stack.

Two details are decisions in their own right. The **read role is
`stats.readOnly`** — not the bare `readOnly` placeholder; four spellings were in
circulation for a security control. And **`iss` is compared as a string, never
fetched**, so one issuer value stays correct whether a module runs on the host
or inside compose; containerised modules get `jwk-set-uri` pointed at the
compose hostname, which also disables OIDC discovery — which would otherwise
try to GET the issuer URL and fail.

**Rejected.** Waiting for ZC — indefinite block on a config change. Reusing a
Datahub Core realm — none was specified; if one exists it replaces this by
configuration.

**Consequence.** Values are provisional pending Q8b-2, but the shape is not.
Rejecting a token minted for another audience is what stops a neighbouring
service's token being replayed here.

---

## 10. API

### DL-070 — GET only; every other method is 405 with no side effect

**Aug 2026** · `RATIFIED` · ADR-006 §8, STA-32A

**Decision.** `stats-api` exposes GET only. POST, PUT, PATCH and DELETE return
405 and do nothing.

**Why.** Rule 1, and it is structural rather than stylistic: Module 2 is the
only component with database write access, so a write path in the API is not a
feature to add later — it is a violation of the architecture. Returning 405
explicitly (rather than 404, or nothing) makes the boundary observable and
testable.

**Rejected.** Leaving non-GET undefined — untestable, and framework defaults
vary.

**Consequence.** A test asserting 405 for **every** non-GET method on **every**
route is mandatory, not optional.

---

### DL-071 — `from` and `to` are required; there is no unbounded query

**21 Aug 2026** · `ENGINEERING` · `openapi.yaml`

**Decision.** `GET /v1/stats/{type}` requires `from` and `to`. `limit` is
1–1000, default 100.

**Why.** An unbounded scan over a table taking up to 115.2 million records a day
is not a query anyone recovers from — it is an outage with a 200 status. The
contract refuses to express one. Making it *required* rather than defaulted
means a caller cannot omit it by accident and receive a default window they did
not reason about.

**Rejected.** A default time window — hides the constraint until it surprises
someone. Unbounded with a server-side cap — the caller believes they got
everything.

**Consequence.** Every caller must pass a range. The maximum query window is
still provisional inside the contract.

---

### DL-072 — Keyset pagination, fixed `ts.device DESC, uid DESC`, cursor carries its filters

**21 Aug 2026** · `ENGINEERING` · `openapi.yaml`, ADR-003

**Decision.** Keyset (cursor) pagination on a **fixed, always-applied**
`ts.device DESC, uid DESC` sort. The cursor carries the filters it was issued
for; changing one mid-walk returns `invalid_cursor`.

**Why.** Three separate reasons converge. **Correctness:** without an explicit
deterministic sort with a unique tie-breaker, a paginated query can return the
same row on two pages or skip one entirely — and it does so silently.
**Performance:** `OFFSET` at page 10,000 makes the database count and discard
10,000 rows; keyset seeks directly. **Alignment:** this sort matches the
`(ts_device, uid)` primary key and the partition key, so the index serves it
without a sort step.

The cursor carrying its filters closes the last hole: a cursor reused with a
changed `from` would otherwise return a page from a *different query* and look
entirely valid.

**Rejected.** `OFFSET`/`LIMIT` — the skip/duplicate bug and the deep-page cost.
Sorting only when the caller asks — the failure appears exactly when they do
not. An opaque cursor without filter binding — silently wrong results.

**Consequence.** The sort is not negotiable per request. Follow `nextCursor`,
resending `from`, `to` and `clsbox` unchanged; `null` or absent means the last
page.

---

### DL-073 — RFC 9457 Problem Details for every error

**Aug 2026** · `RATIFIED` · `stats-common/error`, `openapi.yaml`

**Decision.** All errors use `application/problem+json` per RFC 9457, with a
shared `ErrorCode` catalogue in `stats-common` and `FieldError` for field-level
detail.

**Why.** A standard beats an invented envelope: consumers can parse it without
bespoke code, and it has a defined place for both a machine-readable `type` and
field-level detail. Putting the catalogue in `stats-common` is what makes Input
and the API produce the *same* shape — two hand-written error formats across two
modules is a contract with itself.

**Rejected.** A custom error envelope — no ecosystem support, and it drifts
between modules. Bare status codes — nothing for a client to branch on.

**Consequence.** Every new failure path adds a catalogue entry, which is
deliberate friction — it forces the question of what the caller should do about
it. Combined with DL-053, field errors carry pointers and codes, never values.

---

## 11. Operations

### DL-080 — Structured JSON to stdout; `uid` as correlation ID; no payloads on the success path

**18 Aug 2026** · `ENGINEERING` · decision **D1**, ADR-001

**Decision.** One JSON event per line to stdout, `traceId` and `uid` on every
record, **no payloads on the success path**, no tokens or secrets ever.

**Why.** stdout because the platform collects it; JSON because the alternative
is a regex per log line at query time. `uid` as correlation ID lets one record
be traced from the HTTP request through the queue to the row — which is the
question actually asked during an incident.

No payloads on the success path is a volume decision *and* a data-handling one:
at 115.2M records/day, logging payloads is both unaffordable and a copy of
production data in the log store.

**Rejected.** A generated request ID — real, but does not survive the queue hop
into the Writer. Logging payloads on success — cost and data exposure.

**Consequence.** Failure paths log enough context to act on. Every failure path
either returns a meaningful error, dead-letters, or logs at WARN/ERROR —
never swallowed.

---

### DL-081 — Secrets come from injected environment variables only

**18 Aug 2026** · `ENGINEERING` · ADR-001

**Decision.** Secrets are injected as environment variables by the platform —
never in `application.properties`, images, or Git. The dev stack uses literal
dev-only values that are obviously fake.

**Why.** Anything in `application.properties` is in the image and in Git
history, which is not a place a secret can be removed from. Making the dev
values *obviously* fake matters more than making them absent: a dev stack that
needs real credentials to run is a dev stack people copy production
credentials into.

**Rejected.** A secrets manager SDK — a dependency and a platform coupling for
something env vars already do; the platform can back them with a manager
transparently.

**Consequence.** `application.properties` uses `${VAR}` placeholders. Where a
value is genuinely undecided it is **commented out**, not guessed — an
unanswered question must fail closed (absent) rather than fail open (a
plausible-looking default).

---

### DL-082 — Sized for a 10× synchronised burst; alert on oldest-message age, never queue depth

**18 Aug 2026** · `ANSWERED` (Q13a) · ADR-003, `2B-ANSWERS-18AUG2026.md`

**Decision.** Assume synchronised 10-minute reporting boundaries until Heimdal
telemetry proves otherwise: design target **13,333/s** (10× average — 800,000
records per cycle landing in 60 s). Input sized for peak, Writer for drain
(~2,000/s per type), prefetch 500, `max_wal_size` sized for the burst.
**Alert on oldest-message age, never on depth.**

**Why.** Whether 160,000 gateways report on the boundary or spread across the
cycle is worth 10× on hardware, and it is unanswered (Q13b). Sizing for the
pessimistic case is recoverable; sizing for the optimistic case is an outage.

The alerting rule is the operationally important half: **queue depth spiking
each cycle is the buffer working.** An alert on depth would fire every ten
minutes forever and be muted within a week. Oldest-message age distinguishes a
buffer absorbing a burst from a consumer that has stopped — which is the thing
anyone actually wants to know.

**Rejected.** Sizing for the average — the boundary case is a 10× under-provision.
Depth alerts — guaranteed alert fatigue on healthy behaviour.

**Consequence.** **503 is the backpressure channel and is the mechanism
working, not an incident.** Q13b remains the single most valuable outstanding
question.

---

### DL-083 — Skipping a late-arriving record is deliberate data loss, and is alerted

**29 Aug 2026** · `PROPOSED` (engineering agreed; product signature owed) · ADR-003 **D5b**

**Decision.** A record arriving with `ts_device` older than the retention window
is **skipped**, metered as `records_expired_on_arrival`, and ACKed — plus an
alert when the counter is non-zero. **T-026 stays BLOCKED pending product
sign-off.**

**Why.** No live partition exists for such a record, so the alternative is
writing into a partition that does not exist. Writing a row that retention
removes minutes later is worse than not writing it: it burns write capacity and
makes the data appear to exist. This follows directly from retention keying on
`ts.device` — a gateway offline for weeks delivers records that are near expiry
on arrival, which is the **accepted business rule**, not a bug to fix by quietly
switching to `ts.server`.

**Why it is still open despite engineering agreement:** it is a deliberate
data-loss-by-design path. Engineering can say it is the only implementable
option; only product can accept the loss.

**Rejected.** Silently discarding — violates the no-silent-error rule.
Extending retention for late records — unbounded, and defeats detach-and-drop.
Switching retention to `ts.server` — reverses an answered business rule.

**Consequence.** This is the same mechanism behind DL-034's redrive trap: a
long-dead DLQ message becomes `expired_on_arrival` the moment it is redriven.

---

### DL-084 — RPO/RTO is a product decision, and engineering will not substitute for it

**29 Aug 2026** · `PROPOSED` · ADR-003 **D5a**

**Decision.** Engineering proposes **RPO ≤ 5 min** via continuous WAL archiving,
and **RTO 1 h** for a single partition, **24 h** for a full cluster restore.
This is input to a product and operations decision, not a replacement for one.
**T-051 stays blocked.**

**Why.** RPO is the tight one because lost data is unrecoverable: Heimdal
retries only on a non-2xx, so anything we returned 200 for and then lost is
**gone**. RTO is deliberately loose — this is 90-day telemetry, not financial
records, and at multi-terabyte scale a tight RTO may not be purchasable at any
sensible price.

**Why it was not closed with the other five on 29 August:** the other decisions
were engineering's to take. This one has a cost that engineering does not pay.

**Rejected.** Adopting the proposal unilaterally — sets a production commitment
nobody agreed to fund.

**Consequence.** Take it to product as a question about consequences:
*"if we lost the last 15 minutes, or took 8 hours to come back, what actually
breaks?"*

---

## 12. What is deliberately still open

Not decisions — the absence of them. Full status:
`claude/OPEN_QUESTIONS_STATUS_29AUG2026.md`.

| Ref | Open question | Owner | Blocks |
|---|---|---|---|
| **Q13b** | Do gateways spread reporting across the 10-minute cycle, or fire on the boundary? | Heimdal | Worth 10× on hardware. **The most valuable outstanding question.** |
| Q8a-2 | Heimdal inbound auth — mTLS or OAuth2; issuer, audience, identity, rotation | ZC / security | Nothing today (DL-063). **Must close before any shared deployment.** |
| Q8b-2 | Confirmation of the proposed Keycloak realm values | ZC | Nothing — a config change (DL-064) |
| Q13c | Agreement on the 2× replay rate cap | Heimdal | Our own rate limiting cannot substitute for Heimdal honouring it |
| Q4b | Does any gateway report IPv6 in `netstats`? | ZC | One `$def` widens; no code change |
| D5a | RPO/RTO targets | Product + operations | Production readiness, T-051 (DL-084) |
| D5b | Late-arrival policy sign-off | Product | T-026 (DL-083) |
| D-03 | Exact success code (200 vs 202), path confirmation, request size and timeout limits | Heimdal owner | Request-limit properties only |
| D-09 | ZC confirmation of `openapi.yaml` | ZC | **Phase 4** — the one thing genuinely gating the API |
| D-10 | Runtime platform and release mechanics | Platform | Packaging and release only; nothing before Phase 5 |
| D-12 | Data classification, IMSI masking, log retention, encryption at rest, audit | Security + compliance | Production logging and operations |

> **The standing rule:** do not invent an answer to any of these. If a task
> requires one, say which question blocks it and stop. A row marked
> `PENDING APPROVAL` is not permission to assume the recommended option.
