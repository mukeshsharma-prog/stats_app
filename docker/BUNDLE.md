# Stats App Docker bundle

This archive contains every image and configuration file needed to run the local
Stats App stack without source code or registry access after download.

| File | For |
|---|---|
| `README.md` (this file) | Start, credentials (`.env`), verify with the automated suite, the Postman app, stop |
| **`MANUAL.md`** | **The walkthrough by hand.** Every command and what it should print: log in, send a record, read it back, check that another tenant can't see it, the IMSI mask, rejections, the dead-letter queue, duplicates, logs |
| `docker/stats-helpers.sh` · `docker/shell.sh` · `docker/token.sh` · `docker/run-demo.sh` | The helpers `MANUAL.md` uses, a shell with them loaded, a token printer, the ten-step scripted demo |
| `postman/` | The Postman collection and environment, for `./docker/run-postman.sh` and the Postman app |

**Three ways to use it.**

* **Scripts, browser and the Postman app.** *Start*, *Verify with Postman/Newman*, the
  browser pages under *Monitoring*, and *The Postman desktop app* below. No command-line
  skills needed beyond running a script.
* **The command line, by hand.** `MANUAL.md`: every rule with curl, one command at a time.
  With `./docker/shell.sh`, nothing is needed but Docker.
* **Developing the Stats App itself.** That needs the source repository. Its `README.md`,
  *Run everything end to end*, covers building from source, the dev stack, the checks and
  packaging this bundle. The dev stack and this bundle use the same container names and
  ports, so run one at a time.

**Every command on these pages runs from this directory**, the one holding this file,
`.env` and `docker/`.

## Start

Prerequisite: Docker Engine or Docker Desktop with Compose v2, on an Intel/AMD
(`amd64`) or ARM (`arm64`, e.g. Apple Silicon) machine. Nothing else — no Java,
Gradle, Postman or registry access. (`MANUAL.md` can use `curl` and `jq` on your
machine if you have them; `./docker/shell.sh` gives you both in a container if not.) Ports 8080–8083, 5432, 5672, 15672, 9090, 9187
(and 3000 for Grafana) must be free on the machine.

Check the download first; the `.sha256` file is delivered beside the archive:

```bash
sha256sum -c stats-app-docker-bundle-<version>.tar.gz.sha256
```

On macOS the command is `shasum -a 256 -c` with the same file.

```bash
tar -xzf stats-app-docker-bundle-<version>.tar.gz
cd stats-app-docker-bundle-<version>
./docker/start-stats-app.sh
```

The first start generates `.env` with random local credentials, loads the bundled
images for this machine's architecture (`stats-app-images-amd64.tar` or
`stats-app-images-arm64.tar`; the other one is never read), starts eight long-running containers (including Prometheus and PostgreSQL exporter),
waits for their health checks, and verifies all four metrics targets are UP.

## Credentials — `.env`

Every password and secret the stack uses is in **`.env`** in this directory. The archive
does not contain one. `./docker/start-stats-app.sh` writes it on the first start, with
random values and file mode `0600`, **only if no `.env` exists yet**. Keep a private
backup of it with your data backups.

To see the logins you need:

```bash
grep -E '^(KC_BOOTSTRAP_ADMIN_USERNAME|KC_BOOTSTRAP_ADMIN_PASSWORD|RABBITMQ_USER|RABBITMQ_PASSWORD|STATS_[A-Z_]+_PASSWORD)=' .env
```

### Changing a credential

**Before the first start**, put your own `.env` in this directory. The script then uses
it as it is. It must contain every key: the easy way is to start once, copy the generated
`.env`, change values, and start with that copy on a fresh machine or after *Start again*
in `MANUAL.md`.

**After the first start**, edit `.env` **and then run `./docker/start-stats-app.sh`
again**. Saving the file changes nothing by itself. The script recreates the containers
from it, and from then on the old password is refused and the new one works. The helpers
(`tok`, `./docker/token.sh`, `./docker/run-postman.sh`) read `.env`, so they follow the
change. The Postman app does not, so make its environment again (*The Postman desktop app* below).

Which keys you may change after the first start:

