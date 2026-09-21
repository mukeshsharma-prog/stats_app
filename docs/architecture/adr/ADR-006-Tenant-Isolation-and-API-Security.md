# ADR-006 — Tenant Isolation and API Security

**Status:** `ACCEPTED` — amended 17 September 2026 (D16, DL-142) and 21 September 2026 (device-scoped reads and the external ingest door, DL-151 / DL-152)
**Decision owner:** Security owner / API owner / Product owner
**Requirements:** STA-05A, STA-31A, STA-32A, STA-34A, STA-34B, STA-35A, STA-35B

## Context

The SRS requires Keycloak protection and server-side tenant separation. Every
record carries a tenant; that is confirmed, not assumed.

Records carry both `tenant` and `maintenant`, and the supplied samples very often
set them to the same value. **This is the highest-risk trap in the project.** An
implementation that filters on `maintenant` passes every test written against
those samples and leaks data in production. Isolation is on `tenant`. Always.

## Decision

1. The isolation field is **`tenant`**, never `maintenant`. `maintenant` is the
   maintaining tenant: it is set to the same value as `tenant` and, when a device is
   moved on the Datahub, both fields take the new tenant name (DL-139). It has no
   security meaning. *(Clarified 12 Sep 2026: because the two are equal on every real
   record, a filter on the wrong one is invisible to production data — only the
   deliberately-differing fixtures can catch it.)*
2. Every request derives its effective tenant **only** from a validated Keycloak
   token claim. A tenant value supplied by the caller is never trusted.
3. The tenant predicate is applied in the **database query** — every query, count,
   cursor, and any future aggregate (STA-35A). Never filter across tenants in
   application memory.
4. Controllers accept no `tenant` override in query parameter, header, path, or
   body on any normal request path.
5. A cross-tenant identifier returns **404**, identical in every observable
   respect — status, body, headers, timing class — to a `uid` that does not exist
   at all (STA-35B). The API must not be usable to probe whether a record exists
   in another tenant.
6. A token with **no** `tenant` claim is rejected with **403**. An absent claim
   never means "all tenants". A token with an **empty** `tenant` claim is rejected
   the same way. Both are fail-closed by construction, not by a default value.
7. Every accepted record must carry a valid tenant. A missing tenant is a
   validation failure (400) and is never stored as a placeholder.
8. The API is **GET-only**. POST, PUT, PATCH, and DELETE return 405 with no side
   effect (STA-32A). An authenticated caller lacking the reader role receives 403;
   an unauthenticated caller receives 401.
9. Privileged cross-tenant or support access is **not in scope** until separately
   specified with its own role, audit design, rationale, and security approval. It
   is a separate approved role, never a widened claim.

## Keycloak configuration — proposed by engineering, live in the dev realm

Q8b was answered on 18 August 2026 by building the realm rather than waiting.
These are **our proposal**, live and working in dev, pending ZC confirmation
(Q8b-2). A working realm is committed at `docs/setup/keycloak-realm-statsapp.json`.
Full reasoning: `docs/decisions/2B-ANSWERS-18AUG2026.md` §4.

| Input | Proposed value | Note |
|---|---|---|
| Realm | `statsapp` | A Datahub Core realm, if one exists, replaces it by configuration |
| Issuer | `http://localhost:8080/realms/statsapp` (dev); the real HTTPS issuer in production | Environment-owned, validated on every token. **`iss` is compared as a string, never fetched** — so one issuer value stays correct whether a module runs on the host or inside compose. A containerised module gets `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` pointed at `http://keycloak:8080/realms/…/certs` so it can download signing keys over the compose network; setting that also disables OIDC discovery, which would otherwise try to GET the issuer URL and fail. Leave it unset on the host and in production. |
| API client | `stats-api` — confidential resource server, no flows enabled | The API only validates bearer tokens; it never initiates a login |
| Ingress client | `stats-input` — service account, client credentials | Ready for Heimdal auth when Q8a-2 closes |
| Dev helper | `stats-dev-cli` — public, direct access grants | **Dev realm only** |
| Audience | `stats-api` | Rejecting a token minted for another audience is what stops a neighbouring service's token being replayed here |
| Read role | `stats.readOnly` (realm role) | Not the bare `readOnly` placeholder from the v1.0 answer |
| Ingest role | `stats.ingest` (realm role) | For `stats-input` when Heimdal auth arrives |
| Tenant claim | **`tenant`, single-valued String** | Mapped from the user attribute of the same name |

