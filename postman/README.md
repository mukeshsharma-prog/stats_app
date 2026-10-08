# Stats App Postman tests

Import `stats-app.postman_collection.json` and
`local.postman_environment.json` into Postman. Copy the blank password values
from the generated `.env` file, select **Stats App — Local**, and run the
collection in order.

The preferred automated path needs no manual secret copying:

```bash
./docker/run-postman.sh
```

Compose injects the generated credentials into the one-shot Newman container.
The collection uses unique IDs and current Unix timestamps on every run. Its
read-by-UID requests retry briefly until the asynchronous Writer transaction is
visible, then the remaining list and device queries verify all API route shapes.

The suite covers:

- readiness for Input, Writer, and API;
- Keycloak tokens for valid, missing-role, and missing-tenant callers;
- both `/internal/stats` and `/external/stats`, including the `accepted` JSON
  acknowledgement and its trace ID matching the `X-Trace-Id` header;
- `lorastats`, `sysstats`, and `omsstats` end to end;
- list, UID, device-from, and bounded device-range reads;
- 400, 401, 403, 404, and unauthenticated 405 behavior;
- all 15 queues for the three completed types, quorum durability, consumers,
  drain state, and empty DLQs;
- positive Writer persistence counters for every completed type.


A successful ingest returns HTTP 200 (or configured 202), for example:

```json
{"status":"accepted","traceId":"9fdc149d-1f12-48a8-b5b3-f57d8f9b4adc"}
```

This confirms RabbitMQ acceptance. Writer persistence is asynchronous; use the
polling GET requests to inspect the stored record. This collection requires the
6 October 2026 acknowledgement change or later (v4 bundle); the older v3 image
returns an empty body. Re-import this collection into Postman to load the new tests.
