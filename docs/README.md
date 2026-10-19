# Documentation index

Active documentation is organized by purpose. Architectural decisions, normative
requirements, operational procedures, current plans and verification evidence are
retained here. Dated records keep their original context; a completed implementation
milestone does not automatically close a release or external approval gate.

## Architecture and specifications

| Location | Contents |
|---|---|
| [Architecture decisions](architecture/adr/) | ADR-001 through ADR-007: platform, AMQP, retention/indexes, OMS timestamps, delivery/idempotency, API security and wire format |
| [Execution flow](architecture/FLOW.md) | Runtime path, failure exits, startup and delivery-loss analysis |
| [Architecture diagram](architecture/Stats_App_Flow.png) | End-to-end system diagram |
| [Architecture & Requirements Design v1.1](architecture/Datahub_Core_StatsApp_ARD_v1_1.pdf) | Retained design source; later approved amendments and repository ADRs govern conflicts |
| [Requirements](requirements/) | SRS, BRD and ARB v1.2 implementation amendments |
| [Baseline requirement sources](requirements/source/) | Original SRS/BRD/ARB documents, retained for provenance rather than current implementation instructions |
| [Contracts](contracts/) | Read/ingest OpenAPI, AMQP topology, metrics and contract validation tools |
| [Schemas](schemas/SCHEMA_CATALOGUE.md) | Runtime JSON Schema catalogue and schema/fixture validation |
| [Fixtures](schemas/fixtures/MANIFEST.md) | Production, boundary, isolation, invalid and malformed test inputs |

## Decisions and active development

| Document | Purpose |
|---|---|
| [Decision log](decisions/DECISIONS.md) | Complete dated decision history and rationale; all existing DL entries retained |
| [Answer reconciliation](decisions/ANSWER_RECONCILIATION.md) | Source answers and implementation consequences |
| [Ratified 18 August answers](decisions/2B-ANSWERS-18AUG2026.md) | Approved configuration and engineering decisions |
| [PostgreSQL baseline](decisions/POSTGRESQL_BASELINE.md) | Persistence design and sizing |
| [Data sensitivity inventory](decisions/DATA-SENSITIVITY-INVENTORY.md) | Field classification and handling references |
| [Original decision sources](decisions/source/) | Ratified answers and original internal decisions |
| [Historical 29 August register](decisions/OPEN_QUESTIONS_STATUS_29AUG2026.md) | Dated decisions and question reconciliation; not the current task list |
| [Active project plan](development/PROJECT_STATUS_AND_PLAN.md) | Current status, remaining work, owners and release gates |
| [Project context](development/PROJECT_CONTEXT.md) | Domain, invariants, known traps, open questions and traceability |
| [Implementation rules](development/IMPLEMENTATION_RULES.md) | Code, data, security, verification and contribution rules |
| [Development guide](development/DEVELOPMENT.md) | Build, configuration, tests and troubleshooting |
| [Engineering playbook](development/ENGINEERING_PLAYBOOK.md) | Procedures for extending types/APIs, testing and instrumenting behavior |
| [Code/release checklist](development/CODE_START_CHECKLIST.md) | Approved sequencing and release evidence |
| [LTE/netstats implementation record](development/LTE_NETSTATS_IMPLEMENTATION_PLAN.md) | Completed implementation plan, verification record and remaining external dependencies |

## Setup, operations and evidence

- [Keycloak realm setup](setup/keycloak-realm-statsapp.md) and [user profile](setup/keycloak-user-profile.json).
- [VS Code setup](setup/VS_CODE_SETUP.md).
- [Docker bundle guide](../docker/BUNDLE.md) and [manual walkthrough](../docker/MANUAL.md).
- [Queue troubleshooting](runbooks/queue-troubleshooting.md).
- [Retention policy changes](runbooks/retention-policy.md).
- [Manual verification](runbooks/manual-verification.md), [DLQ redrive](runbooks/dlq-redrive.md), [partition restore](runbooks/partition-restore.md), [burst load](runbooks/load-test-burst.md), and [replay](runbooks/replay-drill.md).
- [Verification evidence](verification/), including [LTE/netstats](verification/lte-netstats-2026-10-09.md), [tenant isolation](verification/zra-isolation-2026-10-19.md) and [Keycloak persistence](verification/keycloak-persistence-2026-10-19.md).
- [Setup handover](handover/Stats_App_v3_Setup_and_Inspection.html) and [historical decision narrative](handover/Stats_App_Project_History_and_Decisions.html).
- [Monitoring](../ops/prometheus/README.md), [dashboards](../ops/grafana/README.md) and [operational drills](../ops/drills/README.md).

## Historical path references

Historical decision entries, applied SQL migrations and production fixtures can still
mention the original `claude/` paths. Their current equivalents are:

| Historical location | Current location |
|---|---|
| `claude/CLAUDE.md` | [Project context](development/PROJECT_CONTEXT.md) |
| `claude/DECISIONS.md` | [Decision log](decisions/DECISIONS.md) |
| `claude/FLOW.md` | [Execution flow](architecture/FLOW.md) |
| `claude/PROJECT_STATUS_AND_PLAN.md` | [Active plan](development/PROJECT_STATUS_AND_PLAN.md) |
| `claude/DEVELOPMENT.md` | [Development guide](development/DEVELOPMENT.md) |
| `claude/SKILLS.md` | [Engineering playbook](development/ENGINEERING_PLAYBOOK.md) |
| Other moved documents | See [cleanup record](development/DOCUMENTATION_CLEANUP.md) and its relocation manifest |

Historical citations are preserved rather than rewriting applied migrations or test
fixtures. The source schemas, application code and SQL migrations were not changed by
this reorganization.
