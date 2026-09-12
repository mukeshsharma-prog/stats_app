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

### DL-098 — The fixture gate asserts the assertion count, not just that they passed

**3 Sep 2026** · `ENGINEERING` · T-007, Q11a

**Decision.** `docs/schemas/validate-fixtures.py` fails when the number of assertions
it ran differs from `EXPECTED_ASSERTIONS` (87), in addition to failing on any
mismatch. Changing the number is a deliberate edit that belongs in the same commit as
the fixture change.

**Why.** The gate reported `{checked} assertions checked` and exited non-zero only on a
mismatch, so **deleting fixtures was invisible**: `checked` simply dropped and CI stayed
green. The fixture corpus is the oracle that caught four wrong payload schemas and every
wrong envelope field name on 29 Aug 2026 — a gate that cannot notice its own oracle
shrinking is not protecting it.

**Rejected.** An environment-variable override — an escape hatch on an assertion whose
whole value is that it cannot be skipped by accident. Counting fixture *files* — the
Java parity test expands to 80 schema cases against the gate's 87 because `$expect`
markers are not schema cases (ADR-003 D9); counting assertions keeps the two numbers
explainable rather than coincidentally equal.

**Consequence.** Adding or removing a fixture is a two-line change. That is the
intended friction.

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

### DL-097 — Message processors are selected by stat type, never by bean uniqueness

**3 Sep 2026** · `ENGINEERING` · T-019/T-020, rule 6, STA-14A, STA-22

**Decision.** `StatsMessageProcessor` declares `statType()`, and the writer resolves
the processor for a type by matching it. Two processors claiming one type is a startup
error, not a silent choice.

**Why.** The first implementation resolved "the" processor with
`ObjectProvider#getIfAvailable`, which works only while exactly one exists. Phase 3
registers five: the second one makes that call throw
`NoUniqueBeanDefinitionException`, so **adding `sysstats` would break `lorastats`** —
the additive-change rule (rule 6, STA-14A) inverted. The failure would also have
appeared during fan-out, far from the line that caused it.

**Rejected.** `@Primary` or `@Qualifier` on each processor — makes the wiring depend on
annotation bookkeeping that a new type can forget, with the same failure mode. A
`Map<StatType, StatsMessageProcessor>` bean — a second registry to keep in step with
the beans themselves.

**Consequence.** Adding a stat type adds a processor and touches nothing existing,
which is the point. The listener's `autoStartup` SpEL needs the wire literal as a
compile-time constant, so the descriptor carries one alongside the enum; a test asserts
the two never diverge, because a divergence would leave the listener permanently and
silently stopped.

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

### DL-093 — The lorastats index set is corrected to ADR-003 in V3

**3 Sep 2026** · `ENGINEERING` · T-011, ADR-003 "Required table and index direction", STA-21C, rule 3 · Corrects V2

**Decision.** `V3__lorastats_adr003_indexes.sql` **drops** `idx_lorastats_tsdevice_uid`
and creates the two indexes ADR-003 specifies:
`idx_lorastats_tenant_clsbox_device_uid (tenant, clsbox, ts_device DESC, uid DESC)`
and `idx_lorastats_tenant_uid (tenant, uid)`. Both are declared on the partitioned
parent so PostgreSQL propagates them to every partition.

**Why.** V2 shipped `(ts_device DESC, uid DESC)`, which the planner cannot use:
`PRIMARY KEY (ts_device, uid)` already provides an equivalent btree and PostgreSQL
answers a DESC/DESC ordering from it with a backward scan. Measured on PG17 against
these exact migrations, dropping it leaves `Index Only Scan Backward using
lorastats_pkey`, and the planner never selected it while it existed. It was a second
full btree maintained on every insert — 23.04M rows per type per day — buying nothing.

Meanwhile neither ADR-003 index existed. The dominant tenant-scoped page planned as a
pkey backward scan with a discard filter: **2,444 rows read and thrown away to return
50**, on one day of 200k rows and 50 tenants; a production partition holds ~23M. The
`uid`-only lookup planned as a pkey walk on a non-leading column: **989 buffer hits for
one row on one partition**, and `GET /v1/stats/{type}/{uid}` supplies no time bound, so
it touches all 90 live partitions. `tenant` leads both because the tenant predicate is
mandatory on every query (rule 3, STA-35).

**Rejected.** Editing V1/V2 — they are applied migrations and are immutable. Keeping
the V2 index alongside the new ones "just in case" — it is provably unreachable, and
an unused index at this write volume is a permanent cost with no upside.

**Consequence.** `CREATE INDEX` cannot be `CONCURRENTLY` on a partitioned parent. The
tables are empty at this migration so the lock is free, but applying the same change to
a populated cluster needs a maintenance window, or per-partition concurrent builds
followed by `ATTACH`. The migration test asserts the resulting index set and its
propagation to a child partition, because the previous test asserted the opposite —
`hasSize(2)`, no index containing `tenant`, and a count of zero for both ADR-003 index
names — which had made the wrong shape a green build.

---

### DL-094 — `passthrough` stores the wire's own shape and units

**3 Sep 2026** · `ENGINEERING` · T-058, ADR-004, ADR-007 §2, ADR-003 D7

**Decision.** The Writer populates `record_version` from the envelope's `version`, and
`passthrough` from `ts.datastore`, `ts.converter`, `ts.expiresOn` and `status`, shaped
as `{"ts": {...}, "status": [...]}`. Timestamps are stored as **epoch seconds** and
`expiresOn` as the **ISO-8601 string** production sends. Both columns stay NULL when
the record carries none of it.

**Why.** ADR-004's byte-identical publication only matters if the record survives to
storage. The first insert implementation mapped neither column, so every production
record — all of which carry `ts.converter`, and most a `version` — was stored having
lost them, and nothing failed: all five members are optional, so no schema test could
see it. Storing the wire's own units is what makes the stored value reconstruct the
received one; normalising to millis or an ISO instant here would quietly change what a
round trip returns.

**Rejected.** Three separate columns — ADR-003 already rejected that: nothing queries
them. Flattening to `{"datastore": ..., "converter": ...}` — loses which members were
inside `ts`, so the reconstruction is a guess.

**Consequence.** `schema_version` is still the literal `1`. ADR-003 D7 says it must
come from the schema that actually validated the record, and until T-057 puts the
payload gate in the Writer nothing has validated it. That is a known, commented gap
that T-057 closes — not a decision to leave it.

> **CLOSED 8 September 2026 — DL-126.** T-057 landed and the literal is gone:
> `ValidatedRecord` carries the label from `ValidationResult.schemaVersion()` through to
> the column, so it now names the contract that admitted the row rather than the one its
> author had in mind. The two answers agree only while there is one schema.

---

### DL-095 — D14b: unknown `data` members are preserved in `data_extra`, and metered

**3 Sep 2026** · `ENGINEERING` · D14b, DL-051, ADR-003 D2, T-021/T-022 · Closes the D14b row in `claude/CLAUDE.md` §7

**Decision.** Each typed stat-type table gains a nullable `data_extra jsonb`. The
Writer routes any `data` member the schema does not name into it, verbatim, and
increments `records_unmapped_data_field{type}`. Unindexed; never read by the
application.

**Why.** D14 (DL-051) requires unknown `data` members to be accepted **and preserved**,
and `lorastats-v1.schema.json` is `additionalProperties: true` — so such a member is
valid and must survive. ADR-003 D2 gives lorastats typed columns, which have nowhere to
put one, so the Writer dropped it silently. That is the same outcome D2 rejected in
writing when it chose a jsonb interface map for `netstats` over fixed `eth0_*` columns;
the argument does not stop applying because the type is different.

The metric matters as much as the column. A member appearing in `data_extra` means
Heimdal and this schema have diverged, which is a signal — but only if someone can see
it. Preserved-and-invisible is how the divergence gets found a year later.

**Rejected.** Dropping and documenting it — the option D2 already rejected. A full
`data jsonb` alongside the typed columns — reintroduces the ~375 GB/type key-name
overhead D2 exists to avoid, on every row, to serve the rare one. Rejecting the record
— an unknown member is explicitly valid under D14; dead-lettering it would break on
Heimdal's next additive change.

**Consequence.** `NULL` on the overwhelming majority of rows, where it costs one
null-bitmap bit — key names are stored only for records that actually carry one, so
this does not become D2's problem by another route. `ADD COLUMN` with no default is
catalog-only on PG11+, which is what makes it affordable to add to the other four
types later. Every new stat-type table must include the column; a type that omits it
silently reopens the drop.

---

### DL-096 — Partition pre-creation runs at startup, before the listeners, and fatally

**3 Sep 2026** · `ENGINEERING` · T-012, ADR-003 D1/D3, STA-23C

**Decision.** `PartitionBootstrap` is a `SmartLifecycle` at a phase below Spring AMQP's
listener containers. It ensures the deployment's partitions before anything consumes,
and a failure is **fatal** — the context does not start. The retention cron still runs
and keeps the horizon rolling. `stats.retention.pre-create-on-startup=false` disables
it for deployments where a separate job owns partition DDL.

**Why.** The cron (`0 30 3 * * *`) was the only trigger, so a module deployed at any
other hour came up against a parent table with no partitions and stayed that way for up
to a day. Every insert fails with `no partition of relation ... found for row`; the
T-020 manual-ACK contract correctly withholds the ACK, so **nothing is lost** — and
that is precisely what makes it dangerous. The consumer wedges once its prefetch window
is entirely unacknowledged, and it does **not** recover when the cron finally fires,
because those deliveries stay unacked until the process restarts. A whole stat type
stops ingesting, quietly, exactly the silent failure ADR-003 D1 demands be loud.

`SmartLifecycle` rather than `@PostConstruct` or `ApplicationReadyEvent`: the work has
to land after Flyway and before the listener containers start. `@PostConstruct` is not
ordered against the Flyway initializer without a bean-name `@DependsOn`;
`ApplicationReadyEvent` fires *after* `finishRefresh()`, which is where the containers
already started. Only the lifecycle phases make it a guarantee, and a test asserts the
phase against a real container instead of trusting a remembered constant.

**Rejected.** Starting anyway and letting the cron fix it — that is the wedge above.
Creating partitions lazily on the first insert failure — DDL on the consume path, racing
across replicas, in the one code path that is already failing. `pg_partman` — never a
dependency (ADR-003 D1).

**Consequence.** A database that is unreachable at boot is now a startup failure rather
than a running process that cannot work. For a module whose entire job needs the
database, crash-loop-with-a-clear-cause is the better failure, and readiness stays
down. A deployment configured for a stat type whose migration has not landed is named
explicitly at startup rather than surfacing as a confusing DDL error.

---

## 8. Validation, errors, and the record contract

### DL-050 — No transformation: the published message is byte-identical to what was received

**Aug 2026** · `RATIFIED` · ADR-004 · **amended 29 Aug 2026 by ADR-007 §2**

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

**Rejected.** *Normalising* on `ts.converter` — deriving, rewriting or
back-filling any timestamp from it. That is still explicitly out of scope and
reopening it requires a new approved compatibility ADR. This is the half of
ADR-004 that survives; it is not the same as rejecting the field.

**AMENDED 29 August 2026 — ADR-007 §2 withdrew the rejection clause.** This entry
previously read: *"A record carrying `ts.converter` is invalid — 400, nothing
published,"* implemented as `"not": {"required": ["converter"]}` on `ts`. The
29 Aug production sample carries `ts.converter` on **100% of records**, so that
rule rejected every record Heimdal sends. It is gone from
`envelope-v1.schema.json` and must not be reinstated.

**Consequence, as it now stands.** `ts.converter` is a **valid passthrough
member** — accepted, stored in the `passthrough` jsonb, returned verbatim, and
**never read**. Its presence is metered as `records_converter_ts_present{type}`
(T-059) so the day Heimdal stops sending it is visible rather than inferred.
Accepting the field is not the same as acting on it: no code anywhere may branch
on `ts.converter`, which is what keeps byte-identical publication — and therefore
duplicate suppression — intact. See DL-088 and ADR-007 §2.

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

