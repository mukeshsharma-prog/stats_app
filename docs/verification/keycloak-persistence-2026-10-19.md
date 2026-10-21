# Keycloak user persistence verification

Verified on the separate `stats-zra-check` Compose project. Existing application volumes were not used.

A temporary user was created through the Keycloak Admin REST API (the same backend used by the console), with tenant `zra_test`, role `stats.readOnly`, and a generated password held only in memory. No `.env` entry or PostgreSQL grant was added.

Initial verification found that undeclared tenant attributes were discarded. The test realm was updated with an administrator-only `tenant` profile field; the final realm seed now includes it.

Admin request sequence: `POST /admin/realms/statsapp/users` → `201`, `GET /admin/realms/statsapp/roles/stats.readOnly` → `200`, `POST /admin/realms/statsapp/users/{id}/role-mappings/realm` → `204`. Credential bodies and tokens are omitted.

## PASS: user survives restart

```sh
docker restart stats-keycloak
```

After readiness, the following helper was run with the generated password on stdin:

```sh
./docker/token.sh console-check-8dfba671 --password-stdin --claims
```

Response body (decoded token claims; helper exited 0):

```json
{
  "tenant": "zra_test",
  "aud": [
    "stats-api",
    "account"
  ],
  "roles": [
    "default-roles-statsapp",
    "stats.readOnly",
    "offline_access",
    "uma_authorization"
  ]
}
```

## PASS: user survives recreate

```sh
docker compose -p stats-zra-check --env-file .env -f docker/docker-compose.yml up -d --no-build --force-recreate --wait keycloak
```

After readiness, the following helper was run with the generated password on stdin:

```sh
./docker/token.sh console-check-8dfba671 --password-stdin --claims
```

Response body (decoded token claims; helper exited 0):

```json
{
  "tenant": "zra_test",
  "aud": [
    "stats-api",
    "account"
  ],
  "roles": [
    "default-roles-statsapp",
    "stats.readOnly",
    "offline_access",
    "uma_authorization"
  ]
}
```

The temporary user was deleted after verification (`DELETE /admin/realms/statsapp/users/{id}` → `204`).

Named-volume deletion was not tested: `down -v` intentionally deletes the Keycloak database.

## PASS: console-managed user can call the read API

A second temporary user was created through the Admin API with tenant `zra_test` and role `stats.readOnly`, without any `.env` entry or PostgreSQL grant.

Token obtained using `./docker/token.sh console-api-check-37d81290 --password-stdin` with its generated password supplied on stdin.

GET `http://localhost:8083/v1/stats/lorastats/ZRA-44a36ce0c455-A` using that bearer token → HTTP 200.

```json
{"uid":"ZRA-44a36ce0c455-A","tenant":"zra_test","maintenant":"zra_test","clsbox":"808A47270048","type":"lorastats","version":1,"ts":{"server":1792383691,"device":1792383691,"converter":1792383691,"datastore":1792383691},"status":["conversion ok"],"data":{"rx_nr_pkts":219,"rx_crc_missing":0,"rx_crc_bad":48,"tx_success":0,"tx_failed":0,"tx_peek":0,"tx_peek_index_zero":0,"tx_override":0,"tx_busy":0,"tx_dequeue_error":0,"tx_type_beacon":0,"chip_comm_error":0,"util_fopen_failed":0,"rx_short_pkt":166,"fsk_received":0,"timespan":1800}}
```

## PASS: legacy database migration and final seed

A separate disposable Keycloak 26.0.8 container was started from the final realm seed
on port 18080 without a data mount. `zra-reader` received the expected tenant and role.

```sh
./docker/migrate-keycloak-data.sh stats-kc-migration-check
```

The command exited 0, copied the stopped H2 database to a private local backup and
populated `stats-kc-migration-check_keycloak-data`. After replacing that container
with one mounting the copied volume:

```sh
KEYCLOAK_BASE_URL=http://localhost:18080 ./docker/token.sh zra-reader --claims
```

The helper exited 0 with:

```json
{"tenant":"zra_test","aud":"stats-api","roles":["stats.readOnly"]}
```

Admin API checks confirmed the administrator-only tenant profile permissions and
the development login client's Standard flow and PKCE S256 configuration survived
migration. Full A/B/C checks also passed after the primary test Keycloak recreation.

Temporary test containers were removed after verification; their named volumes and
the private migration backup were retained. Existing application volumes were untouched.