| Keys | After the first start | Why |
|---|---|---|
| `STATS_ALPHA_READER_PASSWORD`, `STATS_BETA_READER_PASSWORD`, `STATS_NO_ROLE_PASSWORD`, `STATS_NO_TENANT_PASSWORD`, `STATS_EMPTY_TENANT_PASSWORD` (the five test users), `KC_BOOTSTRAP_ADMIN_PASSWORD`, `STATS_API_CLIENT_SECRET`, `STATS_INPUT_CLIENT_SECRET` | **Yes** — edit, then restart | Keycloak keeps no data volume: every start re-imports the realm from `.env` |
| `POSTGRES_PASSWORD`, `POSTGRES_RO_PASSWORD` | **No** | PostgreSQL stored them in its data volume on the first start. The start script tests them against the running database and refuses to change any container if they no longer match |
| `RABBITMQ_PASSWORD`, `RABBITMQ_DEFAULT_PASS` | **No** | RabbitMQ stored them in its data volume. The applications would fail to connect |

To change a database or broker password anyway, delete the data first, then edit and
start:

```bash
docker compose -p stats-app-bundle -f docker/docker-compose.yml --profile apps --profile observability down -v
./docker/start-stats-app.sh
```

**Do not change passwords in the Keycloak admin console.** It works until the next start,
which re-imports the realm from `.env` and puts the `.env` password back. Change `.env`
instead.

### Changing a user's tenant, renaming users, adding users

Keycloak builds the realm on every start from two files in this directory:
**`docs/setup/keycloak-realm-statsapp.json`** (which users exist, their tenant attribute,
their roles) and **`.env`** (the values it fills in). Change either, then run
`./docker/start-stats-app.sh` again. As with passwords, a change made in the Keycloak
admin console is lost on the next start.

| To change | Edit in `.env` | Effect |
|---|---|---|
| The tenant of `alpha-reader` (and `no-role`) | `STATS_TENANT_A_ID` | Their tokens carry the new `tenant` claim |
| The tenant of `beta-reader` | `STATS_TENANT_B_ID` | Same, for beta |
| A user's login name | `STATS_ALPHA_READER_USERNAME`, `STATS_BETA_READER_USERNAME`, … | The old name stops working. `tok` and `./docker/token.sh` take the new name and find its password from the matching `_PASSWORD` key |
| The read role, the tenant claim's name, the API audience | `STATS_READ_ONLY_ROLE`, `STATS_TENANT_CLAIM`, `STATS_API_AUDIENCE` | Keycloak and the read API both read these, so they stay in step |

For example, to move `alpha-reader` to tenant `acme-north`:

```bash
sed -i.bak 's/^STATS_TENANT_A_ID=.*/STATS_TENANT_A_ID=acme-north/' .env
./docker/start-stats-app.sh
./docker/token.sh alpha-reader --claims
```

Expect `"tenant": "acme-north"` in the last output. `sed -i.bak` keeps the previous file as
`.env.bak`.

**What a tenant change does and does not do.** The tenant a *reader* belongs to comes
from the token. The tenant a *record* belongs to is the `tenant` field of the record as it
was sent, and it never changes once stored. After the move, `alpha-reader` sees records
sent with `"tenant":"acme-north"` and no longer sees the ones stored under `tenant-alpha`.
That is the designed behaviour (ADR-006, DL-142): history stays with the tenant it was
measured for. To read those rows again, move the user back. The examples in `MANUAL.md`
send records with `"tenant":"tenant-alpha"`, so after a change, replace that value in the
record you paste. `./docker/run-postman.sh` uses `STATS_TENANT_A_ID` by itself and needs
no change.

**Adding a user** (here `gamma-reader`, a reader on a new tenant `tenant-gamma`): add
its three values to `.env`, copy `beta-reader`'s entry in the realm file under the new
names, and restart.

```bash
printf 'STATS_TENANT_C_ID=tenant-gamma\nSTATS_GAMMA_READER_USERNAME=gamma-reader\nSTATS_GAMMA_READER_PASSWORD=choose-a-password\n' >> .env
cp docs/setup/keycloak-realm-statsapp.json docs/setup/keycloak-realm-statsapp.json.bak
jq '.users += [(.users[] | select(.username|test("BETA")) | tojson | gsub("BETA_READER"; "GAMMA_READER") | gsub("beta-reader"; "gamma-reader") | gsub("STATS_TENANT_B_ID:tenant-beta"; "STATS_TENANT_C_ID:tenant-gamma") | fromjson)]' docs/setup/keycloak-realm-statsapp.json.bak > docs/setup/keycloak-realm-statsapp.json
./docker/start-stats-app.sh
./docker/token.sh gamma-reader --claims
```