**Why the tenant claim is single-valued.** A multi-valued claim turns the isolation
predicate from `tenant = :claim` into `tenant IN (…)`. One misconfigured user then
reads several tenants and every test still passes. Single-valued makes the
dangerous case unrepresentable. Multi-tenant access, if it is ever needed, is a
separate approved role with its own audit design.

Configuration properties, fail-closed with **no defaults in code** so that ZC's
answer is a config change:

```properties
spring.security.oauth2.resourceserver.jwt.issuer-uri=
stats.security.audience=
stats.security.tenant-claim=tenant
stats.security.read-role=stats.readOnly
```

## Input authentication is deferred, not decided

Q8a was answered **"deferred by decision"**: build Input without Heimdal auth for
now. This is a scoping decision, not an oversight, and it has a consequence that
must stay visible — **until ZC answers Q8a-2, anything that can reach the Input
port can write.**

Auth is therefore a config-selected filter, not an assumption baked into code:

```properties
stats.input.auth.mode=none          # none | mtls | oauth2 — no default in code
stats.input.auth.required-role=stats.ingest
```

Three guards are mandatory: the property has **no default in code**, so a
deployment that omits it fails to start; a `WARN` is logged on **every** startup
while the mode is `none`; and a test **refuses to start the application under the
`prod` profile** with `stats.input.auth.mode=none`. Without that third guard,
`none` reaches production the first time someone copies a dev config.


## Row-level security — considered and **not adopted for V1** (24 August 2026)

The ARD proposes PostgreSQL RLS with `SET LOCAL app.tenant_id` per transaction.
It was recorded as "adopted" but never built, and it is **not** what this ADR
specifies. Settling it rather than leaving it ambiguous:

**Decision: the mandatory query predicate stays the V1 control. RLS is deferred.**

RLS is genuinely attractive as defence in depth — it would catch a query somebody
forgot to scope. But `SET LOCAL` binds to a *transaction*, and the API runs on a
pooled connection. Every read path would have to open a transaction and set the
GUC before the first statement, forever, including paths a future developer adds.
Miss it once and the session inherits whatever the previous transaction left, or
falls back to the role's default — and the failure is **silent and fails open**,
which is strictly worse than the predicate it was meant to protect. A control
whose own failure mode is invisible cross-tenant reads is not defence in depth.

What carries the isolation in V1 instead, all of it already specified above: the
tenant predicate is mandatory in every query, count and cursor (decision 3); it is
verified **against the generated SQL** rather than the response body (mandatory
tests); the claim is single-valued so `tenant IN (…)` is unrepresentable; and the
`alpha-reader`/`beta-reader` pair is a release gate.

**Revisit when either becomes true:** a second service gains direct read access to
these tables, so the predicate is no longer enforced in one reviewable place; or
an audit requires isolation demonstrable at the database rather than in
application code. Either one is a new ADR, with the transaction discipline
designed rather than assumed.

## Database roles — one per module, enforced by `GRANT`

Formalising what the dev stack already does, because "only Module 2 writes"
(rule 2, STA-32) should fail loudly rather than depend on nobody making a mistake:

| Role | Module | Grants |
|---|---|---|
| `stats` | `stats-writer` | `SELECT, INSERT` on every stat-type table; `CREATE`/`DROP` for partition maintenance and Flyway |
| `stats_ro` | `stats-api` | `SELECT` only. **No `INSERT`, `UPDATE`, `DELETE` on anything** |
| — | `stats-input` | **No database role at all.** Input holds no datasource; it never touches PostgreSQL |

A write attempted from the API is then a database error, not a code review miss.
`stats-input` having no role is the strongest form of rule 2 available: the
capability does not exist to be misused.

## Required platform inputs still outstanding

| Input | Status |
|---|---|
| ZC confirmation of realm, client, issuer, audience, role names, tenant claim | **Q8b-2** — proposal above is live; confirmation is a config change |
| Heimdal inbound auth mechanism: mTLS or OAuth2 client credentials, issuer, audience, client identity, rotation | **Q8a-2** — deferred by decision; **must close before any shared deployment** |
| Token clock-skew and refresh behaviour | Deployment configuration |

## Mandatory security tests

Tenant isolation is a security control, so these gate the release rather than
merely accompanying it.

* A valid token with the reader role and tenant A reads **only** tenant A records.
* **A fixture where `tenant != maintenant` proves only `tenant` controls
  isolation.** Every fixture in `docs/schemas/fixtures/` differs in the two fields
  for exactly this reason — a test built on equal values proves nothing. The one
  exception is `valid/netstats-supplied-sample.json`, kept verbatim as ZC sent it,
  where the two are equal: **never use that file as an isolation fixture.** Realm
  users `alpha-reader` and `beta-reader` are the cross-tenant pair.
