# Stats App API — OpenAPI 3.1 Design Draft

**Status:** `CONVERTED — design input only, see openapi.yaml`  
> **`openapi.yaml` now exists** (21 Aug 2026) and is the authoritative HTTP contract.
> This file is retained as the design input it always was. Where the two differ, the
> YAML wins and records why in a comment: it takes **Unix seconds** rather than
> RFC 3339 (ARD v1.1 §8.2), **required** rather than optional `from`/`to`, and
> `limit` rather than `pageSize`. Item 1 of §6 below is done; items 2–5 are not.

**Base path:** `/v1`  
**Security:** Keycloak OAuth2/OIDC bearer JWT. ADR-006 is **ACCEPTED**: audience `stats-api`, read role `stats.readOnly`, tenant claim `tenant` (single-valued String). ZC's confirmation of those names (Q8b-2) is a config change.

This document is the human-readable design input for the machine-readable `openapi.yaml` that must be committed before API controllers are implemented.

## 1. API principles

The API is tenant-scoped and GET-only. A normal caller’s tenant is derived from the validated access token; there is **no caller-controlled `tenant` query parameter**. Every operation has a mandatory deterministic sort and stable cursor pagination. The V1 type set is `lorastats`, `ltestats`, `sysstats`, `omsstats`, and `netstats` — all five have strict schemas, `netstats` included since Q4a on 18 August 2026.

## 2. Endpoints

| Method | Path | Purpose | Required role |
|---|---|---|---|
| GET | `/v1/stats/{type}` | Retrieve a single approved stat type for the authenticated tenant. | `stats.readOnly` |
| GET | `/v1/stats/{type}/{uid}` | Retrieve one record of a type for the authenticated tenant. | `stats.readOnly` |
| GET | `/v1/openapi` | Optional rendered/schema discovery endpoint if platform convention supports it. | `PENDING` |
| GET | `/actuator/health/liveness` | Service liveness; platform/internal exposure only. | Platform policy |
| GET | `/actuator/health/readiness` | Dependency readiness; platform/internal exposure only. | Platform policy |

The `{type}` path parameter is an enumeration: `lorastats`, `ltestats`, `sysstats`, `omsstats`, `netstats`. An unsupported type returns 400 `unknown_stat_type` rather than falling through to a table name — and the envelope schema now carries the same closed enumeration, so an unrecognised type is rejected at validation rather than surviving to routing. `netstats` response data follows its strict schema (Q4a, 18 August 2026).

## 3. List operation

### `GET /v1/stats/{type}`

| Parameter | Location | Type | Required | Meaning |
|---|---|---:|---:|---|
| `type` | path | enum | Yes | Stat type. |
| `from` | query | RFC 3339 UTC date-time | No | Inclusive start of the approved query timestamp. |
| `to` | query | RFC 3339 UTC date-time | No | Inclusive end of the approved query timestamp. |
| `clsbox` | query | string | No | Exact canonical gateway filter. |
| `cursor` | query | opaque string | No | Keyset page continuation token. |
| `pageSize` | query | integer | No | Default 100; hard maximum 1000, pending approval. |

The `tenant` is supplied by the token, not by this endpoint. The approved query timestamp is `ts.device`. The default ordering is newest first by `ts.device, uid`, which is deterministic and supports keyset pagination. `from` and `to` apply to `ts.device` and their boundary inclusivity must be frozen in the approved OpenAPI.

| Condition | HTTP status | Problem code / behavior |
|---|---:|---|
| Valid query / results exist | 200 | `StatsPage` response. |
| Valid query / no visible data | 200 | Empty `items`; no cross-tenant disclosure. |
| Unknown type | 400 | `unknown_stat_type`. |
| Invalid timestamp / malformed cursor / invalid page size | 400 | `invalid_parameter`. |
| `from` is after `to` | 400 | `invalid_time_range`. |
| Range exceeds approved maximum | 400 or 422 | `query_window_exceeded`; exact status pending approval. |
| No/malformed/expired token | 401 | `unauthorized`. |
| Authenticated but lacks role | 403 | `forbidden`. |
| Unsupported HTTP method | 405 | `method_not_allowed`. |

## 4. Single-record operation

### `GET /v1/stats/{type}/{uid}`

The repository query must use both the token-derived `tenant` and the `uid`. A record belonging to another tenant must not be returned. **Decided (ADR-006, STA-35B): a cross-tenant identifier returns `404`, identical in every observable respect — status, body, headers, timing class — to a `uid` that does not exist at all.** The API must not be usable to probe whether a record exists in another tenant. This is a named release gate.

## 5. Success schemas

### `StatsPage`

```json
{
  "items": [
    {
      "uid": "2deb420f-56c8-452e-bdfd-53c9d455a9f9",
      "tenant": "<caller-visible tenant>",
      "maintenant": "<original-owner tenant — usually uaa; never the isolation field>",
      "clsbox": "04B648102511",
      "type": "lorastats",
      "ts": {
        "server": "2026-08-13T10:15:30Z",
        "device": "2026-08-13T10:15:20Z"
      },
      "data": {}
    }
  ],
  "nextCursor": "<opaque cursor or null>",
  "pageSize": 100
}
```

The API returns timestamps as RFC 3339 UTC strings unless the approved Datahub convention requires Unix seconds. The inbound payload contains Unix seconds; serialization belongs to the API contract and must be documented/tested. The query and retention basis is `ts.device`. Whether `tenant` is returned to a tenant-scoped caller is an API privacy decision pending consumer/security review.

### `ProblemDetails`

```json
{
  "type": "https://<host>/errors/invalid-parameter",
  "title": "Invalid request parameter",
  "status": 400,
  "detail": "The 'from' timestamp must be earlier than or equal to 'to'.",
  "instance": "/v1/stats/lorastats",
  "errors": [
    { "field": "from", "code": "after_to", "message": "Must not be later than 'to'." }
  ]
}
```

Problem responses must never contain access tokens, full stored records, IMSI, or identifiers that reveal another tenant’s data.

## 6. Contract requirements before implementation

1. Convert this document into `openapi.yaml` using OpenAPI 3.1.
2. ~~Define security scheme, issuer/audience/roles, and tenant claim.~~ **Done — ADR-006 is ACCEPTED.** Realm `statsapp`, audience `stats-api`, read role `stats.readOnly`, tenant claim `tenant` single-valued String; all fail-closed with no defaults in code. ZC confirmation (Q8b-2) is a config change, not a code change.
3. Define type-specific `data` schemas through `$ref` to the approved JSON Schema catalogue.
4. Freeze parameter names, timestamp formats, error codes, ordering, cursor encoding, and compatibility policy with consumer review.
5. Generate controller/client contract tests from the approved contract.