Expect `"tenant": "tenant-gamma"` and `"roles": ["stats.readOnly"]`. The restart picks
the realm change up because `.env` changed too.

**If you change only the realm file**, with nothing in `.env`, the start script leaves the
running Keycloak alone and the change does **not** apply. Recreate Keycloak instead:

```bash
docker compose -p stats-app-bundle --env-file .env -f docker/docker-compose.yml --profile apps up -d --no-build --force-recreate --wait keycloak
```

The new Keycloak signs with new keys. The read API fetches them by itself, so tokens from
the new Keycloak are accepted straight away. The new user reads only records sent with `"tenant":"tenant-gamma"`. Every other tenant's record is a `404`,
or an empty page on the list routes. To undo, put the two `.bak` files back and restart.
For a user *without* the read role, copy `no-role`'s entry instead (`test("NO_ROLE")`).
It authenticates and gets `403` on every read.

### Upgrading to a newer bundle

Copy the old bundle's `.env` into the newly extracted one **before running the start
script**. Every bundle uses the same `stats-app-bundle` Compose project, and so the same
data volumes. If no `.env` is present while those volumes exist, the script refuses to
generate new credentials and changes nothing, because new passwords cannot open a
database or broker that already has passwords. With a healthy running database, it also
checks both database roles against the supplied `.env` before changing any container.

If startup reports `password authentication failed` or `Cannot authenticate`, restore the
PostgreSQL values that initialised the volume into `.env` and start again.

## Monitoring

Prometheus starts automatically and does **not** wait for the applications to be
healthy (DL-167): if one of them fails, Prometheus is already running and
http://localhost:9090/targets shows that target as **DOWN**. Normally Input, Writer,
API and PostgreSQL should all be **UP**.

`start-stats-app.sh` exit status:

| Status | Meaning |
|---|---|
| `0` | Ready — every application healthy and every Prometheus target up |
| `1` | Not started — a container failed or timed out (its last log lines are printed) |
| `3` | Applications ready, **monitoring degraded** — the stack works, but at least one target is not up; the failing samples are printed |

To repeat the monitoring check:

```bash
./docker/check-monitoring.sh
```

Metrics URLs:

- http://localhost:8081/actuator/prometheus
- http://localhost:8082/actuator/prometheus
- http://localhost:8083/actuator/prometheus
- http://localhost:9187/metrics

The local API permits its Prometheus endpoint without a token; stats reads still
require JWT authentication, the read role and tenant. Grafana is optional and its
image, provisioning files and dashboards are included for offline startup:

```bash
docker compose -p stats-app-bundle --env-file .env -f docker/docker-compose.yml --profile apps --profile observability up -d --no-build
```

Open http://localhost:3000 for Grafana. Use the same Compose project for every
service so its DNS names resolve on the shared network.

## Verify with Postman/Newman

```bash
./docker/run-postman.sh
```

The collection exercises both ingest doors, all four read API route shapes,
all five types (`lorastats`, `sysstats`, `omsstats`, `ltestats`, `netstats`), tenant/role authentication failures,
the GET-only rule, RabbitMQ topology and consumers, and Writer persistence metrics.
A successful ingest returns JSON `{"status":"accepted","traceId":"…"}` with a
matching `X-Trace-Id` header after broker confirmation. Database persistence remains
asynchronous; the collection polls the API until Writer commits.

The startup script always loads the images from this archive, so existing mutable
`:dev` tags from an older bundle cannot silently select older code. To retain the
existing credentials and data, copy the old bundle's `.env` into this bundle before
starting; every bundle uses the same `stats-app-bundle` Compose project. Re-import the
Postman collection from this bundle — older copies lack the newer checks.

Run the suite **first**, on a freshly started stack. The walkthrough's step 10 and the
scripted demo dead-letter a record on purpose, and the suite's `DLQs stayed empty` check
then fails, correctly. `MANUAL.md` → *Start again* resets the stack.

## The Postman desktop app