> **SUPERSEDED 4 Sep 2026 by [DL-105](#dl-105--all-three-known-lorastats-dialects-are-accepted-and-normalised-to-one-row).**
> Q15a was answered: all three known dialects are accepted and normalised to one
> row. **Two findings below survive and are still binding** — `version` is never a
> parser discriminator, and a union that grows silently is a contract nobody
> understands (DL-105 rules 1 and 4). The rest is history.

**29 Aug 2026** · `SUPERSEDED` (was `ACCEPTED, ON AN ASSUMPTION`) · ADR-007 §4 · Q15a — answered 4 Sep 2026

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

**31 Aug 2026** · `ENGINEERING` · T-008, DL-014, DL-015, DL-025 · **SUPERSEDED 3 Sep 2026 by DL-099**

> **Superseded.** The placement changed to a plain `stats-testsupport` module; the
> *reasoning* below — one shared definition, never on a production classpath, no
> per-module copies to drift — is unchanged and is what DL-099 preserves. Only the
> Gradle mechanism moved, because IDE Gradle importers do not resolve the
> test-fixtures variant.

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

### DL-099 — Test infrastructure is a plain `stats-testsupport` module, not a test-fixtures variant

**3 Sep 2026** · `ENGINEERING` · T-008, ADR-001 · Supersedes the *placement* in DL-092

**Decision.** `PostgresSupport` and `RabbitSupport` move to a new
`stats-testsupport` module (`java-library`, no Spring Boot plugin, no image).
Consumers depend on it with `testImplementation(project(":stats-testsupport"))`.
`stats-common` no longer applies `java-test-fixtures`. Package names are unchanged,
so no import anywhere moved.

**Why.** The test-fixtures variant is correct Gradle and builds cleanly — but the
IDE could not resolve it. VS Code's Java support imports this repository through
**Buildship** (`org.eclipse.buildship.core.gradleclasspathcontainer`, confirmed in
the generated `.classpath`), and Buildship's Eclipse project model has no
representation for the `testFixtures` source set. The result was
`The import com.zenner.datahub.stats.testsupport cannot be resolved` in **eleven
files across `stats-common` and `stats-writer`** — every Docker-backed integration
test in the project, which is to say the tests covering tenant isolation, the ACK
boundary, retention, and the loss windows. Those are the files a reviewer most needs
to be able to navigate, and in the IDE they had no completion, no
go-to-definition, and no refactoring.

A build layout that only the command line understands is a real defect, not a
preference. `./gradlew build` passing is necessary and not sufficient.

**Rejected.** Telling developers to upgrade or re-configure the extension — it makes
a correct checkout depend on per-machine tooling state, and the next person hits it
again. Configuring the `eclipse` plugin to splice the fixtures output into each
consumer's classpath — carries the same fragility with more moving parts, and has to
be repeated for every new consumer. Copying the support classes per module — the
drift DL-092 correctly rejected.

**Consequence.** One more entry in `settings.gradle.kts` that is **not** a deployable
and not a fourth application module; the module's build file says so at the top, and
the dependency is `testImplementation`, so nothing it brings in can reach an
application's runtime classpath. `StatTypeFanOutTest` scans it like any other module,
so the no-stat-type-outside-`StatType` guard still covers everything. Q2 is untouched:
still three Docker images.

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
has no security meaning. *(Corrected by DL-139: it is the **original-owner** tenant —
the `uaa` pool a device is born in — which makes the two values differ routinely in
production. The security meaning is unchanged: none.)*

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

### DL-080 — Structured JSON to stdout; `uid` as correlation ID; full payloads only at `DEBUG`

**18 Aug 2026** · `ENGINEERING` · decision **D1**, ADR-001 · **AMENDED 4 Sep 2026 by DL-102**

**Decision.** One JSON event per line to stdout, `traceId` and `uid` on every
record, **full payloads only at `DEBUG`, which is off in production**, no tokens or
secrets ever at any level.

> **Amended 4 Sep 2026 (DL-102).** This entry originally said *"no payloads on the
> success path"*, which was true and incomplete: it left the failure path unstated and
> two other documents then read it as permission. The control is the **level**, not the
> path — `DEVELOPMENT.md` §7 is authoritative and always said so.

**Why.** stdout because the platform collects it; JSON because the alternative
is a regex per log line at query time. `uid` as correlation ID lets one record
be traced from the HTTP request through the queue to the row — which is the
question actually asked during an incident.

No payloads on the success path is a volume decision *and* a data-handling one:
at 115.2M records/day, logging payloads is both unaffordable and a copy of
production data in the log store.

**Rejected.** A generated request ID — real, but does not survive the queue hop
into the Writer. Logging payloads on success — cost and data exposure.

**Consequence.** Failure paths log enough context to act on — but that context is
fields, not the record. Every failure path either returns a meaningful error,
dead-letters, or logs at WARN/ERROR — never swallowed, and never with the payload
attached above `DEBUG`.

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

> **AMENDED 4 Sep 2026 by [DL-104](#dl-104--gateways-jitter-across-the-cycle-so-the-10-burst-becomes-headroom-rather-than-the-expected-case).**
> Q13b is answered: gateways **jitter** across the cycle. 13,333/s is now
> **configured headroom, not the expected case** — expected steady state is the
> average, ~1,333/s — and hardware is not cut until the `ts.server mod 600`
> histogram confirms it (T-050). One reading inverts: a cyclic depth spike was
> *the buffer working*; under jitter it is **evidence the answer is wrong.**

**18 Aug 2026** · `ANSWERED` (Q13a; amended by Q13b, 4 Sep 2026) · ADR-003, `2B-ANSWERS-18AUG2026.md`

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

> **RATIFIED 4 Sep 2026 — see [DL-112](#dl-112--late-arrival-skip-meter-ack-alert--signed);
> BUILT 10 Sep 2026 — see DL-133.**
> Signed by Naman Vashishtha. **T-026 is unblocked.** DL-106 (Q15b: `ts.device` is
> genuine device time) is what made the path real rather than hypothetical.

**29 Aug 2026** · `RATIFIED 4 Sep 2026` (was `PROPOSED`) · ADR-003 **D5b**

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

> **RATIFIED 4 Sep 2026 — see [DL-111](#dl-111--rpo--5-min-rto-1-h-per-partition-and-24-h-full-cluster--signed).**
> The proposal below is signed as written by Naman Vashishtha. **T-051 is
> unblocked**, and archiver lag joins T-050's alert set — a stalled archiver
> silently converts RPO from 5 minutes to *"however long ago it stopped"*.

**29 Aug 2026** · `RATIFIED 4 Sep 2026` (was `PROPOSED`) · ADR-003 **D5a**

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

### DL-100 — A counter nobody can scrape is not a metric, and config that describes an unbuilt gate must say so

**3 Sep 2026** · `ENGINEERING` · T-022, T-023, T-058, T-027, T-006, T-013, T-057 · DN-18

**Decision.** Three things, all of the same shape — a claim in the repository that
nothing made true:

1. **`micrometer-registry-prometheus` is a runtime dependency of all three application
   modules**, and `stats-writer` gains `spring-boot-starter-web`. `ManagementSurfaceTest`
   scrapes `/actuator/prometheus` and asserts every Writer counter is present.
2. **`STATS_DLQ_ALERT_DEPTH_LORASTATS` is documented in `.env.example`**, with why
   lorastats cannot share the `alert-depth.default=0` the other four types use.
3. **`stats.*.validation.scope` is renamed to `validation.intended-scope`** in both
   modules, with a `NOT YET ENFORCED` banner naming the task that will build the gate
   (T-013 for Input, T-057 for the Writer). The ADR-007 §5 reasoning stays with the key.

**Why.** All three read as statements of fact and were statements of intent.

`spring-boot-starter-actuator` brings `micrometer-core`, which registers a `Counter`
and cannot export one; with no registry implementation Spring Boot falls back to
`SimpleMeterRegistry` and the metric never leaves the JVM. `stats-writer` had no
servlet container at all, so `server.port=8082`, `management.endpoints.web.exposure.include`
and `management.endpoint.health.probes.enabled` were all inert and the readiness
healthcheck already committed in `docker/docker-compose.yml` could never pass. Nothing
failed: the module started, writes committed, and the unit tests asserting
`counter.count()` stayed green. That is the point — **`records_duplicate_suppressed` is
the only thing standing between `ON CONFLICT DO NOTHING` and silence** (STA-27A,
ADR-005, DL-095), and un-exported it is exactly the silent loss it was built to reveal.

`stats.writer.validation.scope=envelope-and-payload` was worse than absent. No code
reads it and `StatsSchemaValidator` is wired into neither module, so the key asserted
that the Writer's payload gate was live when every payload still reaches the INSERT
unvalidated. Someone acting on it in the other direction — moving `validate()` to Input
— turns a redrivable dead-letter into a **400 Heimdal will never retry**, which is
ADR-007 §5's whole subject and `FLOW.md` §8 window 12.

**Rejected.** *Deleting the validation-scope keys* — the ADR-007 reasoning attached to
them is worth more than the tidiness, and these properties files are deliberately a
design ledger (`stats.exchange.name` and `stats.amqp.retry.*` are unread too). *Building
the gates now* — that is T-013 and T-057, and pulling them into a cleanup would ship the
Input gate without the publisher-confirm path it belongs to. *A separate management
port on the Writer* — it would diverge from the other two modules and break the
committed compose healthcheck for no gain, since no controller exists to isolate it
from and a test now asserts none appears.

**Consequence.** `stats-writer` runs a servlet container that serves actuator and
nothing else; `theWebServerCarriesNoApplicationEndpoint` fails if a controller ever
lands there, which keeps rule 1 and rule 2 intact. T-027 stays PARTIAL — this made the
three existing counters observable, it did not add the missing ones. T-006 stays
PARTIAL: probes are now reachable, but readiness is still Boot's default composite
rather than the per-module dependency set D13 asks for.

**Note for anyone writing the next metrics test.** Spring Boot disables metrics export
under `@SpringBootTest` — its context customizer injects
`management.defaults.metrics.export.enabled=false`. A scrape test therefore needs
`@AutoConfigureObservability`, or it 404s against a perfectly correct application and
"proves" the opposite of what it was written for.

---

---

### DL-101 — Input's gate is the envelope, and the config that says so now refuses to start if changed

**4 Sep 2026** · `ENGINEERING` · T-013, T-059, T-027 · ADR-007 §5, ADR-003 D9, DL-088

**Decision.** `stats-input` gets its POST endpoint, and with it four things:

1. **`EnvelopeGate` calls `StatsSchemaValidator.validateEnvelope()` and nothing else.**
   A failure is a 400 with RFC 9457 Problem Details, and nothing is published (rule 7).
   The per-type payload is not looked at.
2. **`InputValidationProperties` binds `stats.input.validation.intended-scope` and the
   application refuses to start on any value but `envelope`.** DL-100 renamed the key
   and gave it a `NOT YET ENFORCED` banner; this is the enforcement it named.
3. **The clock-skew check (Q14a, ADR-003 D9) is one-sided.** `ts.device` ahead of
   `ts.server` beyond `stats.input.validation.max-clock-skew` is a 400 with the field
   code `clock_skew_exceeded` at `/ts/device`, metered as
   `records_clock_skew_rejected{type}`. A `ts.device` in the **past** is accepted, however
   old.
4. **`stats.input.endpoint.path` and `.success-status` are externalised**, defaulting to
   the contract's draft `/stats` and `200`, with the status constrained in code to the
   two values `heimdal-input-contract-v1.md` §2 admits.

**Why the payload is not validated here.** This is the decision the whole module is
shaped around, and it is counter-intuitive enough to be worth restating: **Heimdal
retries only on a non-2xx.** A 400 issued because `data` disagreed with a per-type
schema is therefore permanent data loss — the sender is told the record is bad and
never sends it again. The same disagreement caught at the Writer is a dead-letter,
which is redrivable once the schema is fixed. Production sends `lorastats` in three
mutually incompatible dialects today, all carrying `version: 1`, so roughly two-thirds
of `lorastats` depends on this asymmetry being right.

`EnvelopeGateTest` asserts it directly: all four `invalid/lorastats-*.json` fixtures —
including the two byte-faithful production records — are **accepted** by Input. If that
assertion ever flips to expecting a 400, the module has started destroying records.

**Why the scope property is a startup guard rather than a comment.** A property that
only documents intent is one edit away from being wrong, and the edit that widens this
gate looks like a tightening — "validate properly at the boundary" is the instinct of
every reviewer who has not read ADR-007 §5. Failing at startup puts the argument in
front of whoever makes that change, in a class that explains why.

**Why the skew rule is one-sided.** Rejecting a future `ts.device` is forced: partition
pre-creation is finite, so a gateway claiming 2038 either demands unbounded partitions
or fails deep in the Writer where the caller can no longer be told. Rejecting an *old*
`ts.device` would be the opposite of the design — retention keys on `ts.device`
(STA-23B) precisely so an offline gateway's readings expire on when they were measured.
A record from a gateway that was down for a month is the record operations most needs;
what happens to it is the Writer's late-arrival policy (D5b), not a 400.

**Not in this change.** Publishing. `StatsPublisher` is a port with a deliberate
stand-in that always answers `NOT_CONFIRMED`, so a record that passes the gate is
answered **503** and Heimdal retries. That is the honest state until T-014: rule 8
allows a 2xx only after a real publisher confirm, and a module that publishes nothing
must never claim success. The stand-in is `@ConditionalOnMissingBean`, so T-014 is
additive.

**Metrics added** (T-027, T-059): `records_accepted`, `records_validation_failed`,
`records_clock_skew_rejected`, `records_converter_ts_present`, `records_publish_failed`
— all labelled by type, with `unknown` for records whose type could not be read. An
absent series and a zero series look identical on a dashboard, and a flood of
unparseable bodies is exactly when the series has to exist.

### DL-102 — Payload logging is gated by LEVEL, not by path; and D-12 gets its input document

**4 Sep 2026** · `ENGINEERING` · D-12, D1, DL-080, ADR-001, ADR-002 §12 · T-034

**Decision.** Two things.

**1. `DEVELOPMENT.md` §7 is the authoritative logging rule and the other documents are
reconciled to it.** The rule is level-gated: *full payloads only at `DEBUG`, which is off
in production; never at `INFO` or above on any path, success or failure; tokens and secrets
never, at any level; payload logging stays configurable.* DL-080's title and decision are
amended, `IMPLEMENTATION_RULES.md` §8 is rewritten, and D-12's *decided* half in
`CODE_START_CHECKLIST.md` now states the level gate instead of the success path alone.

**2. `docs/decisions/DATA-SENSITIVITY-INVENTORY.md` is written** — every field in all six
schemas, the six sinks it can reach, and what class of identifier it is. It is an input
document, not a classification; the five questions in its §6 are still security and
compliance's to answer.

**Why.** Three documents disagreed, and two of the three read as blanket permission:

| Source | Said |
|---|---|
| `CODE_START_CHECKLIST.md` D-12, DL-080 | "no payloads on the success path" — true, but silent about failure |
| `IMPLEMENTATION_RULES.md` §8 | "V1 permits payload logging to the Java logger/Docker logs" — **no level, no path** |
| `DEVELOPMENT.md` §7 | "`DEBUG` — full payloads. Off in production"; "never … full payloads at `INFO` or above" |

Only `DEVELOPMENT.md` stated a rule an implementer could follow. This is the same failure
as the 401/403 contract defect: several documents agreeing on an ambiguity, which
consistency then disguises. It mattered because a logged `ltestats` payload **is** an IMSI
in the Docker log store, so an unqualified permission was quietly pre-deciding the masking
half of D-12 by default.

**What the inventory found that changes D-12's shape.** D-12 names one sensitive field.
`ltestats` carries **three classes on the same record**: subscriber (`imsi`), location
(`lac`/`cid`, refined by the signal fields) and carrier (`plmn`/`operator`) — and every
record of every type also carries `clsbox`, a stable device id. Masking IMSI alone leaves a
per-device location trail keyed on `clsbox`, sampled every ten minutes for ninety days.
Separately, `uid`, `tenant` and `clsbox` are on **every log line on the success path** by
design at 115.2M records/day, under a retention policy that does not exist — the
largest-volume item in D-12 and not what the row was about.

**The deadline is T-034, not a date.** `ltestats` is unbuilt, so no IMSI, cell id or IP
exists in PostgreSQL, in a log or in a queue today; every item is still a free config
choice. After T-034, ADR-002 §12's *no TTL, ever* — correct, and what stops silent
loss — is exactly what makes an unmasked identifier permanent in the DLQ, and the answer
then costs a migration, an API contract change, and a purge that ADR-002 §12 forbids.

**Rejected.** *Reconciling toward "no payloads, ever"* — stricter than the business
decision recorded in `DEVELOPMENT.md` §7 (D2, NFR-03), and it would delete the one tool
that makes a malformed record diagnosable. *Filing the contradiction as a question for
security* — it is a documentation defect between three engineering documents; security
cannot resolve which of our own files is right. *Deciding the masking policy here* — the
irreversible half (P4, P5 in the inventory) genuinely needs a signature.

**Consequence.** Two exposure paths remain open and are named in the inventory §5 rather
than fixed here: a PostgreSQL `DETAIL:` line can carry payload values into an `ERROR` log,
and `data_extra` accepts identifiers no field-level rule anticipates. **D-12 still has no
`T-nnn` task**, so nothing in the tracker will surface it — the Q14a defect, repeated.

---

### DL-103 — Readiness probes each module's real dependencies, because Boot's default fails open

**4 Sep 2026** · `ENGINEERING` · D13, D-10, T-006, ADR-002 §11, STA-32 · DL-100

**Decision.** All three modules configure both probe groups explicitly:

| Module | Readiness | Why |
|---|---|---|
| `stats-input` | `readinessState, rabbit` | The broker and nothing else. Input holds no database dependency at all (rule 2, STA-32), so a `db` indicator here would be evidence the rule had been broken, not a more thorough probe |
| `stats-writer` | `readinessState, db, rabbit` | It consumes from one and commits to the other and cannot work with either gone |
| `stats-api` | `readinessState, db` | **Never `rabbit`** — Q3a and ADR-002 §11 give it no broker connection, so the absence is part of that decision |

Liveness is written out as `livenessState` in all three even though that is already
Boot's default, so the pair is visible and neither silently drifts.

**Why.** D-10's *decided* half has claimed since 29 August that readiness "probes only
each module's real dependencies" (D13). It did not. **No module set
`management.endpoint.health.group.readiness.include`, so Boot's default readiness group
is `readinessState` alone** — the endpoint reported UP with PostgreSQL and RabbitMQ both
down. All three `docker/docker-compose.yml` health-checks target exactly that URL.

It fails **open**, which is the direction that costs something: an orchestrator routes
traffic to a Writer that cannot write, and a rolling deploy replaces healthy replicas
with ones reporting ready before they can do any work. Combined with DL-100 — where
`stats-writer` had no servlet container at all, so its healthcheck could never even be
answered — the Writer's probe went from *unanswerable* to *answering UP unconditionally*
without ever being right.

**Rejected.** *Putting dependencies in the liveness group* — liveness answers "should
this process be restarted", and a database blip would then kill every replica while each
replacement finds the same database still down. That is a restart loop that cannot fix
itself. *A Keycloak indicator for `stats-api`* — Boot ships none for an OAuth2 issuer and
the resource server itself lands with T-038; the probe says what it actually checks
rather than implying one it does not. *Enabling `show-details` in production* — an
unauthenticated probe should not enumerate dependencies; the tests set
`show-components` themselves, which changes rendering and never membership.

**Consequence.** T-006 moves from PARTIAL to substantially closed: the groups are
per-module and tested. What remains under it is the Keycloak probe (with T-038) and the
`stats-input` structured-log field set, which is a different half of that task.
`ManagementSurfaceTest` asserts the Writer's readiness names `db` and `rabbit` and that
liveness names neither; `ReadinessGroupTest` in Input and the API assert the shipped
configuration, since the defect being guarded is a configuration one and deleting a
single line reintroduces it.

---

### DL-104 — Gateways jitter across the cycle, so the 10× burst becomes headroom rather than the expected case

**4 Sep 2026** · `ANSWERED` (Q13b) · DL-082, ADR-003, Q13a · T-048, T-050

**Decision.** ZC confirms gateways **spread** their reporting across the 10-minute
cycle rather than firing on the boundary. Three consequences, and one deliberate
non-consequence:

1. **Expected steady-state ingest is the average — roughly 1,333/s across five
   types**, not 13,333/s. Q5a's 115.2M records/day is unchanged; only its
   distribution across the cycle is.
2. **DL-082's 13,333/s survives as configured headroom, not as the provisioning
   baseline.** Prefetch, consumer concurrency, replica count and `max_wal_size`
   stay externalised, so reaching the old figure is a property change and not a
   redeploy.
3. **T-050 gains a day-one verification**: a histogram of `ts.server mod 600` over
   the first 24 hours of real ingest. Hardware is not cut until that histogram
   exists.
4. **Alert on oldest-message age, never on queue depth — unchanged.** It was never
   a consequence of the burst shape.

**Why the histogram still matters after an answer.** This is the question the 29 Aug
status note recorded as *not actually askable-only*: it is answerable from our own
first day of ingest, and a measurement outranks a statement about firmware
behaviour across 160,000 devices of several generations. Cutting capacity on the
statement and discovering the boundary case in production is a 10× under-provision
during the exact minute it matters.

**What inverts.** Under DL-082 a cyclic queue-depth spike was *the buffer working as
designed*. Under jitter it is **evidence this answer is wrong** — the same graph,
the opposite meaning. That reading belongs in T-050's runbook note, because
nothing in the metric itself says which regime you are in.

**Rejected.** *Cutting the sizing immediately* — the saving is real but unverified,
and it is recoverable in one direction only. *Leaving DL-082 unamended* — it would
have left the repository asserting a 10× synchronised burst as fact after ZC said
otherwise, which is how the SRS field names survived for months.

**Consequence.** Q13b closes. It was the highest-value outstanding question on the
sheet; what it bought is permission to provision against the average once measured,
not an immediate change to any code. DL-082's decision text is amended in place.

---

### DL-105 — All three known `lorastats` dialects are accepted and normalised to one row

**4 Sep 2026** · `ANSWERED` (Q15a) + `RATIFIED` · ADR-007 §4 (amended), DL-089 (superseded) · T-063, T-057, T-060

**Decision.** Hasan's answer to Q15a is *"make it work for all the known
dialects."* **DL-089 is superseded.** The `lorastats` payload schema accepts all
three dialects observed in production:

| Dialect | Field names | Value types | Example |
|---|---|---|---|
| **A** | bare | integer | `"rx_nr_pkts": 176` |
| **B** | bare | **string** | `"rx_nr_pkts": "176"` |
| **C** | **`lora_`-prefixed** | integer | `"lora_rx_nr_pkts": 176` |

Four rules bound the widening, and each one is load-bearing:

1. **Detection is structural, never `version`.** All three carry `version: 1`.
   DL-089's one durable finding survives intact: anything that branches on
   `version` to select a parser is wrong.
2. **All three normalise to the same typed columns.** One stored shape, one API
   shape. The dialect a record arrived in is **not** a column and is not
   queryable — it is a metric label, `records_lorastats_dialect{dialect}`, which
   gives the population view without pushing a three-way branch onto every
   consumer.
3. **Coercion is explicit and range-checked.** Dialect B's strings are parsed
   per-field and range-checked against the signed `BIGINT` maximum *before*
   conversion — the rule Q4a already set for `netstats` counters. A value that does
   not parse, or that overflows, **dead-letters**. It is never silently zeroed,
   truncated or defaulted.
4. **An unknown fourth dialect still dead-letters.** "All the known dialects" is
   exactly three. The schema is a **closed enumeration of three**, not an open
   union: a new spelling surfaces in the DLQ rather than being absorbed.

**Why.** Dead-lettering two thirds of `lorastats` was only defensible while the
dialects were unexplained. Q15a explains them — three shapes are in the field
simultaneously, all carrying `version: 1`, and no retirement timeline has been
given. At that point the DLQ was not rejecting bad data; it was rejecting data,
roughly 15M messages a day, into a queue ADR-002 §12 forbids ever expiring.

**What changed in the reasoning, precisely.** DL-089 rejected a permissive union
because *"it would have made all 15 records pass, and that is exactly the
problem — it silently ratifies a contract nobody understands."* That was correct
while the contract was unknown. Accepting a contract we now understand, with a
closed enumeration and a named owner for each dialect, is a different act.
Rules 1 and 4 exist so that the *unbounded* half of DL-089's objection — that a
union only ever grows — still cannot happen.

**Rejected.** *Storing the dialect distinctly* — costs a discriminator column and
gives the API three shapes for one stat type, for a distinction with no business
meaning; rule 2's metric label answers the only real question ("how much of the
fleet is on C?") for free. *Coercing dialect B with a permissive parse that falls
back to zero* — silent corruption, and the exact shape of the `int4`-wrapping trap
(trap 11). *Opening the schema to any spelling* — that is the union DL-089 rejected
and rule 4 still rejects. *Branching on `version`* — three dialects, one version.

**Consequence.** **T-063** is new and sits in Phase 2 ahead of the lorastats writer
path: widen the schema to the three-dialect enumeration, add fixtures moving
`invalid/lorastats-dialect-prefixed.json` and
`invalid/lorastats-dialect-string-values.json` from `invalid/` to `valid/`, and
implement structural detection with range-checked coercion. **Trap 20 becomes more
load-bearing, not less** — we now branch on structure, and `version` sits there
looking exactly like the discriminator it is not. **Trap 22 largely dissolves**: the
lorastats DLQ stops carrying ~15M routine messages/day, so T-060's per-type
thresholds are still wanted but for ordinary reasons rather than to stop one type
drowning the signal. ADR-007 §4 is amended and its Consequences section's DLQ
volume warning no longer applies.

**Residual.** Q15a's second half — *are the other dialects being retired?* — is
still unanswered. It blocks nothing. It only determines whether we can ever delete
a parser, and rule 2's metric label is what will eventually show a dialect going to
zero in the field.

> **BUILT 8 September 2026 — see DL-127.** Everything above is the decision; DL-127 is
> what the code does and what it cost. Two implementation facts belong with this entry:
> **dialect C is not "everything prefixed"** — the production record mixes fifteen
> `lora_` counters with a bare `fsk_received` and a bare `timespan` — and normalisation
> has to run **before** the column mapping, or `data_extra` swallows the prefixed
> counters and leaves every typed column null.

---

### DL-106 — `ts.device` is genuine device time, so every consequence of retention-on-device is real

**4 Sep 2026** · `ANSWERED` (Q15b) · STA-23B, ADR-003, ADR-003 D9 · D5b/DL-112, T-026, T-052

**Decision.** ZC confirms `ts.device` is assigned by the device, and the record is
sent to Heimdal as soon as the device records it. Retention keying on `ts.device`
(STA-23B) is therefore keying on **measurement time**, not on arrival time.

**Why the sample looked like the opposite.** `ts.device` equalled `ts.server`
exactly in 14 of 15 production records, and differed by 1 second in the fifteenth —
which reads as an arrival stamp. It is not. All 15 came from healthy, online
gateways sending immediately, so the two clocks coincide by construction. **The
sample shows the healthy path; it is not evidence about the absence of the
unhealthy one.** Fifteen records from one moment cannot demonstrate that no gateway
is ever offline.

**Consequence.** Everything that rested on this being real is real, and none of it
is theoretical:

* **The offline-backfill trap (trap 5).** A gateway offline for three weeks
  delivers records already 21 days into their 90-day window.
* **The late-arrival policy (D5b, DL-112, T-026).** A gateway offline longer than
  the retention window delivers records with no live partition at all.
* **The redrive-age trap (trap 18).** A DLQ message redriven after ~90 days is a
  no-op that increments a counter, not a recovery.
* **The clock-skew gate (ADR-003 D9).** A device clock can genuinely run ahead of
  Heimdal's, which is what the skew tolerance is for.

Boundary tests either side of the retention window, keyed on `ts_device`, are
required rather than defensive. Q15b closes.

---

### DL-107 — `_id` is a Mongo export artifact and never appears on the wire

**4 Sep 2026** · `ANSWERED` (Q15c) · D14, DL-095, ADR-007 · Fixtures

**Decision.** ZC confirms Heimdal stores records in its own MongoDB and that
whoever produced the 29 Aug sample dumped straight out of that database. `_id` is
an artifact of the export and is **never POSTed**. No `_id` handling is built.

**Consequence.** **D14 is unchanged and remains the safety net** — this answer
removes a reason to write code, not the policy that made the code unnecessary. An
unknown *envelope* member is still rejected at the Input gate; an unknown *`data`*
member is still preserved into `data_extra` (DL-095). Any fixture derived from the
29 Aug dump must have `_id` stripped, or it tests the exporter rather than the wire.

**The thing to carry.** Heimdal's own store being MongoDB is **not** permission to
reintroduce Mongo anywhere in this repository. It is upstream's storage. The rule
in `CLAUDE.md` §3 stands unchanged: no drivers, no `MongoRepository`, no TTL
indexes, no "collection"/"document" vocabulary. Q15c closes.

---

### DL-108 — A non-OK `status` is metered and stored, never dead-lettered

**4 Sep 2026** · `ANSWERED` (Q15d) + `ENGINEERING` · ADR-007 §1, D14, DL-102 · T-064, T-050

**Decision.** ZC confirms `status` can report a **failed** conversion, not only
`["conversion ok"]`. The Writer therefore:

* **Stores the record.** A non-OK status is upstream's verdict on its own
  conversion, not a failure of our validation.
* **Meters it** as `records_upstream_status_not_ok{type}`, incremented whenever
  `status` is anything other than exactly `["conversion ok"]`. The comparison is
  **value-agnostic on purpose**.
* **Logs at `WARN`** with `uid`, `type`, `clsbox` and the `status` array — and
  never the payload, under DL-102's level gate.
* **Keeps `status` a passthrough member.** Stored and returned verbatim; nothing
  branches on it.

**Why value-agnostic.** We do not have the vocabulary of failure strings. A check
that recognises only the values we happen to know about goes **quiet** on the ones
we do not — which is the failure mode the whole "nothing is ever silently dropped"
rule exists to prevent. Comparing against the one known-good value inverts that:
anything unrecognised is counted, by definition.

**Rejected.** *Dead-lettering a non-OK status* — the record is valid, was correctly
delivered, and a redrive produces the identical result forever; that is a DLQ entry
that can never be drained, in a DLQ with no TTL. *Waiting for the vocabulary before
metering* — the counter does not need it, and the counter is what will name the
actual values. *A metric label carrying the status value* — an unbounded label set
sourced from an upstream vocabulary we do not control is a Prometheus cardinality
bomb.

**Consequence.** **T-064** is new. Q15d closes as asked: we now have a signal where
previously we would have stored records upstream already knew were bad, with
nothing on our side saying so. T-050's alert set gains the counter.

**Residual.** The vocabulary of non-OK `status` values is unknown. It blocks
nothing — the counter is value-agnostic — and it is worth asking on the next round
with Hasan only to decide whether any *specific* failure deserves different
handling. The counter will have enumerated the real values by then in any case.

---

### DL-109 — Heimdal agrees the 2× replay cap, and it is still not something we can enforce

**4 Sep 2026** · `ANSWERED` (Q13c) · ADR-005, Q13a, ADR-002 §12 · T-049, T-052, T-018

**Decision.** ZC agrees to cap post-outage replay at **2× nominal (2,667/s)**.
**T-049 (replay drill) is unblocked** and rehearses that figure: a 6-hour outage is
28.8M messages and roughly 3 hours of replay at the cap, holding total ingest near
4,000/s.

**Why not one defence changes.** An agreement is a commitment, not a control.
Nothing in the Stats App can make Heimdal honour it, and the damaging failure mode
is not the breach — it is tuning our own backpressure away as redundant because a
commitment exists. So all of it stays: **503 is the backpressure channel**, prefetch
stays bounded, and T-018's rate limit remains the only control we actually own. A
503 during replay is the mechanism working, not an incident.

**Consequence.** T-049 asserts that `records_duplicate_suppressed` spikes and that
**no alert fires on it** — replayed records are duplicates by definition and
`ON CONFLICT DO NOTHING` absorbs them (ADR-005). T-052's redrive runbook states the
2× figure as an agreed number **and states plainly that it is unenforced on our
side**, so that a future operator does not read it as a guarantee. Q13c closes.

---

### DL-110 — No IPv6 today; the schema stays strict and the metered rejection is the reopening trigger

**4 Sep 2026** · `ANSWERED` (Q4b, provisionally) · Q4a, ADR-007 §6, `netstats-v1.schema.json` · T-060, T-050

**Decision.** ZC's answer to Q4b is *"for now consider no."* `netstats` addresses
stay **IPv4-only and strict**. The answer is explicitly provisional — *"for now"* is
not *"never"* — so this closure ships with its own reopening mechanism:

* An address failing the IPv4 `$def` produces a **distinct, metered per-field
  code**, not a generic type error, so an IPv6 gateway appears as a **named
  validation-failure spike** rather than as silence.
* **A non-zero count on that code reopens Q4b.** That is the trigger. There is no
  calendar review, because a calendar review is a thing people stop doing.
* `invalid/netstats-ipv6.json` stays in `invalid/`. If Q4b reopens, one `$def`
  widens and that fixture moves to `valid/` — which was always the entire change.

**Why a provisional answer needs a detector.** Closing "for now consider no" with a
plain schema rule and nothing else reproduces trap 16 exactly: an undecided
question with a default answer baked into a passing test, invisible because the
test is green. The metered code is what makes the assumption observable when
production disagrees with it. Q4b closes.

---

### DL-111 — RPO ≤ 5 min, RTO 1 h per partition and 24 h full cluster — signed

**4 Sep 2026** · `RATIFIED` (D5a) · ADR-003 D5, DL-084, BR-30, BR-35, NFR-04 · T-051, T-050

**Decision.** The engineering proposal of 29 Aug 2026 is **signed by Naman
Vashishtha on 4 September 2026**: **RPO ≤ 5 minutes** via continuous WAL archiving;
**RTO 1 hour** to restore a single partition, **24 hours** for a full cluster
restore. DL-084 moves from `PROPOSED` to ratified and **T-051 is unblocked**.

**Why RPO is the tight number and RTO is deliberately loose.** Lost data is
unrecoverable: Heimdal retries only on a non-2xx, so anything we answered 200 for
and then lost is gone, and no amount of restore time recovers it. RTO is loose
because this is 90-day telemetry rather than financial records, and at
multi-terabyte scale a tight RTO may not be purchasable at any sensible price.

**What signing obliges, beyond a number in a document.** A target nobody measures
is not a target:

* Continuous WAL archiving must actually be **configured and its lag monitored**. A
  stalled archiver silently converts RPO from 5 minutes to *"however long ago it
  stopped"* — the failure is invisible until the restore. Archiver lag joins
  T-050's alert set.
* **T-051 must be timed, not merely completed.** It restores a single partition and
  re-attaches it *without taking the parent table offline*, and the elapsed time is
  compared against the 1-hour figure.

**Consequence.** T-051 moves BLOCKED → TODO. D5a leaves the open-questions sheet.

---

### DL-112 — Late arrival: skip, meter, ACK, alert — signed

> **BUILT 10 Sep 2026 — see [DL-133](#dl-133--a-record-past-its-window-is-skipped-and-acked-and-the-sweep-and-the-guard-share-one-cutoff).**
> `RetentionGuard` implements it, and the guard reads the same cutoff as the sweep that
> drops the partition. **The alert is still not built** — that half is T-050.

**4 Sep 2026** · `RATIFIED` (D5b) · ADR-003, DL-083, DL-106, STA-23B · T-026, T-052, T-050

**Decision.** Signed by **Naman Vashishtha on 4 September 2026**, alongside DL-111.
A record arriving with `ts_device` older than the retention window is **skipped**,
metered as `records_expired_on_arrival`, and **ACKed**, with an **alert when the
counter is non-zero**. DL-083 moves from `PROPOSED` to ratified and **T-026 is
unblocked**.

**This is deliberate data loss, and the signature is what makes it a decision
rather than a defect.** It is also the only implementable option: no live partition
exists for such a record, so the alternative is writing a row into a partition that
does not exist. Writing it into a freshly created partition that retention then
removes minutes later is worse — it costs the write, the partition and the delete,
and it looks like success.

**Why it is signed now and not on 29 August.** DL-106 removed the one reason to
hold it. While Q15b was open it remained possible that `ts.device` was stamped on
arrival, in which case no record could ever be late and this policy governed a path
production never takes — signing a data-loss policy for a hypothetical path is how
a policy stops being read carefully. Q15b's answer makes the path real.

**Why the alert, and not the counter alone.** A systemic gateway backlog and a
harmless one-off are the same number in a raw count. The alert is what separates
them.

**Consequence.** T-026 moves BLOCKED → TODO, with boundary tests either side of the
retention window. **Trap 18 is the same code path reached from the DLQ**: a message
redriven after ~90 days increments `records_expired_on_arrival` and looks like a
successful recovery unless T-052's runbook says otherwise. D5b leaves the
open-questions sheet.

---

## 12. What is deliberately still open

Not decisions — the absence of them. **Nine items closed on 4 September 2026**
(DL-104…DL-112) and **two were added on 12 September 2026** (DL-139, from the Datahub API
guide); what is left is below. Historical status as at 29 Aug:
`claude/OPEN_QUESTIONS_STATUS_29AUG2026.md`.

| Ref | Open question | Owner | Blocks |
|---|---|---|---|
| Q8a-2 | Heimdal inbound auth — mTLS or OAuth2; issuer, audience, identity, rotation. Our position: OAuth2 client credentials, sent as *"unless you object by \<date\>"* | ZC / security | Nothing today (DL-063). **Must close before any shared deployment** — until then anything that reaches the Input port can write |
| **Q8a-3** | Is the stats push a Heimdal **ForwardRule** (`{url, type, id, maxRetry}`, credentials in the URL)? If so the auth mode is HTTP basic — not in `stats.input.auth.mode` — and Heimdal's retry is **bounded by `maxRetry`**, not indefinite (DL-139) | ZC, with Q8a-2 | Nothing today. Changes the *shape* of the Q8a-2 answer and the reading of rule 8 |
| **D16** | A gateway that changes tenant keeps its pre-move rows under the old `tenant`; the new tenant cannot see them. Intended? Recommended: **yes** — `tenant` is the value at measurement time (DL-139) | Engineering — ADR-006 amendment when decided | Nothing. A support question, not a build gate |
| Q8b-2 | Confirmation of the proposed Keycloak realm values | ZC | Nothing — a config change (DL-064) |
| **D-09** | ZC confirmation of `docs/contracts/openapi.yaml` | ZC | **Phase 4 — the one item that genuinely gates work.** T-038 directly, T-039…T-046 transitively |
| — | API consumer catalogue: who calls this, with what filters, to what SLO? BRD §3 lists four, all pending | Product | Index-design justification and Phase 4 scope. No operation or index should exist without tracing to a consumer |
| D-03 | Exact success code (200 vs 202), path confirmation, request size and timeout limits | Heimdal owner | Request-limit properties only |
| D-10 | Runtime platform and release mechanics. **Named gap:** `docker/Dockerfile` never `COPY`s `stats-testsupport`, included in `settings.gradle.kts` since DL-099; latent because `bootJar -x test` never resolves the test classpath | Platform | Packaging and release only; nothing before Phase 5 |
| D-12 | Data classification; masking for **subscriber, location AND network** identifiers (not IMSI alone — DL-102); log retention number and access list; audit evidence. Encryption at rest and the log-driver mechanism are **platform's**, not security's | Security + compliance | Production logging and operations. Input document: `docs/decisions/DATA-SENSITIVITY-INVENTORY.md`. **Deadline is T-034** |

### Residuals carried by a closed decision — not open questions

Each of these sits inside a decision that is made. None blocks anything; each is
recorded so it is not rediscovered as a surprise.

| From | Residual | Why it does not block |
|---|---|---|
| DL-105 (Q15a) | *Are the `lorastats` dialects being retired?* Only the first half of Q15a was answered | All three are accepted. This only decides whether a parser can ever be deleted — and `records_lorastats_dialect{dialect}` is what will show one going to zero |
| DL-108 (Q15d) | The vocabulary of non-OK `status` values — **one more known-good value since DL-139:** measurement documents carry `["ok"]` where stats records carry `["conversion ok"]` | The counter is value-agnostic by design and will enumerate the real values itself; T-050's threshold should be set after the first 24 h, not at zero |
| DL-110 (Q4b) | IPv6, *"for now consider no"* | Provisional by its own wording. The metered per-field rejection code is the reopening trigger — a non-zero count reopens Q4b |
| DL-104 (Q13b) | Jitter is a statement, not a measurement | Hardware is not cut until the `ts.server mod 600` histogram over the first 24 h of ingest confirms it (T-050) |
| DL-109 (Q13c) | The 2× replay cap is agreed and **unenforceable by us** | 503 backpressure, bounded prefetch and T-018's rate limit are the controls we own, and they stay regardless |

> **The standing rule:** do not invent an answer to any of these. If a task
> requires one, say which question blocks it and stop. A row marked
> `PENDING APPROVAL` is not permission to assume the recommended option.

---

### DL-113 — The `version` guard is written before the parser that would misuse it

**4 Sep 2026** · `ENGINEERING` · trap 20, DL-105 rule 1, ADR-007 §4 (amended) · T-063

**Decision.** `VersionIsNotADiscriminatorTest` scans every Java main source and fails
if any file outside a justified whitelist names the bare identifier `version`. Three
entries are whitelisted today, each with its reason: `StatsEnvelope` declares the
component, `StatsSchemaValidator` reads the *schema file's* own version — a different
concept that happens to share the name — and `LorastatsBatchWriter` stores it verbatim
as `record_version`, whose only conditional is a `SMALLINT` width check.

**Why now, before T-063 rather than as part of it.** DL-105 made this trap more
dangerous, not less. Until 4 September there was no dialect-selection code at all, so
nothing *could* branch on `version`. T-063 introduces structural dialect selection, and
`version` will sit immediately beside it looking exactly like the discriminator it is
not — all three production dialects carry `version: 1`. A guard written after the parser
gets written to match whatever was built; written first, it has to be argued with, and
the argument is the whole value.

**Why a whitelist of files rather than a rule about branches.** A test cannot prove an
expression is not a discriminator without understanding the code. This detects a
*shape*, like `StatTypeFanOutTest` before it: naming the identifier at all outside the
passthrough mapping requires a whitelist entry with a written reason, which converts
"someone quietly read `version`" into a reviewed decision. The regex is deliberately
narrow — `\bversion\b` misses `conversion`, `record_version` and `schemaVersion` — so
it does not fire on unrelated names.

**Mutation-checked.** Removing the `StatsSchemaValidator` exemption makes the test fail
and name the file; restoring it passes. A guard that cannot fail is not a guard, which
is the same standard trap 17's DLQ-TTL assertion is held to.

**The failure it prevents is silent.** Branching on `version` yields a parser that works
perfectly on whichever dialect its author tested and mis-parses the other two into the
same typed columns. The rows are *wrong*, not absent, and nothing fails — which is why
this is a guard and not a code review note.

**Consequence.** Scanning moved into `MainSourceScan`, shared with `StatTypeFanOutTest`,
so a module added to `MODULES` is covered by both guards at once. Trap 20's second half
— the two dialect fixtures moving from `invalid/` to `valid/` — still belongs to T-063.

---

### DL-114 — D-10 and D-12 lose the halves that were engineering's all along

**4 Sep 2026** · `ENGINEERING` · D-10, D-12, ADR-003 D8, DL-099, DL-102, DL-103 · T-065, T-066, T-034

**Decision.** Four fixes, taken together because they share one shape: each was an item
sitting on a *someone else* row that nobody else could have done.

**1. The Writer refuses a deployment it cannot honour (T-065).** `stats.writer.stat-type`
defaults to `all`, ADR-003 D8 requires exactly one type per deployment in production, and
nothing refused it. `StatTypeDeploymentGuard` now fails the context under the `prod`
profile, and refuses an unrecognised value in *every* profile. The second half is the
dangerous one: a typo makes every per-type `Condition` evaluate false, so the Writer
starts, binds no listener, consumes nothing and still reports ready — readiness has no
opinion about whether a listener exists. `all` outside `prod` stays legal and logs a
`WARN`; a guard that forbade it everywhere would simply get the property deleted.

**2. The Dockerfile copies every module (D-10 residual).** It listed the four original
modules and never `stats-testsupport`, a module since 3 Sep (DL-099). It built green only
because `bootJar -x test` never resolves the test classpath and the warm-up layer ends in
`|| true`. Both `COPY` lines added; the `|| true` stays, now with a comment saying what it
cannot catch. `settings.gradle.kts` also still called itself "Four modules" above a list
of five.

**3. P1, P2 and P3 are adopted (D-12).** The inventory offered five proposals and split
them by reversibility. The three that need no signature are taken: **P1** never log an
`ltestats` or `netstats` payload at any level including `DEBUG`; **P2** log the exception
type and message, never PostgreSQL's `DETAIL:` line; **P3** T-034 does not merge until the
masking questions are answered. P1 and P2 are now in `DEVELOPMENT.md` §7, which is the
authoritative logging rule; P3 is T-034's `Blocked by` cell rather than a line in a table
nobody schedules from. **P4 and P5 remain security's** — they are irreversible, and
adopting the reversible three pre-decides neither.

**Why adopt logging rules for code that does not exist.** `stats-writer` has no `ERROR` or
`WARN` path at all until T-024 and T-057. That is the argument for writing them now, not
against it: a logging rule adopted after the log line exists is a rule someone has to go
back and enforce, and the first `ERROR` path in this system will be written while chasing
a failure, which is the worst moment to be deciding what may be printed. Same reasoning as
DL-113.

**4. Both rows had no task, and that is why they sat.** D-10's STAT_TYPE gap and the whole
of D-12 had no `T-nnn`, so nothing in the Tasks sheet surfaced either — the defect the
29 August pass caught for Q14a, repeating twice. T-065 (done) and T-066 (pending T-024 and
T-057) now exist.

**Consequence.** D-10 is **platform-only**: runtime platform, image registry, promotion
path, resource requests and limits. Its resource-sizing item is no longer blocked on Q13b
either, which DL-104 answered — though DL-104 defers sizing down until T-050's histogram
confirms the jitter. D-12 keeps five questions for security plus two items re-owned to
platform, and engineering's remaining obligation is to honour what it just adopted.


---

### DL-115 — `clsbox` is stored in one canonical case, by normalisation and not by rejection

**5 Sep 2026** · `ENGINEERING` · ARD §7.1, Q14, STA-21C, ADR-003, ADR-007 §1 · T-011, T-022, T-040

**Decision.** Four parts, and the split between them is the decision:

1. **The Writer uppercases `clsbox` before the `INSERT`** (`LorastatsBatchWriter`,
   `Locale.ROOT`).
2. **V5 adds `CHECK (clsbox ~ '^[0-9A-F]{12}$')`** as the backstop, so the invariant
   belongs to the table rather than to one class. ADR-003's *Required table and index
   direction* carries it, and **every new stat-type table must** — like `data_extra`.
3. **The envelope schema keeps `^[0-9A-Fa-f]{12}$`.** A lowercase record is never
   400'd and never dead-lettered.
4. **The API filter is `clsbox = upper(:clsbox)`**, never `upper(clsbox) = …`.

**Why.** The ARD specified `gateway CHAR(12) NOT NULL CHECK (gateway ~ '^[0-9A-F]{12}$')`.
V1 renamed the column to `clsbox` under ADR-007 §1 and dropped the constraint; nothing
replaced the guarantee. So `04b64810652b` and `04B64810652B` — one physical device —
were two distinct keys in `idx_lorastats_tenant_clsbox_device_uid`.

That is the quiet kind of wrong. `clsbox` is half the dominant query pattern (Q14), and
`openapi.yaml` promises the filter matches case-insensitively. A mixed-case estate
therefore returns a **silently partial page**: rows stored, query well-formed, response
200, and the missing rows indistinguishable from a device that reported nothing. It is
the twelfth entry in the same family as the `tenant`/`maintenant` trap — a filter that
is *almost* right returns *almost* the data, and nothing anywhere raises.

No test could have caught it. Every `clsbox` in `docs/schemas/fixtures/` was uppercase
until `valid/lorastats-clsbox-lowercase.json` was added with this change.

**Why normalise rather than reject.** Enforcing the ARD's CHECK *without* normalising
would dead-letter a valid measurement over hex case — the same mistake `netstats` made
by requiring `ip`/`gw`/`mask`/`dns` on a modem that never came up, and the one DL-105
has just finished reversing for the `lorastats` dialects. Rejecting at Input would be
worse still: Heimdal retries only on a non-2xx, so a 400 would loop forever on a record
that can never satisfy it.

**Why not a functional index.** `(tenant, upper(clsbox), ts_device DESC, uid DESC)`
would preserve the received bytes exactly. It was rejected: it changes the index shape
ADR-003 specifies, and every future per-type table would have to repeat the functional
form or silently lose the index — reopening this defect one type at a time.

**What is given up.** The as-received case. Hex case carries no meaning, so nothing a
reader can use is lost, but it is still a normalisation: the exact bytes are recoverable
only from the published message, not from the row. ADR-004's byte-identical publication
is untouched — Input republishes the received bytes and never reads this column.

**Consequence.** `LorastatsBatchWriterIntegrationTest` asserts both spellings answer one
exact-match lookup on the covering index, and that the table refuses a non-canonical
value when the Writer is bypassed. T-040 must use `clsbox = upper(:clsbox)`; a predicate
of the form `upper(clsbox) = …` seq-scans every live partition and is a review failure.

---

### DL-116 — The 4 September reconciliation is finished in the files that are not Markdown

**5 Sep 2026** · `HOUSEKEEPING` · DL-105, DL-106, DL-108, DL-112 · T-060, T-063, T-064, T-026

**Decision.** Seven artifacts still stated the **reversed** ADR-007 §4 as current policy,
eight working days after DL-105 reversed it. All are now corrected:
`stats-writer/.../application.properties`, `.env.example`, `docs/schemas/lorastats-v1.schema.json`,
`docs/schemas/SCHEMA_CATALOGUE.md`, `docs/schemas/fixtures/MANIFEST.md`,
`valid/lorastats-production-canonical.json`, and `docs/contracts/openapi.yaml`.
`envelope-v1.schema.json` additionally still carried Q15b as an open question (DL-106
answered it) and described `status` as never interpreted (DL-108 meters it).

**`stats.writer.dlq.alert-depth.lorastats` and `STATS_DLQ_ALERT_DEPTH_LORASTATS` are
deleted.** Both existed only to hold a threshold for a permanently non-empty `lorastats`
DLQ, which DL-105 removes. A uniform `alert-depth.default=0` is correct again.

**Why.** Commit `874ef77` reconciled `claude/*.md` and the ADRs and stopped there, so the
correction landed everywhere except the files an engineer actually opens while working —
a live properties file, the normative schema catalogue, and the API contract. A decision
that is right in the decision log and wrong in the config is worse than one that is
merely undocumented: the config reads as current and gets believed.

**The distinction every edit preserves.** DL-105 changed the **decision**; T-063 changes
the **behaviour**, and it is not built. Today only the bare/integer dialect validates and
the other two still dead-letter. **[Superseded 8 September 2026 by DL-127: T-063 landed
and all three now validate. Every passage this entry corrected was corrected again that
day — which is the cost of stating a build state in a document, and still cheaper than
stating a decision the code does not implement.]** Every corrected passage says so
explicitly rather than
describing the end state as though it had arrived — the failure mode in the other
direction, and the one DL-100 was written about. The two `invalid/lorastats-dialect-*.json`
fixtures already had this exactly right; the wording follows them.

**Consequence.** A grep for `dead-letter by design`, `most urgent question for ZC`, or
`BLOCKED on D5b` returns nothing outside the dated historical documents
(`claude/OUTBOX_03SEP2026.md`, the ADR narratives that record *why* Q15a was escalated),
where the past tense is correct and must stay.

### DL-117 — The API contract is linted in CI, because a YAML parse cannot see a broken contract

**5 Sep 2026** · `ENGINEERING` · DL-070…DL-073, DL-100, DL-116, ADR-006, STA-38A · T-038, T-039–T-046

**Decision.** `redocly lint` runs on every push and pull request, pinned at `2.51.2`,
before the Gradle build. Its rule set is `recommended` with exactly two suppressions,
each recorded with its reason in the new root `redocly.yaml`. Seven defects it found in
`docs/contracts/openapi.yaml` are fixed in the same change.

**The defect class.** Five `data`-schema properties carried an **unquoted description
containing a comma inside a YAML flow mapping**:

```yaml
plmn: { type: string, pattern: '^[0-9]{5,6}$', description: Public land mobile network code, as a string. }
```

The comma closes the value and `as a string.` is parsed as a **sibling key of the schema
object**. `LtestatsData.plmn`, `.lac`, `.cid`, `SysstatsData.memfree` and
`OmsstatsData.timespan` each gained one or two nonsense keywords. Two further properties
— `SysstatsData.uptime` and `.idletime` — were the same construct without a comma, so they
parsed correctly today and would have broken the moment anyone edited the sentence; all
seven are quoted, not just the five that failed.

**Why a linter and not review.** `yaml.safe_load()` accepts every one of these files
without complaint, and so does a human read — the line looks like prose because it *is*
prose. Only a validator that knows what an OpenAPI schema object may contain rejects it.
This is the same shape as the trap in `CLAUDE.md` §6 about consistency across documents
not being evidence of correctness: the file was internally consistent and wrong.

**Why the two suppressions are decisions rather than noise.** `info-license-strict` wants
an SPDX identifier or URL on a licence that is `Internal / Confidential` — there is no
such identifier, and satisfying the rule would mean asserting a licence that does not
exist. `no-server-example.com` objects to `http://localhost:8083/v1`, which is
deliberately present so a reader can exercise the API against
`docker/docker-compose.yml`; the templated ingress leads the list. Both are turned off in
config with the reasoning inline, so the gate exits **zero** on a correct contract.
A gate that always emits warnings is a gate that is never read — DL-100.

**Verified, not assumed.** The pinned linter exits `1` on a copy with one description
un-quoted and `0` on the contract as it now stands. A gate nobody has watched fail is not
known to be a gate.

**Consequence.** `openapi.yaml` can be circulated for the confirm/decline of T-038 without
the first thing ZC's tooling reports being our punctuation. Any new stat type added to the
contract (`CLAUDE.md` §5 rule 6) is linted on the same push that adds it.

**Not closed by this.** The **schema-drift obligation** in the `openapi.yaml` header still
stands: the per-type `data` schemas are inlined rather than `$ref`'d to
`docs/schemas/*.schema.json`, and nothing yet asserts the two agree. `redocly lint` checks
that this file is a *valid* OpenAPI document; it cannot know that `LtestatsData` here and
`ltestats-v1.schema.json` there describe the same type. That check is still owed before
the contract is frozen — and DL-116, one day earlier, is the evidence that these two
sources do drift.

---

### DL-118 — A 2xx follows the confirm, and Input declares the exchange and nothing else

**5 Sep 2026** · `ENGINEERING` · STA-04A, STA-06A, rule 8, ADR-002 §9/§11, ADR-005 · T-014, T-015, T-032

**Decision.** Four parts.

1. **`ConfirmingStatsPublisher` reads the confirm off `CorrelationData.getFuture()`
   with a bounded wait** (`stats.amqp.confirm-timeout`, 5 s). `CONFIRMED` is returned
   on an ack with no return, and on nothing else. Nack, timeout, interrupt and
   `AmqpException` all become `NOT_CONFIRMED`, therefore 503.
2. **An ack carrying a returned message is a FAILED acceptance** —
   `records_unroutable{type}` plus the `ERROR` log with the routing key, and 503
   (ADR-002 §9). `records_publish_failed{type}` moves as well: this counter is the
   *cause*, that one is the *outcome*.
3. **`stats-input` declares the `app_stats_input` exchange and no queues.**
4. **`stats.exchange.name` becomes `stats.amqp.exchange`, is bound, and is checked
   against `AmqpTopology.INPUT_EXCHANGE`** — any other value refuses to start.

**Why `waitForConfirms` is not used.** It is the `simple`-mode API and this template
runs `correlated`. Mixed, it answers about a batch the calling thread never published —
a green test and a 200 for a record nobody holds.

**Why Input declares no queues.** ADR-002 §11 puts declaration in the broker-connected
modules; it does not say each declares every object, and the distinction earns its keep
twice. A queue Input declares for a type whose Writer is not deployed is an undrained
quorum queue nothing alerts on, because depth alerting belongs to the Writer (§12). More
seriously, declaring the queues would make the missing-binding case unreachable — and
that case is the *entire* reason `mandatory` is set. A confirm says the broker accepted
the message, not that a queue wanted it; a half-applied topology change must surface as
a return, not be papered over by the publisher declaring its own consumer's queue.

**Why the exchange name is checked rather than merely bound.** Pointing Input at an
exchange no Writer binds to is accepted, broker-confirmed, and silently dropped — every
component reporting success while the record ceases to exist. The property exists so a
deployment can *see* the name, not choose it. Unbound, it was documentation with nothing
reading it, which is the state DL-101 was written about.

**Verified, not assumed.** The framework guarantee this rests on — that Spring AMQP sets
the returned message on the `CorrelationData` before completing its future — is asserted
against a real broker with a genuinely unbound routing key in
`IngestPublishIntegrationTest`, not taken from the reference documentation. The timeout
path asserts **elapsed time**, because "waits until the socket dies" also returns
`NOT_CONFIRMED` and would look identical in a test that only checked the outcome.

**Consequence.** `UnconfirmedStatsPublisher` drops out through its own
`@ConditionalOnMissingBean` and `PublisherConfiguration` was not edited, as it was
designed for. That condition is evaluated in configuration-registration order, which for
a component scan follows class name: `InputAmqpConfiguration` sorts before
`PublisherConfiguration`. A rename could invert it silently and leave a module that
starts cleanly and publishes nothing, so `PublisherSelectionTest` asserts there is
exactly one `StatsPublisher` and it is the confirming one.

**Still open.** T-032 — the cross-module declaration-conflict test — is not closed by
this. `IngestPublishIntegrationTest` pins Input's half (name, durable, non-auto-delete,
no arguments); the Writer's half is still a second hand-built `TopicExchange`, because
`AmqpTopology` deliberately holds no Spring AMQP types and `stats-common` has no broker
dependency. A mismatch is `RESOURCE_LOCKED` for whichever module boots second.

---

### DL-119 — Byte-identity is proved with a fixture built to fail, kept out of the normative set

**5 Sep 2026** · `ENGINEERING` · ADR-004, ADR-005, STA-25A · T-016

**Decision.** Input publishes with `RabbitTemplate.send` and a hand-built `Message`,
never `convertAndSend`, and the proof is
`stats-input/src/test/resources/byte-identity/lorastats-adversarial-formatting.json` —
a record posted through the endpoint and compared byte-for-byte with what arrives on the
queue.

**Why the fixture is deliberately ugly.** Keys out of canonical order, mixed indentation,
`é` where Jackson would emit a literal `é`, the signed `BIGINT` maximum, and no
trailing newline. A well-formed record round-trips through Jackson unchanged, so a test
using one **passes against a publisher that re-encodes every record** — it asserts field
equivalence while claiming byte identity. The assertion was removed once and the test
confirmed failing; an identity test that cannot fail is decoration.

**Why it is not in `docs/schemas/fixtures/`.** It is a *serialisation* fixture, not a
schema-conformance one. That set is gated by `validate-fixtures.py` on count and
`MANIFEST.md` entry, and a file whose entire purpose is its byte layout has no business
in a directory somebody may reasonably reformat.

**Why this matters beyond tidiness.** ADR-005's duplicate outcome is
`ON CONFLICT (ts_device, uid) DO NOTHING`. That is only correct if a redelivered record
*is* the same record. Re-encoding at publish makes a replayed record a different sequence
of bytes carrying the same identity — which still suppresses, but silently changes what
was stored versus what was sent, and there is no longer any artifact that can prove which.

---

### DL-120 — The inbound auth seam is a servlet filter, not Spring Security — yet

**5 Sep 2026** · `ENGINEERING` · Q8a, Q8a-2, ADR-006, 2B-ANSWERS §3 · T-017

**Decision.** `stats.input.auth.mode` selects the mechanism, with no default in code.
`none` registers a pass-through filter on the ingest path and WARNs on every startup;
`mtls` and `oauth2` **fail while the context is building**; the `prod` profile refuses
`none` outright, in `ProductionAuthModeGuard`. `spring-boot-starter-security` is **not**
added yet.

**Why not the starter now.** Adding it changes the default posture of the entire
application — every actuator endpoint included, and the readiness probe
`docker-compose` health-checks with it — before the mechanism Q8a-2 will choose is even
known. What T-017 owes is the seam. A filter registered on one URL pattern is the
smallest thing that is honestly a seam, and the starter arrives *with* the OAuth2
implementation, where its defaults can be argued against a real requirement.

**Why an unimplemented mode fails at startup rather than per request.** The alternative
— accept `oauth2`, let requests through until someone implements it — produces an
endpoint that reports itself as authenticated and is not. That is strictly worse than
`none`, which is at least honest about what it is.

**Why `none` occupies the seam rather than registering nothing.** A mode that quietly
registers no filter is indistinguishable, at every later reading, from a mode whose
filter failed to register — and the difference between those two is whether the endpoint
is open by decision or by accident.

**Filter order is a decision:** size (10) → auth (20) → rate limit (30). Size first
because it is a header comparison that reads nothing. Rate limiting after authentication
so that, once Q8a-2 lands, an unauthenticated caller cannot spend a legitimate sender's
budget. None of the three touch `/actuator/**`.

**Risk restated, unchanged.** Until Q8a-2 closes, anything that can reach the Input port
can write statistics. Acceptable on a local dev stack and nowhere else — and now
enforced by a context that will not build under `prod`.

---

### DL-121 — Request limits are engineering defaults in properties, and 413/429 join the shared catalogue

**5 Sep 2026** · `ENGINEERING` · STA-06B, D-03, DL-109, contract §2 · T-018

**Decision.** `stats.input.limits.max-request-bytes` (256 KB) and
`stats.input.limits.max-requests-per-second` (4,000, **per instance**) are enforced by
two filters. `ErrorCode` gains `PAYLOAD_TOO_LARGE` (413) and `TOO_MANY_REQUESTS` (429).
The read timeout is `server.tomcat.connection-timeout`, not an application property.

**Why now, with D-03 open.** D-03 fixes no numbers — the contract's response matrix says
413 and "429 or 503, exact policy pending". An **unbounded ingest endpoint is wrong at
every value D-03 can return**, so waiting was never the safe option. The values live in
`application.properties` and the record carries no default of its own, so ZC's answer is
a property change with a decision-log entry behind it.

**The trap this avoids.** The obvious implementation is
`server.tomcat.max-http-form-post-size`, and it does nothing here: that limit applies to
form content and Heimdal sends `application/json`. A limit that is configured,
documented, and never fires is worse than no limit, because nobody looks at it again.
Content-Length is checked before a byte is read; a chunked body has no declared length,
so the stream is wrapped and `PayloadTooLargeException` travels out through
`IngestExceptionHandler` — which also unwraps it from `HttpMessageNotReadableException`,
because answering that 400 would tell Heimdal to fix a record that is not malformed.

**Why one error catalogue and not two.** Both codes appear only in the inbound contract,
so an Input-local enum was the obvious alternative. Two catalogues means two type-URI
bases, and a caller matching on `type` would have to know which module answered before it
could parse the error. `ErrorCode`'s javadoc naming only `openapi.yaml` is what made a
second one look necessary — the javadoc was the bug.

**Why the rate limit is per instance.** The cluster ceiling is this value times the
replica count, and that is the number to reason about when sizing against the 2× replay
cap. A shared limiter puts a network round trip and a new dependency in front of every
ingest. DL-109 records that Heimdal has agreed the cap and that we cannot enforce it:
this filter and rule 8's 503 backpressure are the two controls we own. A 429 is a
non-2xx, so the record is resent — the limit sheds load, it does not drop records.

**`requests_too_large` and `requests_rate_limited` carry no `type` label**, which is the
one exception to the per-type rule in `IngestMetrics`. Both fire before the body is read,
so the type is genuinely unknown rather than unreadable, and `type="unknown"` already
means "we parsed and could not tell" — reusing it would merge a flood of oversized
requests into the series an operator watches for malformed records.

---

### DL-122 — The failure arm is built, and the copy is confirmed before the original is ACKed

**5 Sep 2026** · `ENGINEERING` · STA-12, STA-25, STA-25A, rule 5, rule 9, ADR-002 §4/§12, ADR-005, G1/G4 · T-024, T-020, T-057, T-060

**Decision.** `stats-writer` now has the whole failure arm. Five parts.

1. **Three retry tiers and a per-type DLQ are declared** — `app_statsstore.<type>.retry.1..3`
   with the configured TTL, `app_statsstore.<type>.dlq` with no expiry of any kind, and
   `app_stats_dlx` carrying both journeys.
2. **`FailedDeliveryRouter` publishes the onward copy and waits for its confirm.** The
   consumer ACKs the original only after that call returns. If the copy cannot be
   confirmed the router throws, nothing is ACKed, and the broker keeps the only copy.
3. **Attempts are counted in our own header,** `x-stats-attempt`, not in RabbitMQ's
   `x-death`.
4. **`FailureClassifier` sends permanent failures straight to the DLQ** — an explicit
   `PermanentFailureException`, a Jackson parse failure, or a
   `DataIntegrityViolationException`. Everything else is retried.
5. **Delays and the ceiling are properties** — `stats.amqp.retry.tier{1,2,3}-delay`
   (30 s / 5 min / 30 min) and `stats.amqp.max-retries=3`.

**The ordering is the decision, and it is the eleventh loss window.** ACK-then-publish
is the tempting order and it opens a gap in which the original is gone and the copy does
not exist. A crash inside that gap loses the record with the broker, the database and
Heimdal all reporting success — no exception, no counter, no queue depth, nothing.
`UnconfirmedHandoffException` exists so the safe order cannot be undone by accident, and
`LorastatsConsumerTest` asserts it as an *order* rather than as two calls that both
happened.

**Why our own attempt header.** Half of this loop is broker-driven — a tier's TTL
expiring dead-letters the message back to the main queue, which does append `x-death` —
and half is ours, an explicit publish that does not. Counting attempts from a field only
one half maintains gives a number that resets, and a record that never reaches the DLQ
retries forever. The header is also why a stale `x-death` is stripped from the copy:
two accounts of one history is worse than one.

**Why permanent failures skip the tiers.** 35½ minutes of queue time per record, and at
115.2m records/day a systematic permanent failure retried three times is three times the
broker traffic and three times the delay before DLQ depth is actionable. **The default
runs the other way on purpose:** anything not recognised as permanent is retried, because
wrong-and-retry costs 35½ minutes and wrong-and-dead-letter costs a person, a root cause
and a redrive.

**A duplicate is not a failure.** `ON CONFLICT (ts_device, uid) DO NOTHING` absorbs it
and meters it long before an exception exists (ADR-005). Nothing in this arm sees one.

**The body is never touched.** The original bytes are republished verbatim; only headers
change. ADR-004's byte-identical publication has to survive the retry loop, because a
redriven record must still collide on `(ts_device, uid)` when it finally lands.

**Two things this changed that were previously asserted the other way.**
`LorastatsConsumerIntegrationTest.aProcessingFailureLeavesTheRecordWithTheBroker…` was
correct for the code that existed — with no arm, a failure stayed unacknowledged and was
requeued on channel close, so the same record was redelivered forever. It now asserts the
new contract. And `stats-writer/application.properties` no longer says the retry queues
are undeclared.

**Not closed by this.** The payload gate (T-057) is still the missing producer of
permanent failures — `PermanentFailureException` is the channel it will use. DLQ depth
and oldest-message-age alerting is T-060, so **the DLQ is real but nobody is watching it
yet**, and ADR-002 §12's 14-day disposition has no trigger until that lands.

---

### DL-123 — T-032 is closed by construction, not by a two-context test

**5 Sep 2026** · `ENGINEERING` · Q3a, ADR-002 §8/§11, trap 8 · T-032, T-002

**Decision.** Three parts, and together they are T-032.

1. **`AmqpTopology` now owns the whole declaration, not just names and arguments.**
   `ExchangeDefinition` and `QueueDefinition` carry durability, auto-delete and
   exclusivity as well. Both broker-connected modules build their Spring AMQP objects
   from them.
2. **`AmqpDeclarationAssert` in `stats-testsupport` checks a declared object against its
   definition,** and both modules' tests call it — one implementation, two modules.
3. **`stats-api` asserts the absence:** the AMQP client types are not loadable from its
   classpath in any scope.

**Why not the obvious test.** T-032 was written as a declaration-*conflict* test: start
both modules against one broker and watch the second fail. It cannot be written that way
here without a test-scope dependency from one application module to another, which
`claude/DEVELOPMENT.md` §2 forbids for the good reason that test scope is one edit away
from compile scope. Moving the values into shared definitions makes the conflict
*impossible* rather than *detected*, which is strictly better: a detected conflict is
still a conflict somebody has to diagnose.

**What was actually wrong.** Names and argument maps were already shared (T-002). The
durability and auto-delete flags were not: each module passed them positionally into its
own `new TopicExchange(...)` and `new Queue(...)`. They are exactly as fatal — RabbitMQ
rejects a mismatched declaration with `RESOURCE_LOCKED` and that module fails to start,
in whichever environment happens to boot them in the wrong order, which is rarely the one
where the change was made. Quorum sharpens it again: a queue's type is fixed at
declaration, so correcting one means deleting and redeclaring it (ADR-002 §8).

**Why the `stats-api` half is a classpath assertion.** "No `ConnectionFactory` bean" can
only be written by a test that can name the type, which means putting spring-amqp on that
module's test classpath — and the moment it is there, someone can promote it to
`implementation` and the test still passes. Asserting the class cannot be loaded fails the
day the dependency appears.

**Proved by hitting it.** `RetryAndDeadLetterIntegrationTest` originally shortened the
tier delays to one second and failed on the shared broker with inequivalent
`x-message-ttl` arguments — the exact trap, met by the test written to prove the arm. It
now uses the production delays and provokes exhaustion with an attempt header instead.

**Residual.** Four lines per module still turn a definition into a Spring object.
Removing even those would mean either a broker client in `stats-common` — which its five
responsibilities (D15) exclude — or a sixth module. The assertion covers them; the
duplication is deliberate and bounded.

---

### DL-124 — Guard P2 is enforced by the build, because writing it down first was not enough

**5 Sep 2026** · `ENGINEERING` · D-12, DL-114, STA-03A, DEVELOPMENT.md §7, `DATA-SENSITIVITY-INVENTORY.md` §7 · T-066, T-024, T-021

**Decision.** Three parts.

1. **`SafeFailure` is the only way a failure may enter a `stats-writer` log line.**
   `describe()` returns the causal chain of exception **type names** plus the `SQLState`
   when one is present — `DataIntegrityViolationException <- SQLException sqlState=23505`
   — and no message and no stack, ever. `SQLState` is validated as five alphanumerics
   before it is included.
2. **`WriterLogsCarryNoThrowableTest` fails the build** if any `stats-writer` main source
   hands a throwable to a logger. The allow-list is empty and every future entry needs a
   written reason.
3. **The architecture guards' scanned sources are declared as Gradle task inputs.**

**Why the rule had to be narrowed to be implementable.** P2 as adopted said *"log the
exception type and message, never the driver's `DETAIL:` line"*. That cannot be built: for
a `PSQLException` the detail **is** part of the message, and there is no substring of it
that is reliably value-free. Our own exceptions are no safer — nothing stops a processor
throwing `new PermanentFailureException("uid=… failed the schema")`, and **T-057 is about
to add exactly that kind of throw**. So the implemented rule is stricter than the adopted
one: no message at all.

**What survives is more useful than what was removed.** `SQLState` is the diagnostic that
matters — `23505` is a unique violation, `22003` a numeric overflow, `23514` a violated
CHECK — and it is a five-character code from the standard, carrying no values by
construction. The stack is not lost so much as relocated: the record itself is on the
dead-letter queue with its own headers, under whatever access control the DLQ has
(ADR-002 §12), rather than under the log-retention policy that does not exist yet.

**The correction this entry exists to record.** DEVELOPMENT.md §7 said on 4 September that
*"stats-writer has no `ERROR` or `WARN` path at all until T-024 and T-057"*, and the 5
September status page repeated it as "the rule and the breach arrived together — two
statements added by T-024". **Both were wrong.** There were **four** sites, and the oldest
predated the rule: `LorastatsBatchWriter` had logged `LOGGER.error(…, failure)` on the
INSERT failure path since T-021 — the precise path P2 names — and `PartitionMaintenance`
had a second. T-024 then added two more by copying the shape, which is exactly how this
kind of defect propagates.

**Why the Gradle inputs are part of the decision and not housekeeping.** The guard passed
on its first negative test. It had not run: `:stats-common:test` was UP-TO-DATE, because
Gradle cannot see that the task reads other modules' sources. That was true of
`StatTypeFanOutTest` and `VersionIsNotADiscriminatorTest` as well, so **all three
architecture guards were silently skippable on an incremental build** — a gate that passes
because it never executed, which is DL-100's failure in a different costume. Verified by
reintroducing the defect: green and UP-TO-DATE before the input declaration, red after,
with no `--rerun-tasks`.

**Scope, deliberately.** `stats-input` still logs broker exceptions with their stacks. An
`AmqpConnectException` carries a host and a port, not a record, and the Writer is the only
module holding a database connection and a decoded payload at the same time.

**Cost.** An unexpected `RuntimeException` in the Writer now reaches the log as a type
chain rather than a stack. That is a real loss of diagnostic detail on the paths where the
cause is our own code rather than the database, and it was accepted: the alternative is a
rule with an exception in it, and the exception is what the next author copies.

### DL-125 — The tracker gets a sequence, because a backlog does not answer "what next"

**5 Sep 2026** · `HOUSEKEEPING` · DL-113…DL-124, DL-095, DL-105, D-12 P3 · T-035, T-037, T-047, T-060

**Decision.** Five changes, all recorded in `Stats_App_Tracker.xlsx` and reflected in
the working documents.

1. **A `Next Up` sheet.** Fourteen ranked rows across both owners, each carrying the gate
   that must close before it starts. The Tasks sheet stays the backlog; this sheet is the
   order. `claude/CLAUDE.md` §7, `PROJECT_STATUS_AND_PLAN.md` §3 and `README.md` all point
   at it rather than restating it.
2. **The fan-out starts at `sysstats`, not `ltestats`.** `T-034` is blocked on D-12 P3 —
   whether `imsi` is stored masked or raw and whether it may be a query predicate — and
   `T-035` is blocked on nothing. Its only sharp edge is `timespan` arriving as the string
   `"300"`, which is the T-062 lesson learned on the cheapest type.
3. **`T-037` (`netstats`) now depends on `T-033`, not `T-034`.** The old dependency made
   `netstats` inherit a block about a field it does not have. `netstats` is last because it
   is the `jsonb` hybrid the typed-column reference slice templates least well — a plan,
   not a prerequisite.
4. **The six missing decisions and four missing evidence rows were added** — DL-113,
   DL-114, DL-115, DL-116, DL-117 and DL-124 on the Decisions sheet, DN-41…DN-44 on the
   Done sheet. The tracker had recorded the ingest work of 5 September and none of the six
   decisions taken the same day.
5. **The fixture figures are corrected to 76 JSON fixtures and 88 assertions.** *(79 and
   91 since T-063 and T-064 on 8 September — DL-127, DL-128.)* The sheet
   said 77 and 87; `validate-fixtures.py` says 88 over 76, and `valid/` gained
   `lorastats-clsbox-lowercase.json` with DL-115. The stale `A1:H22` range on the Open
   Questions table — spanning fifteen deleted rows — was shrunk to the six that remain.

**Why a sheet rather than a paragraph.** The Tasks sheet answers *what is outstanding*
and answers it well: sixty-six rows with acceptance criteria, requirement IDs and
blockers. It does not answer *what do I open now*, and neither does ID order — T-034
precedes T-035 and must not be started first, T-060 precedes nothing and must wait for
T-063. Every reader was re-deriving that sequence from dependencies, priority and three
"blocked by" columns, and two of them derived it differently.

**Why the ordering corrections are decisions and not tidying.** A false dependency and a
true one are indistinguishable to whoever reads the row next. `T-037 → T-034` would have
held `netstats` behind a data-classification question about IMSI for as long as D-12 P3
stayed open, and the reason would have been invisible: the sheet would simply have shown
`netstats` as not startable. That is the same failure as a stale `OPEN` item — the
expensive kind, because someone eventually spends a day re-deciding it (DL-116's opening
paragraph makes the same point about a live properties file).

**What this deliberately does not do.** It does not change a single status. Nothing was
marked DONE that was not already DONE in the repository, and nothing was reprioritised
except by naming the gate that was already true. The one behavioural claim it makes —
that the fan-out may start at `sysstats` — follows from D-12 P3 blocking `ltestats` alone,
which DL-114 established on 4 September.

---

### DL-126 — The payload gate is built, and it is not configurable

**8 Sep 2026** · `ENGINEERING` · STA-03A, STA-12A, ADR-007 §5, DL-100, DL-122, DL-124 · T-057, T-064, T-063

**Decision.** `stats-writer` validates the per-type payload before the INSERT, through a
type-agnostic `PayloadGate` that parses the body once, calls
`StatsSchemaValidator.validate()`, binds the envelope and returns a `ValidatedRecord`. A
failure throws `PermanentFailureException`, so the delivery skips all three retry tiers
and reaches `app_statsstore.<type>.dlq` on its first attempt. Four things go with it:

1. **There is no property that turns the gate off.** `stats.writer.validation
   .intended-scope` is **deleted**, not promoted. It existed to record the decided design
   while the gate was unbuilt and was named "intended" precisely so it could not be
   mistaken for a live control (DL-100). ADR-007 §5 offers no second scope for a switch to
   select, and a key that can make "every payload reaches the INSERT unvalidated" true is
   a one-line change away from silently disabling a data-safety control.
2. **`StatsBatchWriter` takes `ValidatedRecord`, not `StatsEnvelope`.** The gate becomes a
   signature rather than a convention: a writer that accepted a bare envelope could be
   called with an unvalidated one. The schema label rides along into `schema_version`,
   which ADR-003 D7 wants to mean *the contract that admitted this row* — a compiled-in
   constant answers *the contract its author had in mind*, and the two agree only until a
   v2 exists, at which point every stored row claims the wrong one and nothing fails.
3. **The exception message is a constant.** `SafeFailure`'s own javadoc named
   `new PermanentFailureException("uid=… failed the schema")` as the thing T-057 was
   expected to write, and DL-124 makes it impossible for a throwable to reach a
   `stats-writer` log line. The diagnosis is published separately: a `WARN` carrying the
   `FieldError` list, which is value-free by construction — JSON Pointers, machine codes
   and reviewed `SafeMessage` constants — plus the record itself, intact, on the DLQ.
4. **`records_payload_rejected{type,reason}` is a new counter**, separate from
   `records_dead_lettered{type,reason}`. That one is tagged with the root exception class,
   which cannot separate "the payload does not match its schema" from "a table invariant
   was violated" — and those call for different responses: the first is an upstream
   conversation, the second is ours. **The `reason` tag was added on review, because the
   first cut made the same mistake one level down**: it counted a routing fault — a record
   whose own `type` is not the type of the queue it arrived on — under a name that says
   "payload". Both throw `PermanentFailureException`, so `records_dead_lettered` sees one
   class name for both and this counter is the only place the two stay distinguishable.
   The tag set is closed at three constants — `payload_schema`, `type_mismatch` and
   `unmappable` — so it cannot grow into a cardinality problem the way a free-text reason
   would.

   **`unmappable` was the third correction, and it came from the same mistake a third
   time.** `records_write_failed` is described as *"records whose write failed"* and its
   log line said `write FAILED` — but once `parameters()` was hoisted out of the
   transaction (see DL-127), a record in an unknown dialect never reaches PostgreSQL at
   all. Counting it as a write failure sends whoever watches that counter to a database
   that is behaving perfectly, while upstream quietly ships a shape nobody recognises.
   `LorastatsBatchWriter.write` now has **two stages with two failure meanings**: binding
   the row, which is payload work and is counted here, and the `INSERT`, which is the
   database's turn and keeps `records_write_failed` to itself. The regression test that
   pins it, `countsAnUnmappablePayloadApartFromAFailedWrite`, asserts both counters at
   once — asserting either alone passes for the wrong reason. `metersAFailedWrite` was
   rewritten at the same time: it used to prove this counter with a record missing a
   required member, which never reaches the database, so it agreed with itself while
   meaning two different things. It now uses a `ts_device` with no partition, which is a
   real rejection by PostgreSQL.

**Why.** The failure arm built on 5 September (DL-122) was real and idle: nothing produced
a permanent failure, so three retry tiers, a per-type DLQ and a confirmed hand-off existed
with no producer. The gate is the producer. It is also the second half of the split ADR-007
§5 decided in August, and the half that was missing was the one with teeth — until now
every payload reached the INSERT unvalidated and a mismatch surfaced as a *write* failure,
classified transient, which spent 35½ minutes in the tiers before reaching the DLQ anyway.

**Why the gate is type-agnostic.** Nothing in `PayloadGate` names a stat type. Rule 6
requires that adding a type be additive, and a gate written inside `LorastatsMessageProcessor`
becomes five copies of itself during the Phase 3 fan-out — at which point the fifth copy is
the one that quietly omits the check. The sysstats processor of T-035 is a sibling of the
lorastats one, not a copy of its validation.

**Why the body is parsed once.** The same tree is validated and then bound. Parsing twice
costs a second parse on each of 115.2m records a day, and — the part that matters — lets
the tree that passed and the object that was written drift apart, so the row stored would
not be the row that was validated.

**Rejected.** *Promoting `intended-scope` to a live key* — see rule 1; a scope switch on a
gate whose only correct value is "on" is a foot-gun with a config file for a trigger.
*Keeping `write(List<StatsEnvelope>)` and validating by convention* — the convention is
exactly what was missing for three weeks. *Putting the field pointers in the exception
message* — they are safe today, and the rule that stops a message reaching a log has to be
absolute or it is a judgement call made again at every new throw site. *Validating at
Input instead* — window 12 of `FLOW.md` §8: the record is already accepted, confirmed and
published, and Heimdal retries only on a non-2xx, so a 400 there is unrecoverable loss.

**Consequence.** T-064 hangs on this code path and lands with it. T-063's rules 3 and 4
both need `PermanentFailureException` to be a live route to the DLQ, which is why the
Tasks sheet recorded T-063 as depending on T-057 while `Next Up` ranked T-063 first: both
were right about different halves, and the two shipped together. **T-060 can now be sized**
— what reaches the lorastats DLQ from today is real failure, not routine dialect
disagreement. The Writer's ERROR line no longer claims a failed write "goes to the retry
arm": a permanent one does not.

---

### DL-127 — All three lorastats dialects are built, and the fourth still dead-letters

**8 Sep 2026** · `ENGINEERING` implementing `ANSWERED` (Q15a) · DL-105, DL-089 (superseded), ADR-007 §4 (amended), DL-113, DL-095 · T-063, T-060, T-062

**Decision.** DL-105 is implemented. `docs/schemas/lorastats-v1.schema.json` becomes a
`oneOf` of exactly three dialect branches, and `LorastatsDialects` normalises all three
into one canonical payload before `LorastatsBatchWriter` maps a single column. Each of
DL-105's four rules is now a mechanism rather than a sentence:

1. **Structural detection.** Which required key-set is present — bare or `lora_`-prefixed —
   and what type its values hold. `VersionIsNotADiscriminatorTest` (DL-113) was written
   before this code and forbids the new file from naming the bare identifier at all; it
   passes with **no new whitelist entry**, which was the acceptance criterion.
2. **One stored shape.** Proved in the table, not asserted in prose:
   `allThreeDialectsLandAsOneIdenticalRow` writes the same counters in three dialects
   against real PostgreSQL and compares the rows. The dialect is the metric label
   `records_lorastats_dialect{dialect}` — whose name is **composed** from
   `StatType.wireName()`, because a `"lorastats"` literal in main source fails
   `StatTypeFanOutTest` — and all three series are registered at startup so a dialect
   going to zero in the field is visible rather than absent.
3. **Range-checked coercion, in that order.** A dialect-B string is parsed to
   `BigInteger`, compared against `Long.MAX_VALUE`, and only then narrowed. Overflow or a
   bad parse dead-letters. Never zeroed, never truncated, never defaulted.
4. **Closed at three.** A fourth spelling matches no branch and dead-letters, in the
   schema and independently in the detector.

**The detail the prose loses, and the reason to read the fixture first.** Dialect C is
**not** "everything prefixed". The production record of 29 August carries fifteen
`lora_`-prefixed counters alongside a **bare** `fsk_received` and a **bare** `timespan`. A
branch or a detector written from DL-105's description rather than from the record rejects
the traffic it was written to accept. Both now encode the record.

**Normalisation happens before the column mapping, and that ordering is load-bearing.**
`data_extra` is computed from the *normalised* residue. Taken from the wire shape instead,
every dialect-C record would drop fifteen `lora_*` members into `data_extra`, leave the
typed columns NULL, and increment `records_unmapped_data_field` — a counter whose entire
job is to say "Heimdal added a field" — on ordinary traffic every ten minutes. The row
would be valid JSON, the write would succeed, and the defect would surface as a column
that is always null. `aPrefixedDialectRecordFillsColumnsAndLeavesDataExtraNull` is the
guard.

**One round trip is deliberately no longer byte-exact.** A dialect-B or dialect-C record
rebuilds from storage with bare names and integral values. ADR-004's byte-identical
guarantee is about **publication** — what Input republishes and what the retry arm hands
on, which is what keeps `(ts_device, uid)` suppression correct — and storage was always
value-preserving rather than encoding-preserving (ADR-003 D2). Rule 2 is what makes one
API shape possible for a type that arrives in three.

**The fixture gate learned a third marker.** `writer-range-reject` marks a record the
catalogue **admits** and the Writer refuses on the `BIGINT` range check. Neither existing
marker fits: `writer-dlq` means "fails its per-type schema" and this record does not.
It is to the Writer what `input-reject` is to Input — both mark a rule that is arithmetic
or cross-field rather than structural, and DL-105 rule 3 had no assertion vocabulary
until now. `EXPECTED_ASSERTIONS` moves 88 → **91** and the Java parity expansion 81 → **84**;
the marker count stayed at seven while its composition changed completely.

**Rejected.** *`anyOf` instead of `oneOf`* — the three branches are disjoint by name and
value type, so an overlap is a modelling error, and `oneOf` surfaces it as a validation
failure instead of silently picking the first match. *Typing dialect B's `timespan` as
either a string or an integer* — no production record exercises it, a dialect is
internally consistent by definition, and a mixed record is a shape nobody has explained;
rule 4 sends those to the DLQ where a person sees them. *Letting the detector fall back to
a "best guess" dialect* — a record neither the schema nor the detector can place belongs
on the DLQ, not in a column chosen by a fallback. *Stripping `lora_` from unrecognised
members* — renaming an unknown field on the way to a column that does not exist is
inventing a mapping; it keeps its spelling and goes to `data_extra` verbatim (D14b).

**Consequence.** Roughly **15m messages/day stop accumulating** in a queue ADR-002 §12
forbids ever expiring. The two production dialect fixtures move `invalid/` → `valid/` and
lose their `writer-dlq` markers; `invalid/lorastats-dialect-unknown.json` and
`valid/lorastats-dialect-string-overflow.json` take their place at the two boundaries the
widened schema creates. **T-060 is unblocked and can now be sized against real volume.**
T-062 inherits the coercion helper rather than writing a second one — `sysstats`'
`timespan: "300"` and `netstats`' 19-digit counter strings are the same rule applied to
other types, and two helpers written a month apart is how the range check goes missing
from one of them.

**Residual, unchanged.** Q15a's second half — *are the other dialects being retired?* —
is still unanswered and still blocks nothing. `records_lorastats_dialect{dialect}` is what
will show one going to zero, and it is now emitting.

---

### DL-128 — A non-OK upstream status is metered at the gate, and absence is not a failure

**8 Sep 2026** · `ENGINEERING` implementing `ANSWERED` (Q15d) · DL-108, DL-102, ADR-007 §1, D14 · T-064, T-050

**Decision.** DL-108 is implemented in `PayloadGate`, immediately after validation and
before the write: when `status` is present and is anything other than exactly
`["conversion ok"]`, increment `records_upstream_status_not_ok{type}` and log at `WARN`
with `uid`, `type`, `clsbox` and the status array. The record is **stored regardless** and
is never dead-lettered. `status` stays a passthrough member — this reads it to count it,
and nothing branches on it.

**An absent `status` is not counted, and that is the decision DL-108 left open.** Read
literally, "anything other than exactly `["conversion ok"]`" includes absence. But
`status` is optional, and every fixture written before 29 August 2026 omits it — so
counting absence would light the counter on ordinary traffic and drown the signal it
exists to carry. *"Upstream did not say"* and *"upstream said it failed"* are different
facts, and only the second is what Q15d asked for. Recording it here rather than leaving
it to the code is the point: it is exactly the kind of unstated default that DL-108's own
value-agnostic argument warns about.

**Why the comparison is against the one known-good value.** We do not have the vocabulary
of failure strings. A check that recognises only the values we happen to know about goes
**quiet** on the ones we do not, which is the failure mode the whole "nothing is silently
dropped" rule exists to prevent. Comparing against the single good value inverts it:
anything unrecognised is counted, by definition. `metersAStatusValueNobodyHasSeenBefore`
is the test that holds that property, using a value no production record has sent.

**Why it is at the gate rather than in the writer.** One place, once per delivery, with
the whole envelope in hand and before any transaction opens. Putting it in
`LorastatsBatchWriter` would repeat it five times across the Phase 3 fan-out, and putting
it after the INSERT would miss the records suppressed as duplicates.

**Rejected.** *Dead-lettering a non-OK status* — the record is valid and was correctly
delivered, and a redrive produces the identical verdict forever, in a DLQ with no TTL.
*A metric label carrying the status value* — an unbounded label set sourced from an
upstream vocabulary we do not control is a Prometheus cardinality bomb. *Waiting for the
vocabulary before metering* — the counter does not need it, and the counter is what will
name the actual values. *Logging the payload alongside it* — `status` is an **envelope**
member, which DL-108 names explicitly; the DL-102 level gate that keeps payloads at
`DEBUG` does not extend to it, and does not become weaker because of this line.

**Consequence.** T-050's alert set gains the counter — a Mukesh-owned hand-off, not a
change here. `valid/lorastats-status-not-ok.json` is a new fixture and carries no `$expect`
marker, because nothing anywhere rejects it. Without this we would store records upstream
already knew were bad with nothing on our side saying so, which is the whole of Q15d.

**Residual, unchanged.** The vocabulary of non-OK `status` values is still unknown. It
blocks nothing, the counter is value-agnostic, and by the next round with Hasan the
counter will have enumerated the real values in any case.

---

### DL-129 — Configuration is audited by the build, because a duplicate key is silent

**8 Sep 2026** · `ENGINEERING` · DL-100, DL-116, ADR-002 §12, ADR-003 D6, ADR-007 §1 · T-060, T-025

**Decision.** Six defects in `stats-writer`'s configuration and in two Gradle files are
fixed, and the one that could recur is now enforced by
`PropertiesDeclareEachKeyOnceTest`, which fails the build if any module's
`application.properties` declares a key twice.

| Defect | Why it mattered |
|---|---|
| **Four retry keys declared twice** — `tier1/2/3-delay` and `max-retries`, in two separate comment blocks | A `.properties` file silently keeps the **last** definition. The values happened to agree, so nothing misbehaved; tune the first occurrence and nothing changes, with no warning from the parser, Spring or the IDE |
| **`stats.writer.dlq.alert-depth.default=0` read by nothing** | A key that looks live is one someone edits expecting an effect. Exactly the hazard DL-100 named when it insisted the validation-scope key be called `intended-scope` — and the payload-gate key was deleted for that reason hours earlier, so leaving this one preserved the inconsistency the deletion was meant to remove |
| **Two wrong `DL-` citations** — T-057 cited DL-125, T-063 cited DL-126, both off by one | Introduced by the 8 Sep edit itself. A decision log is only navigable if its citations resolve |
| **The `MAX_PRE_CREATE_DAYS` comment sat above the cron** | It documents `pre-create-days`; as placed it read as a bound on `schedule` |
| **`stats-common/build.gradle.kts` called the device field `gateway`** | The SRS name that no production record has ever used. ADR-007 §1 makes the wire name the name everywhere, and `CLAUDE.md` §6 lists this as a trap — the build file was still teaching the wrong one |
| **`stats-writer/build.gradle.kts` called Flyway "PROVISIONAL pending Q1a"** | Q1a closed 18 Aug 2026. The same module's `application.properties` already said "final since Q1a closed", so one module contradicted itself in two files |

**Why a guard for the duplicate and not for the rest.** The other five are one-off
staleness, caught by reading. The duplicate is a different species: it is **invisible
by construction**. Nothing in the toolchain reports it, both halves were individually
correct, and it survived every review of a file whose own comments insist these values
are *"externalised here and NEVER hard-coded"*. That is the profile of a defect that
comes back, so it gets a test rather than a note.

**The guard needed a second fix to be real.** `stats-common`'s test task declared only
`src/main/java` as an input, so editing a properties file left `:stats-common:test`
`UP-TO-DATE` and the new guard would not have run — the identical trap the existing
`inputs.files` block was written for, one directory across. `src/main/resources` is now
declared too. The guard was then verified the way DL-124's was: the duplicate was
reintroduced, the build went red, and it was removed again.

**Rejected.** *Leaving the duplicate because the values agree* — that is the argument
for every latent defect, and it is only true until someone edits one of them.
*Renaming `alert-depth.default` to `intended-` something* — T-060 will introduce the keys
it actually reads; a placeholder that has to be renamed to stay honest is worth less than
the comment that replaced it. *A `.editorconfig` or lint rule* — this repository's
convention is a JUnit guard that fails the build (DL-113, DL-124), because that is what
runs in CI without anyone opting in.

**Consequence.** 483 tests. T-060 inherits a file with no misleading threshold in it, and
a guard that will fail if its new keys are ever added twice.

---

### DL-130 — Configuration metadata is generated, and eleven keys that bound to nothing are parked

**8 Sep 2026** · `ENGINEERING` · DL-100, DL-129, STA-23A, ADR-003, ADR-006 · T-025, T-060, Phase 4

**Decision.** `spring-boot-configuration-processor` is added to the three application
modules as an `annotationProcessor`, so each one generates
`META-INF/spring-configuration-metadata.json` from its `@ConfigurationProperties`
classes. Two live keys that are read *by name* rather than bound —
`stats.writer.stat-type` (`@Value` and `Environment`) and
`stats.retention.pre-create-on-startup` (`@ConditionalOnProperty`) — are described in a
hand-written `additional-spring-configuration-metadata.json`, because the processor
cannot see either.

**Why this is not cosmetic.** The symptom was an IDE marking every custom `stats.*` key
as an unknown property. The cost is not the squiggle: it is that a file in which **every
warning is false** is a file where a **true** warning is never read. And there were true
ones — eleven keys that bound to nothing at all:

| Module | Keys | Disposition |
|---|---|---|
| `stats-writer` | `stats.retention.default-days` | **Parked.** STA-23A requires it and 90 days is decided, but `RetentionProperties` binds only three components and T-025 has not been built |
| `stats-writer` | `delete-batch-size`, `fallback-schedule`, `max-batches-per-run`, `max-run-duration` | **Removed.** They configured the batched-`DELETE` path to take *"if partitioning is not approved"* — and it **was** approved. ADR-003 makes detach-and-drop the mechanism and `CLAUDE.md` §6 lists row-by-row deletion at this volume as a trap. They tuned a design the project decided against |
| `stats-input` | `stats.input.metrics.converter-ts-present` | **Removed.** A metric name is not configuration: it already exists as `IngestMetrics.CONVERTER_TS_PRESENT`, so this was a second copy of one truth, free to drift |
| `stats-api` | `stats.security.audience`, `read-role`, `tenant-claim`, `stats.api.default-page-size`, `max-page-size` | **Parked.** No `@ConfigurationProperties` class exists in that module, so all five resolved to nothing while reading as live security and paging controls. `read-role` is the worst of them to leave live-looking — it is the control that keeps tenant isolation working |

Parking follows this repository's own rule, stated at the top of
`stats-input/application.properties`: *a value that is decided but unread must fail
closed (absent) rather than fail open (a plausible-looking guess)*, and
`stats.retention.lorastats-days` was already commented out that way. The decided values
stay recorded; they simply stop pretending to be wired.

**The fix had a trap of its own, and it failed silently.** `annotationProcessor` is a
separate configuration and does **not** inherit the Boot BOM that the root build imports
into `implementation` and `testImplementation`. So the version-less catalog entry
resolved against nothing: `./gradlew dependencies --configuration annotationProcessor`
reported `FAILED`, the processor never ran, no metadata was generated — and the compile
still succeeded. The only symptom would have been an IDE that still called every property
unknown, i.e. no visible change at all, which is exactly how a fix gets declared done. The
root build now adds the platform to `annotationProcessor` too, and the outcome was
verified by reading the generated file rather than by the build going green: 15 described
properties in `stats-writer`, 10 in `stats-input`, and zero live `stats.*` keys left
undescribed in any module.

**Rejected.** *Suppressing the warnings in IDE settings* — that hides the false ones and
the true ones together, which is the whole problem. *Deleting the parked keys outright* —
`default-days` and the Keycloak values are decided answers, and re-deriving them costs
more than a commented line. *Keeping the fallback-`DELETE` keys "just in case"* — a
fallback to a rejected design is a new decision when it is needed, not four keys nobody
got round to removing. *A build guard asserting every key has metadata* — the prefixes are
not all string literals (`LorastatsConsumerProperties` uses a constant), so the check
would be brittle; with the noise gone the IDE is now itself the feedback loop, which is
what DL-129's duplicate-key guard could not rely on.

**Consequence.** 483 tests, configuration cache still reused. T-025 un-parks
`default-days`; Phase 4 un-parks the five `stats-api` keys and adds the
`@ConfigurationProperties` classes that make them real.

---

### DL-131 — The API's lorastats examples are real production records, shown post-normalisation

**8 Sep 2026** · `ENGINEERING` · DL-105, DL-127, D14b, ADR-007 §1, STA-35A · `openapi.yaml`, T-063

> **Renumbered 10 Sep 2026 (was a second `DL-130`).** Two entries were written on 8 Sep and
> both took the number 130. Every citation outside this file — three `build.gradle.kts`
> files and two `application.properties` — resolves to the *other* one (configuration
> metadata), so this entry is the one that moves and nothing else needed editing. It is the
> same defect DL-129 itself caught and fixed twice on the day it was written: a duplicate
> identifier that the file loads without a murmur, where the second definition is simply
> unreachable by reference.

**Decision.** Both `lorastats` examples in `docs/contracts/openapi.yaml` are now drawn
from the three production records of 29 August 2026 — one per dialect — rather than from
`valid/lorastats-valid.json`, the synthetic fixture that predates them. `LorastatsRecord`
mirrors `valid/lorastats-production-canonical.json`; `LorastatsPage` carries all three, in
`ts.device DESC, uid DESC` order, **as the API returns them**: dialect B's quoted counters
as integers and dialect C's `lora_`-prefixed names stripped, because that is what DL-127
does to them before the `INSERT`. Exactly one deviation from the source fixtures is
permitted, and it is now stated in the file: `tenant` and `maintenant` are made to differ.

**Why the old example was wrong without being invalid.** It carried the thirteen
**required** counters and nothing else — no `rx_short_pkt`, no `fsk_received`, no
`timespan`, no `version`, no `status`, and two of the five `ts` members. Every one of
those omissions is legal, and the composite is a record production has never sent. A
reader comparing the contract against a real payload concludes the contract is wrong about
the payload, which is the more expensive mistake here: the last time a document set
disagreed with production, production won and four of five payload schemas were rebuilt
(ADR-007). An example is the only part of a contract most people read.

**Why the page shows three dialects and not three copies of one.** The question this
answers is *"why does the response not look like what Heimdal sends?"*, and the answer is
only convincing side by side. A prefixed name or a quoted counter in a **response** example
would be worse than the thin example it replaced: it would describe a response this API
cannot produce, and invite a client that parses for `lora_rx_nr_pkts`. The dialect is a
metric label — `records_lorastats_dialect{dialect}` — and the examples are where that stops
being an abstract claim.

**Rejected.** *Adding the optional members to `valid/lorastats-valid.json`* — the
originally chosen option, withdrawn on inspection. That fixture is load-bearing **because**
it is bare: `StatsEnvelopeTest.omitsAbsentOptionalMembersRatherThanSerialisingThemAsNull`
proves `JsonInclude.NON_NULL` keeps a pre-29-Aug record from round-tripping with four null
members, which byte-identical publication depends on (DL-119), and
`PayloadGateTest.doesNotMeterAnAbsentStatus` needs a record with no `status` at all
(DL-128). Enriching it would have left both green while testing nothing — the silent
failure both guards exist to prevent. *A new production-shaped fixture* — unnecessary;
`valid/lorastats-production-canonical.json` already was one. *Editing only the YAML* —
breaks the rule stated above the examples block.

**Consequence.** `redocly lint` 2.51.2 clean; 91 fixture assertions, 0 failed; no fixture
added, moved or re-marked. The stale `$comment` on the canonical fixture — which still said
the other two dialects "dead-letter until then" — is corrected, and `RequestLimitTest`'s
two byte counts are re-measured (1,462 → 1,758; 425 → 429) since the fixture that comment
describes grew. `lorastats-valid.json` is untouched.

---

### DL-132 — Retention is detach-and-drop, and every run begins by repairing the last one

**10 Sep 2026** · `ENGINEERING` · ADR-003 D1/D3/D4, STA-23A, STA-23C, DL-096, DL-130 · T-025, T-031, T-051, T-054, T-055

**Decision.** `PartitionRetention` implements ADR-003 D1: for every daily partition wholly
older than the retention window, `ALTER TABLE … DETACH PARTITION … CONCURRENTLY` followed
by `DROP TABLE`. Never row deletion. It runs once daily on the existing
`stats.retention.schedule`, under the same `pg_try_advisory_lock` pre-creation already uses
(D3), and `stats.retention.default-days=90` is un-parked and bound at last (DL-130
predicted this).

Four things are decided alongside the mechanism.

**One maintenance pass, in one order.** `PartitionMaintenance.maintainOnSchedule()` runs
pre-creation and then retention, per type, and `PartitionMaintenanceScheduler` is the only
`@Scheduled` entry point. Two scheduled methods on one cron expression would have run in
whatever order Spring registered them, and the order is load-bearing: tomorrow's partition
is created before yesterday's is dropped.

**Every run finalises an abandoned detach first.** This is the part that is not in
ADR-003, because it is a property of PostgreSQL rather than of the design, and it was
found by probing PostgreSQL 17 before the class was written:

* `ALTER TABLE … DETACH PARTITION … CONCURRENTLY` **cannot run inside a transaction
  block** — a hard `ERROR`. `PartitionSession` forces auto-commit for it, and nothing in
  the sweep opens a transaction.
* An **interrupted** detach leaves `pg_inherits.inhdetachpending = true`, and from that
  moment **every** `DETACH CONCURRENTLY` on that parent fails with
  `partition "…" already pending detach`.
* Meanwhile `INSERT` and `CREATE TABLE … PARTITION OF` **keep working perfectly.**

So a run killed by a rolling deploy, an OOM or a statement timeout wedges retention for
that stat type *permanently*, ingestion looks completely healthy, and the only symptom is
disk. That is a twelfth entry in the shape of `FLOW.md` §8, and it is silent. Beginning
each run with `DETACH … FINALIZE` turns it into a cost of one day.

**The gap between the detach and the drop is swept.** After the detach the table still
exists, standalone, still holding its rows, with `relispartition` false. A crash there
leaks a day of storage that nothing would ever reclaim, so the sweep drops leftovers it
can name, date and place outside the window — counted as `retention_orphans_dropped`, its
own series rather than a tag, because a non-zero value means an earlier run *died* and an
ordinary drop counter would read as retention working.

**Nothing is dropped that has not been proved droppable.** The name must be one
`PartitionNaming` produced, the catalogue bound must be exactly the bound that name
implies, and the day must be wholly outside the window. A hand-made child, a name that
does not parse, a bound that disagrees — all logged and kept. Dropping is irreversible
short of a per-partition restore (D5), so the safe direction is to retain something that
could have gone, never the reverse.

**Why.** The alternative to partitioning was never on the table — `claude/CLAUDE.md` §6
lists row-by-row deletion at 115.2m records/day as a trap, and D1 chose
`RANGE (ts_device)` partly so retention could be a catalogue operation. What T-025 adds is
the discovery that the mechanism has a failure mode which *presents as nothing at all*,
and a class that assumed the previous run finished would have inherited it.

`RetentionProperties` gains three things and one of them is a change of shape.
`default-days` has **no default in code**: retention deletes data, so a deployment that
omits the key must fail to start rather than adopt a window somebody guessed — the same
reasoning that leaves `stats.input.auth.mode` undefaulted. Per-type overrides become
**`stats.retention.days-by-type.<wire-name>`**, replacing the recorded-but-never-live
`stats.retention.<type>-days`; the map is validated against `StatType.wireName()` at
startup, so a typo fails the context instead of silently reverting that type to 90 days.
That is DL-100, DL-129 and DL-130's shared lesson applied *before* the key goes live
rather than after. `stagger` implements D3's "stagger the five types" as an offset derived
from the **type**, so one mechanism covers both shapes: five deployments firing on one
cron in production, and five passes inside one process in `all` mode.

**Rejected.** *A `stats.retention.enabled` kill switch* — `schedule=-` already stops the
cron, and an unrequested off-switch on a mechanism ADR-003 makes mandatory is the
`intended-scope` hazard again (DL-100). *A cap on partitions dropped per run* — a detach
and a drop are catalogue operations, and a cap would leave partitions past the window
attached with nothing saying so. *Letting retention discover partitions by parsing
`pg_get_expr` alone* — the bound is verified, but the **name** is what makes a partition
recognisable as ours; accepting anything whose bound merely looks like a day would put a
hand-made table in scope. *Copying the lock and session protocol into a second class* —
D3 names one lock, and two spellings of `hashtext('stats.retention:' || …)` that drifted
would leave the two jobs mutually invisible, running concurrently and mostly getting away
with it. `PartitionSession` is extracted so the key cannot disagree with itself.

**Consequence.** 523 tests, 0 failures. `PartitionRetentionIntegrationTest` proves the
mechanism against real PostgreSQL 17 — including the pending-detach state, staged by
cancelling a detach that a second session is blocking, which is the only way to prove the
recovery exists. Every drop in that class runs through `DETACH CONCURRENTLY`, so a sweep
that ever acquired a transaction would fail the suite rather than degrade quietly.
**T-051, T-054 and T-055 are unblocked** — they measure what this built. `PartitionPrecreator`
keeps its public API, so T-012's tests are unchanged; `PartitionPrecreationScheduler` is
renamed `PartitionMaintenanceScheduler`, because a class that now runs retention as well
should not be named for half of what it does.

---

### DL-133 — A record past its window is skipped and ACKed, and the sweep and the guard share one cutoff

**10 Sep 2026** · `ENGINEERING` implementing `SIGNED` (D5b) · DL-112, DL-106, ADR-003 option B, STA-23B, ADR-002 §12 · T-026, T-031, T-050, T-052

**Decision.** D5b is implemented. `RetentionGuard` runs between the payload gate and the
write: a record whose `ts.device` is already past the retention window is **skipped**,
`records_expired_on_arrival{type}` is incremented, a `WARN` is logged carrying envelope
members only, and the delivery is **acknowledged**. It is **never dead-lettered**.

**The guard and the sweep read one cutoff, and that is the decision inside the
decision.** `RetentionWindow` is the single definition; `PartitionRetention` and
`RetentionGuard` both derive from it. Two independent calculations would fail in opposite
directions and only one of them noisily: a guard even a day more permissive than the sweep
admits a record into a partition retention has just dropped, the `INSERT` fails, the record
dead-letters, and every redrive re-derives the identical verdict forever in a queue
ADR-002 §12 forbids ever expiring. Written as one function, the disagreement is
unrepresentable rather than merely unlikely.

The arithmetic leans deliberately: `cutoffDay = today(UTC) − retentionDays`, a record is
expired when `ts.device < cutoffDay 00:00Z`, and a partition is droppable when its day is
*before* `cutoffDay`. The boundary belongs to the **retained** side on both sides of the
comparison. Over-retaining by one day costs one partition; under-retaining deletes records
still inside the window the business asked for, and nothing would report it.

**Why it returns `false` where `PayloadGate` throws.** The two gates differ in consequence,
so they differ in signature. A payload failure throws `PermanentFailureException`, which is
this module's channel to the DLQ. This one has to end in a broker ACK, and an exception is
the one thing that cannot produce one — `LorastatsConsumer` would hand it to
`FailedDeliveryRouter` instead. Making the two look alike is how the skip would eventually
acquire a dead-letter it must not have.

**Why the gate runs first and the guard second.** A record that is both too old *and*
malformed dead-letters, where the schema mismatch can be diagnosed, rather than being
discarded as merely late. The reverse order silently swallows the more informative of the
two failures.

**The five late-arrival fixtures were wrong, and quietly.** `edge/<type>-late-arrival.json`
each said the record is *"DLQ'd without retry"* — the opposite of the signed policy — and
that the policy was *"still pending product sign-off"*, which stopped being true on
4 Sep 2026. Worse, none carried an `$expect` marker, so all five asserted only "the schema
accepts this" and said nothing about a signed, deliberate data-loss path. That is precisely
the gap `input-reject` was invented to close for the clock-skew fixtures, where an undecided
question had "accept" baked into ten green assertions. A fourth marker, **`writer-skip`**,
now states it: schema-valid, accepted by Input, published, consumed, and then deliberately
not stored. Markers **7 → 12**, assertions **91 → 96**, and no fixture added, moved or
removed.

**Why "never dead-letter" is worth this much text.** The record is *valid*. A dead-letter
would describe it wrongly, occupy a queue with no TTL and manual disposition, and be
rejected on every redrive because the record can only have got older. It is also trap 18
read from the other end: **a DLQ message redriven after roughly ninety days lands in this
class, increments this counter and stores nothing** — a no-op that looks like a recovery.
The redrive runbook (T-052) has to say so outright.

**Rejected.** *A `DEFAULT` catch-all partition* (ADR-003 option A) — degrades pruning and
grows unwatched. *Clamping `ts.device` to the oldest live partition* (option C) — falsifies
the time series. *Silently dropping the record* — this is deliberate data loss by a signed
decision, which is exactly why it is metered and logged rather than quiet. *Rate-limiting
the `WARN`* — a flood of warnings about a systemic gateway backlog is the correct noise,
and a stateful suppressor is the kind of cleverness that hides the second incident.
*Putting the counter in `WriterGateMetrics`* — that class answers "what was wrong with this
record", and nothing is wrong with this one.

**Consequence.** 523 tests, 0 failures.
`LorastatsPersistenceConsumerIntegrationTest.aRecordPastItsRetentionWindowIsSkippedAndAckedRatherThanDeadLettered`
proves it end to end against a real broker and a real database: the counter moves, the main
queue drains, **the DLQ stays empty**, and no row is written. `RetentionWindowTest` asserts
the boundary at the exact second on both sides — the assertion T-031 asks for, alongside the
integration test that proves the same boundary through a dropped partition.
**The alert is not built.** The counter is exported and registered at zero from startup, but
nothing watches it; that is T-050, and it is the half of D5b that separates a systemic
backlog from a harmless one-off.

---

### DL-134 — Record correlation uses standard AMQP properties and scoped MDC

**7 Sep 2026, merged 10 Sep 2026** · `ENGINEERING` · D1, ADR-001, NFR-02, P1/P2, DL-124 · T-005

**Decision.** Input generates one `traceId` at the ingest boundary, returns it as
`X-Trace-Id`, and places it in AMQP `correlationId`; the validated record `uid` goes in
AMQP `messageId`. Writer restores both before parsing and preserves them on every retry
and DLQ copy. The record fields `uid`, `statType`, `tenant`, `clsbox` and `errorCode` live
in a scoped MDC, while each application log statement owns a bounded `event` key and
each module adds its stable `module` field through the structured JSON formatter.

**Why standard properties.** Custom `x-stats-*` correlation headers would create a
second message contract for values AMQP already names. Standard properties are visible
to broker tooling, survive hand-off without touching the byte-identical body, and can be
restored before a malformed body is parsed.

**Why placeholders exist.** Size, rate and malformed-JSON failures happen before a
record can be trusted. Those events use the bounded literal `unknown` for unavailable
record fields rather than copying unvalidated strings into logs or silently changing the
JSON shape.

**Enforcement.** `StructuredLoggingContractTest` requires an `event` field on every
application-owned log statement, the stable module formatter setting in all three apps,
and no direct body/payload/data expression handed to a logger. Runtime tests assert the
actual ECS line, correlation hand-off and context cleanup. P1 and P2 remain stricter:
`ltestats`/`netstats` payloads never reach a log at any level, and Writer failures use
`SafeFailure.describe()` rather than messages or stacks.

**On merge (10 Sep 2026).** Two things changed between the branch and `main`. This entry was
written as DL-126, a number `main` had meanwhile given to the payload gate (T-057); it is
renumbered here rather than leaving two entries under one ID — the failure DL-131 had just
repaired. And the move to the SLF4J fluent API would have silently switched off
`WriterLogsCarryNoThrowableTest` (DL-124): its regex recognised only
`log.error("…", e)`, so `log.atError().log("…", e)`, `.addKeyValue("cause", e)` and
`.setCause(e)` would all have passed the build while printing the same stack. The guard now
walks fluent statements with balanced parentheses and checks each sink's direct arguments,
so `SafeFailure.describe(failure)` still passes and a bare throwable does not; a self-check
asserts each shape it claims to catch. Every plain log statement that had landed on `main`
after this branch was cut — T-057's gate, T-025's retention and guard — was converted to
carry its `event` field, which is what `StructuredLoggingContractTest` requires of it.

---

### DL-135 — Database capability is proved by PostgreSQL, not application convention

**8 Sep 2026, merged 10 Sep 2026** · `ENGINEERING` · STA-32, ADR-006 · T-009

*Written as DL-127, renumbered on merge: `main` had meanwhile assigned DL-127 to the `lorastats` dialects (T-063).*

**Decision.** The PostgreSQL bootstrap explicitly states the `stats` owner role's
Writer/Flyway capabilities and grants `stats_ro` only `CONNECT`, schema `USAGE`, and
table `SELECT`. Default privileges apply both role contracts to tables and partitions
created later. `stats_ro` receives no sequence or temporary-table capability, cannot
inherit `stats`, and there is no `stats_input` database identity.

**Credential boundary.** The shared development `.env` remains the single local input,
but Compose overrides database variables to empty in `stats-input`, removes the Writer
credential from `stats-api`, and removes the API credential from `stats-writer`. A module
therefore receives only the database capability its architecture permits; merely
choosing another Spring datasource username cannot recover a stronger secret from the
process environment.

**Proof.** `DatabaseRolesIntegrationTest` starts PostgreSQL 17 with the exact production
init SQL, creates a table after bootstrap, and connects as both identities. `stats_ro`
must read that future table, while `INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, and `CREATE`
must each fail in PostgreSQL with SQLSTATE `42501`. `DatabaseCapabilityIsolationTest`
guards Input's absent database dependency/configuration and the Compose secret
overrides. The failure is consequently a database error, not a code-review promise.

**Boundary retained.** The official PostgreSQL image creates `POSTGRES_USER` as its
bootstrap/database owner, and PostgreSQL 17 refuses to demote that bootstrap identity in
its own init session. Separating a cluster administrator from `stats` would change the
approved one-name image/Writer setup, credential rotation, migration ownership, and
existing-volume upgrade procedure; that is a separate platform decision, not silently
introduced by T-009.

### DL-136 — Meter delivery attempts separately from committed inserts

**10 Sep 2026** · `ENGINEERING` · STA-27A, STA-25A, ADR-005 · T-027

*Written as DL-128, renumbered on merge: `main` had meanwhile assigned DL-128 to the
upstream-`status` metering (T-064).*

**Decision.** The listener counts `records_consumed{type}` before decoding and times each
attempt as `records_processing_duration{type}` through processing, any confirmed failure
hand-off and the ACK attempt — recorded on failure exits too, so a redelivery counts again.
Persistence increments `records_persisted{type}` only after the transaction returns with
an inserted row; a duplicate stays its own outcome. `records_postgres_errors{type}`
separates database-access and transaction failures from the broader
`records_write_failed{type}` by inspecting only exception *types* in a bounded cause
chain — no driver message becomes a label. Neither exception messages nor record
identifiers become metric labels.

The measurement boundaries are recorded in `docs/contracts/writer-metrics-v1.md`.

---

### DL-137 — Broker observations are polled and cached; a scrape performs no I/O

**10 Sep 2026** · `ENGINEERING` · STA-27A, NFR-05, DL-104, DL-132, DL-133 · T-027, T-050, T-060

*Written as DL-129, renumbered on merge: `main` had meanwhile assigned DL-129 to the
configuration audit.*

**Decision.** `OperationalMetrics` exposes per-type `queue_depth`, `dlq_depth` and
`consumer_availability` from passive AMQP queue inspection — no management-plugin
credential, no new dependency — read on `stats.metrics.poll-interval` and cached, so a
Prometheus scrape reads memory. A failed observation writes NaN, drops
`broker_metrics_collection_success` and leaves
`broker_metrics_last_success_timestamp_seconds` where it was: a queue the broker cannot
report on and an empty queue must never read the same, and an alert has to check freshness
before treating a zero as healthy. Replica observations describe one shared resource —
aggregate with `max`, never `sum`. `dlq_depth` is the series T-060 watches.
`retention_period_days{type}` is read from `RetentionWindow`.

**What the branch built and the merge removed — and why.** T-027 was cut before T-025 and
T-026 landed, and it carried its own answers to both:

* A second retention cutoff, `RecordRetentionPolicy`: `now − days` as an instant, with the
  boundary on the *expired* side ("at or before"), reading the retired
  `stats.retention.<type>-days` keys. DL-133 exists because two cutoffs that drift by a day
  admit a record into a partition the sweep has just dropped, and this one differed from
  `RetentionWindow` in both the day and the boundary direction. Dropped;
  `retention_period_days` reads the one definition instead.
* A late-arrival skip inside `LorastatsMessageProcessor`, and a `type != expectedType`
  check beside it. Both already exist on `main` as `RetentionGuard` (DL-133) and the
  `PayloadGate` `type_mismatch` reason (DL-126), in the order that matters — gate, then
  guard — so a late record that is *also* malformed dead-letters where it can be
  diagnosed rather than being discarded as merely late. Dropped as duplicates.
* `PartitionLagReader` and `retention_lag_seconds`, a catalogue query on a 30-second poll.
  DL-132 rejected exactly this in favour of the sweep-reported
  `retention_oldest_partition_age_days`: a polled gauge keeps reporting a healthy number
  from a replica whose sweep never ran, and it hard-coded the `public` schema. Dropped.

What survives is what T-027 was for: the broker gauges and the delivery/persistence
counters, which T-050 and T-060 need and which nothing on `main` provided. Main-queue
depth remains a diagnostic, not the oldest-message-age alert T-050 requires — under
jitter (DL-104) a cyclic depth spike is evidence the jitter answer is wrong, not the
buffer working.

---

### DL-138 — Manual DLQ redrive gets one processing attempt, never fresh retry tiers

**11 September 2026** · T-052 · STA-12A, ADR-002 §12

A dead-letter queue is inert: no application component consumes or redrives it
automatically. Records remain available with their original bytes and safe failure
metadata until an authorised operator explicitly redrives or disposes of them. A
redrive requires a change identifier, dedicated per-type credentials and an explicit
assertion that the root cause has been resolved.

The manual copy preserves `x-stats-attempt`, `x-stats-failure` and
`x-stats-permanent`, adds traceable redrive metadata, and receives one processing
attempt. `x-stats-manual-redrive=true` makes any repeat failure bypass all retry tiers
and return directly to the same type's DLQ. This marker is necessary because permanent
failures can reach the DLQ on attempt one; preserving only the attempt count would let
a transient repeat failure enter tier two.

The original DLQ delivery is ACKed only after the main-queue copy is publisher-confirmed.
The operation is bounded, per type and capped at 2,667 records/second. Records at or
beyond the 90-day device-time retention boundary are refused because their processing
outcome would be `expired_on_arrival`, not database recovery. The complete access,
audit, pilot, stop and verification procedure is `docs/runbooks/dlq-redrive.md`.

---

### DL-139 — The Datahub API guide is reconciled against this project: one glossary correction, four questions raised, nothing reopened

**12 Sep 2026** · `ENGINEERING` · ADR-006, ADR-007, DL-106, DL-108, Q8a-2 · T-034, T-038, T-050

**What was read.** `ZENNER_Datahub_API_Guide` v4.0 (11 Sep 2026, Naman Vashishtha) — the
consolidated reference for the platform the Stats App plugs into, cross-checked against the
Datahub OpenAPI spec and live instances. It is kept outside this repository
(`~/Desktop/work/Naman/Work/Documentation/APIs/`) because it describes ZC's platform, not
ours. Every field name it shows for `lorastats` and `ltestats` matches the schemas in
`docs/schemas/`; the envelope's `ts` object already accepts the extra members the platform
carries on measurement documents (`lns`, `parser`, `lnsnano`). **No schema, column, or
contract changes.** What follows is what did change, and what is now a question.

**Decision 1 — `maintenant` is the *original-owner* tenant, and the glossary said
otherwise.** Guide §1.3: every inventory object is born in the shared `uaa` tenant and
moved into a customer tenant with the tenant-change call; `maintenant` records where it
came from, and each move is appended to the object's `moved` audit array. `claude/CLAUDE.md`
§4 called it "the *managing* tenant", which is a guess from the SRS. Corrected in this
change. The isolation rule does not move an inch — `tenant` is still the only isolation
field (ADR-006) — but the trap gets sharper: in production `tenant=mzc, maintenant=uaa`
will be the *routine* case, not the exception. The existing fixture rule (the two values
must differ) already covers it; the reason is now the real one.

**Decision 2 — four questions are recorded, and none is answered here.** Each was
derived from the guide, not from ZC, and the guide's §9 documents *sensor* forwarding —
whether gateway statistics use the same path is not stated. So they are questions.

| Ref | Question | Why it matters | Owner |
|---|---|---|---|
| **Q8a-3** | Is the stats push to Input a Heimdal **ForwardRule**? Guide §9: a ForwardRule is `{url, type, id, maxRetry}`, transports `AMQP \| HTTP \| AMQP+ELEMENT \| MSSQL \| OCHP \| MQTT`, and *"credentials go in the URL"* | Two of our assumptions rest on the answer. **Auth:** credentials-in-URL is HTTP basic, which `stats.input.auth.mode=none\|mtls\|oauth2` (DL-120) cannot express — Q8a-2's "OAuth2 unless you object" may be proposing a mode Heimdal does not have. **Retry:** rule 8 says Heimdal retries every non-2xx; `maxRetry` says it retries a *bounded* number of times, so a sustained 503 burns a budget and the loss is on their side, invisible to us | ZC (with Q8a-2) |
| **D16** | A gateway that changes tenant keeps its pre-move rows under the *old* `tenant`. The new tenant's token cannot see them (ADR-006 puts `tenant` in every query). Is that the intended reading? | Ours to decide. Recommended position: **yes** — a row's `tenant` is the tenant at measurement time, exactly as Heimdal stamped it (ADR-007: no rename layer, no rewriting). The alternative, re-labelling history on a move, would require the Writer to read the inventory service, which is a dependency this project does not have and should not acquire. Record as an ADR-006 amendment when decided; until then it is a support question waiting to happen | Engineering |
| — | `status` vocabulary: measurement documents carry `["ok"]` (guide §1.8); stats records carry `["conversion ok"]` | Adds one known-good value to the DL-108 residual. If a stats record ever carries `["ok"]`, `records_upstream_status_not_ok{type}` lights on ordinary traffic. The counter will show it; T-050's alert threshold should not be zero until the first 24 h of ingest have enumerated the real values | Engineering (T-050) |
| — | `clsbox = FFFFFFFFFFFF` means *synthetic / no gateway* (guide §1.8) | Nothing to build: the `CHECK (clsbox ~ '^[0-9A-F]{12}$')` accepts it and it is a valid device identifier as far as this system is concerned. Recorded so an operator who sees it in a `WARN` does not open an incident | — |

**Two things the guide confirms rather than changes.** The platform already serves
`sysstats`/`lorastats`/`ltestats` through `POST /datastore/aggregate/pageable` with
`$match {clsbox, type, ts.device range}` and `$sort ts.device` (guide §7.3) — the same
query the `(tenant, clsbox, ts_device DESC, uid DESC)` index was designed for, and a
**parity oracle** for T-041/T-043: the same gateway and window fetched from both sides
must agree. And `/statistics/statisticevents` (guide §7.6) uses the field name `gateway`
and **milliseconds** — which is almost certainly where the SRS's wrong vocabulary came
from (ADR-007 §1, trap "the SRS field names are not the wire field names"). Consistency
across documents was never evidence; here is the document they were consistent with.

**One data point for D-12, not an answer.** The guide's `ltestats` example (§7.3) returns
`imsi`, `lac` and `cid` in the clear to any holder of the `datastore.read` scope. That is
the platform's current posture, and D-12 P3 is the question of whether ours should match
it. It is recorded here so the D-12 conversation starts from what the platform does today
rather than from a blank page.

