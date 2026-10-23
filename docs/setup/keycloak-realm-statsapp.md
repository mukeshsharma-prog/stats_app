# Keycloak dev realm — `keycloak-realm-statsapp.json`

Notes for the importable dev realm. **This prose used to live in `_comment` keys
inside the JSON itself. It cannot: Keycloak's `RealmRepresentation` and
`UserRepresentation` both reject unknown fields, so a single `_comment` key makes
`--import-realm` fail and puts the container in a restart loop.** Keep commentary
here; keep the JSON strictly to what Keycloak accepts.

Proposed values, pending ZC and security confirmation.

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

## The six fixture users exist for specific tests

Their usernames, emails and tenant ids are all variables, so every name in this
realm is ours to change from `.env` — but the SRS and the isolation tests refer to
them by the default names, so rename in `.env` and in those tests together.

| User | Proves |
|---|---|
| `zra-reader` | Reads `zra_test`; A/B/C isolation demonstration, including mismatched `maintenant` |
| `alpha-reader` / `beta-reader` | The cross-tenant isolation pair |
| `no-role` | An authenticated caller without the read role gets 403 |
| `no-tenant` | A token with **no** tenant claim fails CLOSED rather than returning everything |
| `empty-tenant` | A token with a **present but blank** tenant claim also fails closed |

`no-tenant` and `empty-tenant` are deliberately **separate users** and separate
code paths. An absent claim never means "all tenants"; an empty claim passes a
naive `claim != null` check and then filters on an empty string, which matches
nothing — or worse, is treated as unset somewhere downstream. One user cannot
exercise both, and each of them is a release gate. `empty-tenant`'s empty
tenant attribute is deliberately **not** a variable — the empty string is the
thing under test.

## The ingest service account

The `stats-input` client is confidential, with the service account enabled and an
audience mapper that puts `stats-input` in `aud`. Its service-account user holds the
realm role `stats.ingest`. That is the identity Input checks in
`stats.input.auth.mode=oauth2`: a client-credentials token from this client, validated
for issuer, audience, expiry and the `stats.ingest` role.

The seed import runs only for a missing realm, so a realm created before the service
account was added does not have the role. Assign it once in the admin console: **Clients →
stats-input → Service account roles → Assign role**, filter by realm roles, select
`stats.ingest`.

To fetch an ingest token on the development stack, with `STATS_INPUT_CLIENT_SECRET`
taken from `.env`:

```sh
set -a; . ./.env; set +a
curl -s -X POST http://localhost:8080/realms/statsapp/protocol/openid-connect/token -d grant_type=client_credentials -d client_id=stats-input -d "client_secret=$STATS_INPUT_CLIENT_SECRET" | jq -r .access_token
```

## Persistent users and profile configuration

The Compose stack mounts `keycloak-data:/opt/keycloak/data`. Seed import runs only
for a missing realm. Existing users, credentials and signing keys survive restarts
and recreation; `down -v` deletes the volume. Before replacing an older container,
run `docker/migrate-keycloak-data.sh` to preserve its stopped H2 database.

The realm embeds the profile configuration from `keycloak-user-profile.json`,
including the single `tenant` attribute with administrator-only view/edit permissions.
Keep the embedded configuration and readable JSON synchronized. This declaration is
necessary for newly created users: an undeclared attribute may be discarded by
Keycloak's user-profile handling. For existing realms, add the attribute through
Realm settings → User profile, preserving existing profile configuration.

Users can be created through the admin console or Admin REST API. Assign
`stats.readOnly`, set `tenant`, configure their credentials and obtain a fresh token.
No PostgreSQL user or grants are created for them. `docker/token.sh USER --prompt`
or `--password-stdin` works without fixture credentials in `.env`.

The public development login client also enables Authorization Code with mandatory
PKCE S256 and a configurable explicit callback. The password grant remains enabled
for development scripts; it cannot complete interactive required actions or MFA.

Keycloak references: [startup import behavior](https://www.keycloak.org/server/importExport)
and [user-profile permissions](https://www.keycloak.org/docs/26.0.8/server_admin/#user-profile).