* Cross-tenant lookup by `uid` returns 404, indistinguishable from not-found
  (STA-35B). This is a named release gate.
* A token without `stats.readOnly` receives 403. Realm user: `no-role`.
* A token with **no** `tenant` claim receives 403. Realm user: `no-tenant`, which
  carries no `tenant` attribute at all.
* A token with an **empty** `tenant` claim receives 403. Realm user:
  `empty-tenant`. These are two users on purpose — absent and empty are different
  code paths, and a naive `claim != null` check passes the second one and then
  filters on an empty string. One fixture user cannot exercise both.
* A missing, invalid, or expired token receives 401.
* A request cannot override tenant by query parameter, header, path, or body.
* Every query path, count, cursor, and future aggregate includes the tenant
  predicate — verified against the generated SQL, not only the response body.
* POST, PUT, PATCH, DELETE return 405 with no database side effect.
* The application refuses to start under the `prod` profile with
  `stats.input.auth.mode=none`.

## Decisions register

| Decision | Resolution | Status |
|---|---|---|
| Isolation field | `tenant`, never `maintenant` | **DECIDED** |
| Tenant source | Validated token claim only; no caller override | **DECIDED** |
| Cross-tenant response | 404, indistinguishable from not-found | **DECIDED (STA-35B)** |
| Missing/empty tenant claim | 403, fail-closed, never "all tenants" | **DECIDED** |
| Tenant claim shape | `tenant`, single-valued String | **PROPOSED (Q8b)** — live in dev |
| Realm / clients / roles / audience | `statsapp`; `stats-api`, `stats-input`, `stats-dev-cli`; `stats.readOnly`, `stats.ingest` | **PROPOSED (Q8b)** — awaiting Q8b-2 |
| Input authentication | Config-selected filter, no default, `prod` refuses `none` | **DEFERRED BY DECISION (Q8a)** — Q8a-2 must close before shared deployment |
| Privileged cross-tenant access | Out of scope until separately specified | **DECIDED** |
| Tenant-move history (D16) | A row's `tenant` is the tenant at measurement time, as Heimdal stamped it. Pre-move rows stay under the old tenant; the new tenant sees the gateway from the move onward. No re-labelling, no inventory dependency | **DECIDED 17 Sep 2026 (DL-142)** |
| Device-scoped read paths `/{type}/{clsbox}/{from}[/{to}]` | Tenant from the token, predicate in the query, served by `(tenant, clsbox, ts_device DESC, uid DESC)`. A foreign or unknown `clsbox` is an **empty 200** — a list, not a lookup; a 404 would disclose another tenant's gateway. No cross-tenant lookup by MAC | **DECIDED 21 Sep 2026 (DL-151)** — contract only, T-067 |
| Tenant binding on the external ingest door | `/external/stats` shares the internal door's authentication and binds **nothing** to the record's `tenant`. Per-door `stats.input.auth.<door>.mode` plus a *token tenant = record tenant* check land with Q8a-2 | **OPEN — T-068, BLOCKED on Q8a-2 (DL-151 residual).** Do not expose the external door anywhere shared before it closes |
| Row-level security (`SET LOCAL app.tenant_id`) | **Not adopted for V1.** `SET LOCAL` on a pooled connection fails open and silently; the mandatory query predicate is the control | **DECIDED 24 Aug 2026** |
| Database roles | `stats` (write) for the Writer, `stats_ro` (SELECT only) for the API, **none** for Input | **DECIDED 24 Aug 2026** |

## Amendment — 17 September 2026 (D16, DL-142)

**Context.** A gateway can be moved between tenants over its life: the Datahub
`tenant/change` call sets both `tenant` and `maintenant` to the new name and appends to
the object's `moved` audit array. Records arriving after the move carry the new tenant;
rows this system wrote *before* the move carry the old one, and decision 3 puts `tenant`
in every query — so the new tenant's token cannot see the gateway's pre-move history, and
the old tenant's still can. Raised as D16 on 12 September 2026 (DL-139).

**Decision — ratified 17 September 2026 by Naman Vashishtha: that is the intended
reading.** A row's `tenant` is the tenant at measurement time, exactly as Heimdal stamped
it. The new tenant sees the gateway from the moment of the move onward; the old tenant
keeps what was measured while the gateway was theirs. A new tenant requesting a `clsbox`
that has only pre-move rows receives **404**, indistinguishable from not-found (STA-35B).

