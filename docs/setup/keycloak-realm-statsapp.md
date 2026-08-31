# Keycloak dev realm — `keycloak-realm-statsapp.json`

Notes for the importable dev realm. **This prose used to live in `_comment` keys
inside the JSON itself. It cannot: Keycloak's `RealmRepresentation` and
`UserRepresentation` both reject unknown fields, so a single `_comment` key makes
`--import-realm` fail and puts the container in a restart loop.** Keep commentary
here; keep the JSON strictly to what Keycloak accepts.

Proposed values pending ZC/security confirmation of Q8b — see
`docs/decisions/2B-ANSWERS-18AUG2026.md`.

> **DEVELOPMENT REALM ONLY. Never reuse any value here in a deployed environment.**

## Placeholders

Every env-placeholder in the file — dollar-brace, `env.` prefix, colon, fallback —
is resolved at import time from an environment variable, falling back to the
literal after the colon when it is unset. Values come from the repository-root
`.env` (gitignored; `.env.example` is its committed twin). The fallbacks are the
previous hard-coded literals, so the file still imports a complete working realm
with no `.env` present — a missing variable degrades to the dev value instead of
producing a broken realm.

## Verify the substitution actually happened

Placeholder support has varied across Keycloak versions, and the failure mode is
silent: an unresolved placeholder is imported as the literal placeholder text, so
`alpha-reader`'s tenant becomes that raw string — **and the cross-tenant isolation
test still PASSES**, because A's raw string still differs from B's. After the
first import, check the realm name and one user's tenant attribute in the admin
console before trusting any test result.

## The five users exist for specific tests

Their usernames, emails and tenant ids are all variables, so every name in this
realm is ours to change from `.env` — but the SRS,
`docs/decisions/2B-ANSWERS-18AUG2026.md` and the isolation tests refer to them by
the default names, so rename in `.env` and in those tests together.

| User | Proves |
|---|---|
| `alpha-reader` / `beta-reader` | The cross-tenant isolation pair |
| `no-role` | An authenticated caller without the read role gets 403 |
| `no-tenant` | A token with **no** tenant claim fails CLOSED rather than returning everything |
| `empty-tenant` | A token with a **present but blank** tenant claim also fails closed |

`no-tenant` and `empty-tenant` are deliberately **separate users** and separate
code paths. An absent claim never means "all tenants"; an empty claim passes a
naive `claim != null` check and then filters on an empty string, which matches
nothing — or worse, is treated as unset somewhere downstream. One user cannot
exercise both, and ADR-006 release-gates each of them. `empty-tenant`'s empty
tenant attribute is deliberately **not** a variable — the empty string is the
thing under test.
