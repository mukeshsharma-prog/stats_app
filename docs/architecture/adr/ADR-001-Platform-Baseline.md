# ADR-001 — JVM and Spring Platform Baseline

**Status:** `APPROVED`
**Decision owner:** Platform engineering
**Date:** 13 August 2026

## Context

The requirements specify a multi-module Gradle project and `application.properties`, but do not select Java or Kotlin, a JDK, Spring Boot version, Gradle DSL, or reference project. These choices determine code shape, dependency management, test support, deployment, observability, secrets, and maintainability.

## Decision required

| Decision item | Approved value |
|---|---|
| Language | **Java 21** |
| JDK | **Java 21** |
| Spring Boot | **3.5.16** — pinned as a single entry in `gradle/libs.versions.toml`. **Pinned, not provisional** *(G6, 18 Aug 2026; confirmed by the Q1a answer the same day)*. Module build files never declare their own versions |
| Gradle DSL |  Kotlin DSL |
| Build wrapper | Gradle Wrapper only; no globally required Gradle version. |
| Test stack | JUnit, Spring Boot Test, Testcontainers for PostgreSQL/RabbitMQ, and contract tests—subject to platform policy. |

## Q1a is closed — there is no reference repo

Java 21 is selected and Spring Boot is pinned at 3.5.16 in the version catalog (G6). **Q1a was answered on 18 August 2026: there is no internal reference repo.** This was never a waiting game; there was nothing to wait for. The pin is therefore **final, not provisional** — there is nothing left to reconcile it against. It remains a single catalog entry so that a future platform standard is a one-line change, which is why no module build file may declare its own version.

## Conventions are now ours to define

With no reference project to inherit from, CI, logging, secrets, and deployment each need a deliberate choice rather than a copy. These were decided on 18 Aug 2026 (`docs/decisions/2B-ANSWERS-18AUG2026.md` §1):

| Convention | Decision |
|---|---|
| Build | Gradle Wrapper only, Kotlin DSL, version catalog in `gradle/libs.versions.toml`; no module declares its own versions |
| Logging | Structured JSON to stdout, one event per line; `traceId` and `uid` on every record; **full payloads only at `DEBUG`, which is off in production** — never at `INFO` or above on any path, and no tokens or secrets at any level. *Qualified 4 Sep 2026 by DL-102*: this row previously read "no payloads on the success path", which was silent about the failure path and was then read as permission. `docs/development/DEVELOPMENT.md` §7 is the authoritative rule |
| Secrets | Environment variables injected by the platform; never in `application.properties`, images, or Git. The dev stack uses literal dev-only values that are obviously fake |
| CI | `./gradlew build` plus Testcontainers integration tests on every push; the build fails on a failed schema-fixture assertion |
| Deployment | Three images, one per application module, tagged with the Git SHA; immutable |
| Migrations | Flyway (ADR-003 D6), no longer provisional |

**Consequence to accept:** these conventions are ours to maintain, and a later ZC standard may force a migration. That is strictly better than blocking Phase 1 on a document that does not exist.