**Why.** ADR-007 forbids rewriting the record, and `tenant` is the isolation field — the
last column this system should ever alter. Re-labelling history on a move would require
the Writer, or a maintenance job, to read the inventory service's `moved` array: a second
upstream and an `UPDATE` of the isolation column across live partitions, neither of which
this project has or should acquire. And it would leak in the other direction — the *new*
tenant would receive data measured while the gateway belonged to someone else, which is
the exposure this ADR exists to prevent.

**Consequences.** No code change. `maintenant` is not an escape hatch — it moves with
`tenant` (DL-139) and carries no pre-move information. *(Precision added 18 Sep 2026,
DL-146: on every tenant but minol the two are equal on every live record; on minol
roughly one record in ten carries `tenant: minol-gateway` with `maintenant: minol`.
Neither case makes `maintenant` a filter — the first cannot reveal a wrong filter, the
second gives a wrong filter a different wrong answer.)* Privileged cross-tenant access
remains out of scope, as the register says; this amendment does not reopen it. The
support answer to "the gateway moved to us and its history is missing" is *by design*,
and the Datahub's `moved` array — not this system — is the record of the move.

## Amendment — 21 September 2026 (DL-151, DL-152)

**Context.** Management asked on 20 September for two things this ADR has a view on:
a second `stats-input` endpoint for callers other than Heimdal Core, and a read path by
MAC address and timestamp. DL-151 designed both; this amendment records what each does
to tenant isolation, and settles one sentence of the 17 September amendment that the
new read path would otherwise contradict.

**Decision 1 — the device-scoped read paths are tenant-scoped exactly as the list is.**
`GET /v1/stats/{type}/{clsbox}/{from}` and `…/{from}/{to}` derive the tenant from the
token (decision 2), put it in the query (decision 3), and are served by the
`(tenant, clsbox, ts_device DESC, uid DESC)` index that ADR-003 already carries.
`{clsbox}` *is* the MAC address and keeps its wire name (ADR-007 §1). A `clsbox` the
token's tenant does not own — or that does not exist at all — returns an **empty `200`
page**, byte-identical in both cases. It does **not** return 404: these paths are a
*list*, and on a list a 404 would say "this gateway exists, elsewhere" — the probe
decision 5 exists to prevent (STA-35A). Cross-tenant lookup by MAC was raised on
17 September and **rejected**; the quiz that preceded the build (DL-005) closed it.

**Precision to the 17 September amendment.** That amendment says a new tenant requesting
a `clsbox` that has only pre-move rows "receives 404, indistinguishable from not-found
(STA-35B)". That is the answer for the `uid` lookup, `/{type}/{uid}`, which is a
single-record lookup and keeps decision 5's 404. For the device-scoped paths the same
request is an empty `200`, for the reason above. Both are "indistinguishable from
not-found" — each in its own operation's vocabulary. Nothing about D16 changes: the
pre-move rows stay under the old tenant on every operation.

**Decision 2 — the external ingest door adds a tenant-isolation gap on the *write* side,
and it is recorded as open, not closed.** `/external/stats` is bound to the same handler
and the same filter chain as `/internal/stats`, by decision: the split is for routing
and ingress restriction, not a second authentication mechanism, because nothing exists
to implement one against until Q8a-2 selects Heimdal's. The consequence for this ADR is
that decision 2 — *tenant from the token, never from the caller* — has no counterpart on
writes. It never needed one: the only writer was Heimdal, which forwards every tenant.
An external caller is different in kind. Anything that can reach `/external/stats` can
today submit a record under **any** tenant's name, and it will be validated, confirmed,
published, stored and read back by that tenant's token with every counter green. Once
Q8a-2 lands, an external caller would be presenting Heimdal's credentials.

The closing change is **T-068**: a per-door `stats.input.auth.<door>.mode`, and a
*token tenant = record tenant* check on the external path only (Heimdal's door keeps
forwarding every tenant). Both are a filter plus a property — the seam is now a path.
Until then the only protection is an ingress rule that keeps `/external/stats` off any
shared network, and the `source="external"` label on every Input metric so that a write
through that door is at least visible. **The external door must not be exposed anywhere
shared on the strength of "same authentication as Heimdal's".**

**Consequences.** No change to any read-side decision. Two register rows added. The
`tenant != maintenant` fixture rule of §6 gains a write-side sibling for T-068's test:
a record whose `tenant` differs from the token's tenant claim (and from its own
`maintenant`) is rejected on the external door and accepted on the internal one.