The same collection runs in the Postman app, where you can send each request yourself and
see its response. `postman/local.postman_environment.json` ships with **blank**
passwords, because they are generated per installation. Make a filled copy from `.env`
(this needs `jq`):

```bash
v() { grep -E "^$1=" .env | head -1 | cut -d= -f2-; }
jq --arg a "$(v STATS_ALPHA_READER_PASSWORD)" --arg r "$(v STATS_NO_ROLE_PASSWORD)" --arg t "$(v STATS_NO_TENANT_PASSWORD)" --arg q "$(v RABBITMQ_PASSWORD)" --arg ten "$(v STATS_TENANT_A_ID)" --arg au "$(v STATS_ALPHA_READER_USERNAME)" --arg nu "$(v STATS_NO_ROLE_USERNAME)" --arg tu "$(v STATS_NO_TENANT_USERNAME)" '.name = "Stats App — Local (filled)" | .values |= map(if .key=="alphaReaderPassword" then .value=$a elif .key=="noRolePassword" then .value=$r elif .key=="noTenantPassword" then .value=$t elif .key=="rabbitPassword" then .value=$q elif .key=="tenantAlpha" then .value=$ten elif .key=="alphaReaderUsername" then .value=$au elif .key=="noRoleUsername" then .value=$nu elif .key=="noTenantUsername" then .value=$tu else . end)' postman/local.postman_environment.json > postman/filled.postman_environment.json
jq -r '.values[] | select(.key|test("Password")) | "\(.key) set: \(.value|length>0)"' postman/filled.postman_environment.json
```

Expect four lines ending `set: true`. The command also copies the tenant and the user
names from `.env`, so it stays right after the changes above. The file holds this installation's passwords, so
don't share it. Then, in Postman:

1. **Import** → drop in `postman/stats-app.postman_collection.json` and
   `postman/filled.postman_environment.json`.
2. In the environment dropdown at the top right, select **Stats App — Local (filled)**.
   Every `{{…}}` in the requests comes from here. With *No environment* selected, the
   passwords are empty and Keycloak answers `invalid_grant`.
3. **1 — Health and authentication → Authenticate alpha reader → Send.** Expect `200`
   with an `access_token`. The request's test script stores it as the environment
   variable `alphaToken`, which every read request sends as its bearer token. The two
   other *Authenticate* requests store `noRoleToken` and `noTenantToken` for folder 4.
   `./docker/token.sh alpha-reader --claims` shows what is inside the token
   (`tenant-alpha`, `stats-api`, `stats.readOnly`).
4. **2 — Ingest → POST internal lorastats → Send.** Expect `200`,
   `{"status":"accepted","traceId":"…"}`, and the same ID in the `X-Trace-Id` header.
5. **3 — Read API → GET lorastats by uid → Send.** Expect `200` and the full record.
   Each POST stores the uid its GET reads, so send them in order. A `404` means the
   Writer has not committed yet: send again. For the IMSI mask, send **POST internal
   ltestats**, then **GET ltestats Lte by uid**: `data.imsi` has `*` in the middle.
6. **4 — API security and errors**, one by one, after step 3: `401` anonymous, `403` no
   read role, `403` no tenant, `404` unknown uid, `400` unknown type, `405` any POST.
7. **5 — RabbitMQ and Writer evidence:** the 25 queues (*Basic* auth, `stats` /
   `{{rabbitPassword}}`) and the Writer's `records_persisted_total` per type.
8. **All of it at once:** right-click the collection → **Run collection** → **Run**.
   Expect 100 passed, or 99 and one `DLQs stayed empty` failure after the demo or
   `MANUAL.md` step 10.

| Postman shows | Why | Do |
|---|---|---|
| `invalid_grant` / `Invalid user credentials` on *Authenticate* | The selected environment's password is not this installation's: no environment selected, a file made from another folder's `.env`, or a password typed in by hand | Make the filled file again, delete the old environment in Postman, import the new one. To choose your own password, change it in `.env` and restart (*Credentials* above), then make the file again |
| Edited `filled.postman_environment.json`, nothing changed | Postman keeps its own copy from import time | Re-import, or edit the variable's **Current value** in Postman's *Environments* view and **Save** |
| `401` on reads that worked a minute ago | Tokens last **15 minutes** | Send *Authenticate alpha reader* again |
| `Could not send request` / `ECONNREFUSED` | The stack is not running | `./docker/start-stats-app.sh` |

