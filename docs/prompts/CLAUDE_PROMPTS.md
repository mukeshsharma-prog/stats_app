# Claude Code Prompts — Stats App

Use these prompts in the Claude Code VS Code extension with **Plan** mode selected. Replace bracketed values. Do not paste real production tokens, credentials, personal data, IMSI, or production payloads into prompts.

## 1. Repository orientation

```text
Read @claude/CLAUDE.md, @claude/CODE_START_CHECKLIST.md, and all files in @docs/contracts/.
Do not edit anything. Summarize the module boundaries, non-negotiable requirements,
current PENDING APPROVAL decisions, and the safest Phase 1 work that can begin now.
Flag document conflicts instead of resolving them yourself.
```

## 2. Decision review before coding

```text
Read @claude/CLAUDE.md, @claude/CODE_START_CHECKLIST.md, and @docs/architecture/adr/[ADR-FILE].
We want to implement [FEATURE]. Do not write code yet.

Create a plan with: (1) STA/NFR/ADR references, (2) required approved inputs,
(3) modules/files to change, (4) failure paths, (5) security/tenant impact,
(6) tests, and (7) documentation updates. Mark any PENDING APPROVAL item that blocks work.
Wait for my approval.
```

## 3. Implement a narrow requirement slice

```text
The plan is approved. Implement only [FEATURE] for [STAT TYPE OR MODULE].
Read @claude/CLAUDE.md, @docs/requirements/[SRS AMENDMENT], @docs/architecture/adr/[ADR-FILE],
@docs/contracts/[CONTRACT-FILE], and the relevant tests first.

Constraints:
- Satisfy [STA/NFR IDs].
- Do not modify handling for existing stat types.
- Do not add dependencies without explaining why.
- Do not make assumptions where the documents say PENDING APPROVAL.
- Add focused unit/integration/contract tests.
- Run the focused tests and report their exact command/result.

Before editing, restate the files you will change and wait for my approval.
```

## 4. Create a test plan

```text
Read @claude/CLAUDE.md and [FEATURE FILES]. Do not modify code.
Create a test matrix for [STA IDs]. Cover success, invalid input, dependency failure,
security, tenant isolation with tenant != maintenant, pagination/retention if relevant,
and observability. Identify which tests are unit, integration, contract, or load tests.
```

## 5. Tenant-isolation review

```text
Review [FILES OR PR DIFF] against @claude/CLAUDE.md and @docs/architecture/adr/ADR-006-Tenant-Isolation-and-API-Security.md.
Do not edit. Identify every data read path, count, cursor, aggregation, and error path.
For each, state whether the tenant comes only from the validated token and whether the filter is applied in the database query. Look specifically for tenant/maintenant confusion and existence leaks.
```

## 6. RabbitMQ/reliability review

```text
Review [FILES OR PR DIFF] against @claude/CLAUDE.md, @docs/architecture/adr/ADR-002-AMQP-Topology.md,
@docs/architecture/adr/ADR-005-Delivery-and-Idempotency.md, and @docs/contracts/amqp-topology-v1.md.
Do not edit. Check publisher confirmation, persistence, queue isolation, ACK timing,
duplicate outcome, retry limits, DLQ behavior, graceful shutdown, and metrics. Identify
any path that could silently lose an accepted record.
```

## 7. API-contract implementation review

```text
Read @claude/CLAUDE.md and @docs/contracts/openapi-v1-design.md, then review [API FILES].
Do not edit. Compare routes, parameters, status codes, error bodies, time format,
sort, pagination, security, and GET-only behavior to the contract. List exact mismatches
and classify them as breaking, non-breaking, or PENDING APPROVAL.
```

## 8. Debug a failing test safely

```text
A test is failing: [TEST NAME / OUTPUT]. Read @claude/CLAUDE.md, the failing test, and the smallest relevant implementation files.
Do not edit first. Explain the likely cause, requirements impacted, and the smallest safe fix.
Do not weaken tenant security, validation, retries, or tests merely to make the test pass.
Wait for approval before changing files.
```

## 9. Pre-merge review

```text
Perform a pre-merge review for [BRANCH OR DIFF]. Read @claude/CLAUDE.md and all relevant ADRs/contracts.
Check requirement traceability, backward compatibility, API contract changes, dependency additions,
configuration/secrets, tenant isolation, failure visibility, idempotency, tests, documentation,
and migration/operational impact. Do not edit. Produce blocking findings first, then non-blocking improvements.
```

## 10. Prompt anti-patterns

Do **not** use prompts such as “build the complete stats app”, “make it production ready”, or “fix all tests” without explicit scope. Those prompts conceal requirements gaps and encourage Claude to invent security, topology, or API behavior. Use the staged prompts above and approve plans/diffs in small increments.
