# Stats App Postman tests

Two ways to run this collection.

**Automated (Newman, no secrets to copy).** From the bundle directory:

```bash
./docker/run-postman.sh
```

Expect 40 requests and 100 assertions, 0 failed, on a freshly started stack. Compose
injects the credentials from `.env` into a one-shot Newman container. On a developer
checkout the same suite runs against the dev stack with
`docker compose -p stats-app --env-file .env -f docker/docker-compose.yml --profile apps --profile test run --rm postman-tests`
from the repository root.

**By hand, in the Postman app.** `local.postman_environment.json` ships with **blank**
passwords, because every installation generates its own into `.env`. Don't type them in:
the bundle `README.md`, section *The Postman desktop app*, has one command that writes
`filled.postman_environment.json` from `.env` (passwords, tenant and user names), plus
the request-by-request walk and its troubleshooting. In short:

1. Import `stats-app.postman_collection.json` and `filled.postman_environment.json`.
2. Select **Stats App — Local (filled)** at the top right. Every `{{…}}` comes from it.
3. **Authenticate alpha reader → Send.** Its script stores the token as `alphaToken`,
   which every read request sends. Tokens last 15 minutes.
4. Send the POST, then the matching GET (each POST stores the uid its GET reads), then
   folder 4's security checks, or **Run collection** for all 100 assertions.

Editing the environment file after importing it changes nothing in the app: re-import
it, or edit the variable's **Current value** in Postman and **Save**. A password must be
the one in `.env`. Postman cannot change it, Keycloak can only change it from `.env`
(bundle `README.md` → *Credentials*), and anything else is `invalid_grant`.

The collection runs in order.
The collection uses unique IDs and current Unix timestamps on every run. Its
read-by-UID requests retry briefly until the asynchronous Writer transaction is
visible, then the remaining list and device queries verify all API route shapes.

The suite covers:

- readiness for Input, Writer, and API;
- Keycloak tokens for valid, missing-role, and missing-tenant callers;
- both `/internal/stats` and `/external/stats`, including the `accepted` JSON
  acknowledgement and its trace ID matching the `X-Trace-Id` header;
- all five types (`lorastats`, `sysstats`, `omsstats`, `ltestats`, `netstats`) end to end;
- list, UID, device-from, and bounded device-range reads;
- 400, 401, 403, 404, and unauthenticated 405 behavior;
- all 25 queues for the five completed types, quorum durability, consumers,
  drain state, and empty DLQs;
- positive Writer persistence counters for every completed type.


A successful ingest returns HTTP 200 (or configured 202), for example:

```json
{"status":"accepted","traceId":"9fdc149d-1f12-48a8-b5b3-f57d8f9b4adc"}
```

This confirms RabbitMQ acceptance. Writer persistence is asynchronous; use the
polling GET requests to inspect the stored record. This collection requires the
6 October 2026 acknowledgement change or later (v4 and later bundles); the older v3 image
returns an empty body. Re-import this collection into Postman to load the new tests.

LTE/netstats support requires the v5 bundle. LTE checks include measured, 2G and
unknown-technology records. The primary LTE/network requests now match the data
keys and JSON types in the 9 October 2026 live exports, with synthetic identifiers
and addresses. LTE has no timespan in that sample; network has healthy eth0 and
wwan, including WWAN driver and string mtu. Degraded WWAN, extra interfaces,
unknown members and exact 19-digit counters remain covered by regression fixtures
and integration tests. The immutable v5 archive predates this example update.
LTE identifiers follow the current
string contract for local evaluation; D-12 still gates merge/shared release.