For a single request outside the collection, `./docker/token.sh alpha-reader` prints a
token: paste it under the request's **Authorization → Bearer Token**.

## Walk through it by hand

Open **`MANUAL.md`**. It has fourteen steps, each with the command to paste and the
output to expect. **Run every command from this directory.** The passwords are in this
directory's `.env`, which was generated on first start. Another copy of the Stats App,
such as an older bundle or a developer checkout, has different passwords, and its
Keycloak answers "Invalid user credentials" for these.

* `. docker/stats-helpers.sh` loads the bash/zsh helpers `tok`, `claims`, `env_get` and
  `pg`. It needs `curl` and `jq`.
* `./docker/shell.sh` gives the same helpers in a container, with nothing installed but
  Docker.
* `./docker/run-demo.sh` runs the ten-step scripted demo. Its step 10 stops RabbitMQ
  briefly.

## Troubleshooting

| You see | Why | Do |
|---|---|---|
| `grep: .env: No such file or directory`, `stats-helpers: no .env` | The command ran outside this directory (for example in `~`) | `cd` into this directory and run it again |
| `Invalid user credentials` / `invalid_grant` | The password sent is not the one the running Keycloak was started with: commands run from another bundle's folder or a developer checkout, a password typed into Postman by hand, or `.env` edited without restarting | Run from the folder the stack was started from (`. docker/stats-helpers.sh` warns when it is a different one). For Postman, make the filled environment again. After editing `.env`, run `./docker/start-stats-app.sh` |
| `jq: parse error` right after asking for a token | The token request failed, and its error message went into the decoder | Use `tok` / `claims` or `./docker/token.sh`, which print Keycloak's reason instead |
| `zsh: no matches found` | A URL containing `?` lost its quotes | Paste the line exactly as written |
| `curl: (3)` or odd extra arguments after pasting | A `#` comment pasted into zsh becomes arguments | The blocks on these pages have none. For your own, run `setopt interactivecomments` once |
| `Bind for 0.0.0.0:8080 failed: port is already allocated` | Another Stats App stack (a developer stack, another bundle) is running | Stop it, then `./docker/start-stats-app.sh` |
| `401` on reads that worked earlier | Tokens last 15 minutes | Get a new token |
| A changed tenant, user or realm entry has no effect | Keycloak was not recreated | See *Changing a user's tenant…* above: restart after a `.env` change, `--force-recreate keycloak` after a realm-only change |
| `password authentication failed`, `Cannot authenticate` at start | `POSTGRES_*` in `.env` no longer match the database volume | Put back the values that initialised it (*Upgrading*), or `down -v` to start with empty data |
| `DLQs stayed empty` fails in the suite | `MANUAL.md` step 10 or the demo dead-lettered a record, as intended | `MANUAL.md` → *Start again*, then run the suite first |
| Start exits with `3` | Applications ready, monitoring degraded | See *Monitoring*. The stack itself is usable |

## Stop

```bash
./docker/stop-stats-app.sh
```

Data is retained in Docker volumes. To deliberately delete all local data, run
`docker compose -p stats-app-bundle -f docker/docker-compose.yml --profile apps down -v`.

## Important scope

The bundle uses `STATS_INPUT_AUTH_MODE=none` for isolated local evaluation. Anyone
who can reach port 8081 can submit records. Do not expose this configuration on a
shared or production network.

The runtime intentionally consists of separate containers. `stats-common` is a
library embedded in the three application images; it is not a fourth application.

All five stat types are built end to end. LTE checks include measured, 2G and
unknown-technology records. Primary LTE/netstats examples match the 9 October
live-export shapes using synthetic identifiers and addresses. Optional degraded
states, extra interfaces and numeric boundaries remain covered by source regression
tests. **`imsi` is stored as received and masked on every read API response** (carrier
prefix and last four digits visible, the rest `*`, length kept — DL-167); `lac`/`cid`
are returned as reported. No LTE/netstats payload is written to any log at any level.

Input refuses, with a 400, a record PostgreSQL could not store — an over-long `uid`,
a blank `tenant`, an out-of-range `version`, a NUL character — instead of accepting it
and dead-lettering it later (DL-168).
