# Stats App Docker bundle

This archive contains every image and configuration file needed to run the local
Stats App stack without source code or registry access after download.

| File | For |
|---|---|
| `README.md` (this file) | First-time setup (Windows/macOS/Linux), start, credentials (`.env`), explore services, verify with the automated suite, the Postman app, stop |
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

## First-time setup — choose your operating system

If Docker is already installed, its engine is running, and `docker info` works from the
terminal you plan to use, skip to **Start the bundle** below.

> **Windows users:** the files in `docker/` are Linux shell scripts (`.sh`). Do **not**
> double-click them and do not choose Docker Desktop, Docker Desktop Launcher or Docker CLI
> when Windows asks which app should open them. Run them from an **Ubuntu WSL terminal**.

### Windows 11 — Docker Desktop + WSL 2 + Ubuntu

This is the recommended setup for someone who does not already have a Linux shell on
Windows.

#### 1. Install Docker Desktop

Install **Docker Desktop for Windows** from the official Docker website and start it:

https://www.docker.com/products/docker-desktop/

Wait until Docker Desktop reports that the Docker engine is running. Use the normal
**Linux containers / WSL 2** backend.

You can check from PowerShell:

```powershell
docker info
```

If this says that the Docker daemon/engine is not reachable, keep Docker Desktop open and
finish the WSL integration steps below.

#### 2. Install WSL 2 and Ubuntu

Open **PowerShell as Administrator** and run:

```powershell
wsl --install -d Ubuntu
```

Restart Windows if requested. The first time Ubuntu launches it asks you to create a Linux
username and password. This password is only for the Ubuntu environment; it does not have
to match the Windows password.

Check the installed WSL distributions:

```powershell
wsl --list --verbose
```

You should see an `Ubuntu` entry using **VERSION 2**.

Docker Desktop also creates an internal distribution named `docker-desktop`. That
distribution belongs to Docker Desktop and is **not** the shell you should use for this
bundle.

If plain `wsl` opens a prompt similar to:

```text
docker-desktop:/...#
```

set Ubuntu as the default distribution:

```powershell
wsl --set-default Ubuntu
```

Or launch Ubuntu explicitly at any time:

```powershell
wsl -d Ubuntu
```

#### 3. Enable Ubuntu inside Docker Desktop

In Docker Desktop open:

**Settings → Resources → WSL Integration**

Enable integration for **Ubuntu**, then choose **Apply & Restart**.

Depending on the Docker Desktop version, there may also be a setting such as
**Use the WSL 2 based engine** under **Settings → General**. Keep that enabled.

After Docker Desktop restarts, open Ubuntu:

```powershell
wsl -d Ubuntu
```

Then, inside the Ubuntu terminal, verify Docker:

```bash
docker version
docker info
```

`docker version` should show both a **Client** and a **Server** section. `docker info`
should print information about the running Docker engine.

If a script says:

```text
Docker is installed but its daemon is not reachable.
```

the Docker command is present but Ubuntu cannot reach Docker Desktop. The usual fixes are:

1. Start Docker Desktop and wait for the engine to finish starting.
2. Enable **Ubuntu** under **Settings → Resources → WSL Integration**.
3. Apply/restart Docker Desktop.
4. Close and reopen the Ubuntu terminal.
5. Run `docker info` again before starting this bundle.

#### 4. Verify the downloaded archive on Windows

`sha256sum` is a Linux command and is normally not available in PowerShell. In PowerShell,
from the folder containing the archive and its `.sha256` file:

```powershell
Get-FileHash .\stats-app-docker-bundle-<version>.tar.gz -Algorithm SHA256
Get-Content .\stats-app-docker-bundle-<version>.tar.gz.sha256
```

The long SHA-256 value printed by `Get-FileHash` must match the value at the beginning of
the `.sha256` file.

If you prefer, you can also verify from the Ubuntu WSL terminal with the Linux command
shown later in this README.

#### 5. Open the bundle from Ubuntu WSL

Windows drives are available under `/mnt`. For example, this Windows folder:

```text
C:\Users\your-name\Desktop\Statsapp\stats-app-docker-bundle-<version>
```

is normally:

```bash
cd /mnt/c/Users/your-name/Desktop/Statsapp/stats-app-docker-bundle-<version>
```

Run the startup script from Ubuntu:

```bash
bash ./docker/start-stats-app.sh
```

Using `bash` explicitly is fine. Running `./docker/start-stats-app.sh` also works when the
file has executable permission.

### macOS

1. Install and start Docker Desktop for Mac.
2. Wait until Docker Desktop reports that the engine is running.
3. Open **Terminal**.
4. Verify Docker:

```bash
docker info
```

5. Verify the archive:

```bash
shasum -a 256 -c stats-app-docker-bundle-<version>.tar.gz.sha256
```

6. Extract and start:

```bash
tar -xzf stats-app-docker-bundle-<version>.tar.gz
cd stats-app-docker-bundle-<version>
./docker/start-stats-app.sh
```

On Apple Silicon (`arm64`) the startup script automatically uses the bundled ARM image
archive.

### Linux

Install Docker Engine and the Docker Compose v2 plugin using the instructions for your
Linux distribution. Make sure this works:

```bash
docker info
docker compose version
```

If your Linux account is not allowed to use Docker directly, either configure Docker for
non-root use or use the method required by your distribution.

Verify, extract and start:

```bash
sha256sum -c stats-app-docker-bundle-<version>.tar.gz.sha256
tar -xzf stats-app-docker-bundle-<version>.tar.gz
cd stats-app-docker-bundle-<version>
./docker/start-stats-app.sh
```

## Start the bundle


Prerequisite: Docker Engine or Docker Desktop with Compose v2, on an Intel/AMD
(`amd64`) or ARM (`arm64`, e.g. Apple Silicon) machine. Nothing else — no Java,
Gradle, Postman or registry access. (`MANUAL.md` can use `curl` and `jq` on your
machine if you have them; `./docker/shell.sh` gives you both in a container if not.)
Ports 8080–8083, 5432, 5672, 15672, 9090, 9187 (and 3000 for Grafana) must be free
on the machine.

Run all commands below from a Bash-compatible terminal. On Windows, that means the
**Ubuntu WSL terminal**, not PowerShell and not the internal `docker-desktop` WSL
distribution.

If the archive has not already been extracted:

```bash
tar -xzf stats-app-docker-bundle-<version>.tar.gz
cd stats-app-docker-bundle-<version>
```

Start the complete stack:

```bash
./docker/start-stats-app.sh
```

If Windows/WSL reports a permission issue when launching the script, use:

```bash
bash ./docker/start-stats-app.sh
```

The first start generates `.env` with random local credentials, loads the bundled
images for this machine's architecture (`stats-app-images-amd64.tar` or
`stats-app-images-arm64.tar`; the other one is never read), starts eight long-running containers (including Prometheus and PostgreSQL exporter),
waits for their health checks, and verifies all four metrics targets are UP.


## After startup — confirm it works and explore the services

First, confirm that the containers are running:

```bash
docker ps
```

For a simple end-to-end authentication check:

```bash
./docker/token.sh alpha-reader --claims
```

A successful result shows claims including the tenant, the `stats-api` audience and the
`stats.readOnly` role. `./docker/token.sh alpha-reader` prints the raw bearer token instead.
Treat bearer tokens like passwords: do not paste them into tickets, chat rooms or shared
documents.

Useful local services:

| Service | Address | What to use it for |
|---|---|---|
| Keycloak | http://localhost:8080 | Login/identity administration and realm inspection |
| Stats Input | http://localhost:8081 | Input application; metrics at `/actuator/prometheus` |
| Stats Writer | http://localhost:8082 | Writer application; metrics at `/actuator/prometheus` |
| Stats Read API | http://localhost:8083 | Read API; use the supplied Postman collection or `MANUAL.md` |
| RabbitMQ management | http://localhost:15672 | Queues, exchanges, consumers and broker state |
| Prometheus | http://localhost:9090 | Metrics queries; `/targets` shows scrape target health |
| PostgreSQL | `localhost:5432` | Database port; normally inspected through tools/scripts rather than a browser |
| PostgreSQL exporter | http://localhost:9187/metrics | PostgreSQL metrics exported to Prometheus |
| Grafana (optional) | http://localhost:3000 | Dashboards, after starting the observability profile |

The login values you need for Keycloak and RabbitMQ are stored in `.env`; the next section
shows how to display them.

### Keycloak reachability check

If Keycloak opens in a browser but a helper script claims it is not reachable, test the
actual realm endpoint from the same terminal that runs the script:

```bash
REALM=$(grep '^STATS_REALM_NAME=' .env | cut -d= -f2-)
curl -i "http://localhost:8080/realms/$REALM/.well-known/openid-configuration"
```

An HTTP `200` response means Keycloak is reachable.

A `404` from `http://localhost:8080/health/ready` by itself does **not** prove Keycloak is
down in this bundle. Use the OpenID configuration endpoint above.

### Windows line-ending problem (`curl: (3)` / misleading "Keycloak is not reachable")

If `.env` was created, copied or edited by a Windows program, it may contain CRLF line
endings. A hidden carriage return can turn a valid value such as `statsapp` into an invalid
URL value and produce errors such as:

```text
curl: (3) URL rejected: Malformed input to a URL function
```

or make `docker/token.sh` print:

```text
Keycloak is not reachable at http://localhost:8080.
```

Check for hidden `^M` characters:

```bash
grep '^STATS_REALM_NAME=' .env | cat -v
```

If the line ends in `^M`, back up the file and convert it to Unix line endings:

```bash
cp .env .env.bak
sed -i 's/\r$//' .env
```

Then retry:

```bash
./docker/token.sh alpha-reader
```

## Credentials — `.env`

Initial infrastructure secrets and optional fixture credentials are in **`.env`** in this directory.
Passwords for users added through Keycloak stay in Keycloak. The archive
does not contain one. `./docker/start-stats-app.sh` writes it on the first start, with
random values and file mode `0600`, **only if no `.env` exists yet**. Keep a private
backup of it with your data backups.

To see the logins you need:

```bash
grep -E '^(KC_BOOTSTRAP_ADMIN_USERNAME|KC_BOOTSTRAP_ADMIN_PASSWORD|RABBITMQ_USER|RABBITMQ_PASSWORD|STATS_[A-Z_]+_PASSWORD)=' .env
```

### Changing a credential

Keycloak now stores users, credentials, roles and signing keys in the named
`keycloak-data` volume. **Users created in the admin console survive both restart and
container recreation.** Realm JSON and its `.env` substitutions bootstrap a missing
realm only; editing them does not overwrite an existing realm.

| Credential | Where to change it after first initialization |
|---|---|
| Keycloak user or administrator password | Keycloak admin console. `.env` fixture passwords are only helper defaults; use `--prompt` after changing one. |
| Keycloak client secret | Keycloak console, plus the corresponding application's configuration |
| PostgreSQL / RabbitMQ password | Coordinate a password change in that service and update application configuration; editing `.env` alone does not rotate it. |

For RabbitMQ, `RABBITMQ_NODE_HOSTNAME` fixes its node identity across container
recreation (default `stats-rabbitmq` for a fresh installation). When reusing an older
broker volume, set this to the hostname of its existing `rabbit@HOSTNAME` data
directory; choosing a new hostname opens a different queue store.

Keep a private backup of `.env` and the data volumes. `docker compose down` preserves
named volumes. **`docker compose down -v` deletes them, including Keycloak users and
statistics.** It is not a user-management or upgrade procedure.

### Preserve an existing Keycloak installation

Before the first upgrade from a bundle without Keycloak persistence, preserve its
container-local database:

```bash
./docker/migrate-keycloak-data.sh
./docker/start-stats-app.sh
```

The migration stops Keycloak, copies its H2 database into a private backup under
`build/keycloak-backups/`, and populates the Compose project's `keycloak-data` volume.
It does not modify PostgreSQL or RabbitMQ. It refuses to overwrite an existing target
volume. On failure it attempts to restart the original container; keep the backup.
The start script refuses to replace a legacy container until this migration is done.
If the old container has already been deleted, recover its database from a backup;
the realm file cannot recover users created later through the console.

This is the local development `start-dev` database. A production Keycloak deployment
should use its own supported external database and backups.

### Changing a user's tenant, renaming users, adding users

Manage existing users in **Keycloak admin console → realm `statsapp` → Users**:

1. Create and enable a user, supplying username, first name, last name and email.
2. Set a password under **Credentials**. For the development password-grant helper,
   make it non-temporary and clear any required actions after completing setup.
3. Set the single **Tenant** profile attribute, for example `zra_test`.
4. Under **Role mapping**, assign the realm role **`stats.readOnly`**.
5. Obtain a token; a console-created user needs no `.env` entry:

```bash
./docker/token.sh new-reader --prompt --claims
TOKEN=$(./docker/token.sh new-reader --prompt)
```

The helper prompts without echoing the password. It also accepts `--password-stdin`
for automation. The existing fixture users can still use passwords from `.env` for
repeatable demos. `tok USER --prompt` in the tools shell uses the same helper.

No PostgreSQL grants are needed for an API user. `stats_ro` remains the API's internal
SELECT-only database login. API access requires a valid token for `stats-api`, the
read role, and one nonblank string tenant claim. Missing role or tenant gives `403`.

**Existing realms need the Tenant profile field once.** New imports configure it
already. For an older realm, open **Realm settings → User profile → Create attribute**:
name `tenant`, display name `Tenant`, multivalued off, maximum length 128, required off,
and view/edit permissions **Admin only**. Preserve the existing profile fields.
`docs/setup/keycloak-user-profile.json` shows the development profile configuration.
Do not allow end users to edit their tenant. Existing token mappers read this attribute
and emit the configured tenant claim (`tenant` by default).

**Tenant changes:** change the user's Tenant field in Keycloak and obtain a fresh
token. Stored records retain the `tenant` supplied at ingestion. Changing a reader's
tenant does not move history. `maintenant` never grants access. Tokens issued before a
role/tenant change can retain their previous permissions until they expire (the dev
realm defaults to 15 minutes); this API validates JWTs locally.

For interactive browser login, the development public client `stats-dev-cli` also
supports Authorization Code with PKCE **S256**, with callback
`http://localhost:8085/callback` by default (`STATS_LOGIN_REDIRECT_URI` at initial import).
Configure the callback to match your login client. Older realms must enable Standard
flow, set the redirect URI and require S256 on that client through Keycloak. The CLI
helper continues to use the development password grant and does not support MFA or
outstanding required actions.

### Adding a user to a fresh bundle's realm seed

For reproducible fixtures, add the user to `docs/setup/keycloak-realm-statsapp.json`
and its username/password/tenant values to `.env` **before the first realm import**.
This does not update an existing persistent realm; use the admin console there.

The seed now includes **`zra-reader`**, role `stats.readOnly`, tenant **`zra_test`**:

```env
STATS_ZRA_READER_USERNAME=zra-reader
STATS_ZRA_READER_PASSWORD=<choose-a-private-password>
STATS_ZRA_TENANT_ID=zra_test
```

Fresh bundle installations generate this fixture's password. Upgrading an existing
realm requires creating `zra-reader` in the console with the values above; adding
`.env` entries alone does not create it.

### Verify zra_test tenant isolation

From a checkout with Python 3, curl and jq, after starting the stack:

```bash
./docker/token.sh zra-reader --claims
python3 docker/check-zra-isolation.py --report build/zra-isolation.md
```

The verifier uses fixture credentials from `.env` for `zra-reader` and `alpha-reader`;
keep these optional test credentials synchronized with Keycloak. It inserts three
unique synthetic records with current timestamps:

| Record | tenant | maintenant | zra-reader access |
|---|---|---|---|
| A | zra_test | zra_test | Visible |
| B | tenant-alpha | tenant-alpha | Hidden |
| C | tenant-alpha | zra_test | Hidden |

It proves B and C exist using the alpha reader before testing exclusion, then checks
the tenant list, A/B/C UID lookups, B's gateway and every route without a token.
The list supplies its required `from` and `to` parameters. The report contains curl
arguments, HTTP status, response body and PASS/FAIL; tokens and passwords are omitted.
If C or any foreign-tenant record appears for `zra-reader`, verification stops immediately.
Synthetic records remain in the database until normal retention removes them.

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
healthy: if one of them fails, Prometheus is already running and
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
| `invalid_grant` / `Invalid user credentials` on *Authenticate* | The selected environment's password is not this installation's: no environment selected, a file made from another folder's `.env`, or a password typed in by hand | Make the filled file again, delete the old environment in Postman, import the new one. To choose your own password, change it in Keycloak and update the optional Postman fixture credentials |
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
| Windows asks which app should open `start-stats-app.sh` | `.sh` is a Linux shell script; Docker Desktop is not the program that executes it | On Windows install Ubuntu with WSL 2, open `wsl -d Ubuntu`, `cd` to the bundle under `/mnt/c/...`, then run `bash ./docker/start-stats-app.sh` |
| `sha256sum` is not recognized in PowerShell | `sha256sum` is normally a Linux command | Use `Get-FileHash <archive> -Algorithm SHA256` and compare it with the `.sha256` file, or run `sha256sum -c ...` inside Ubuntu WSL |
| `wsl` opens `docker-desktop:/...#` and `bash` is missing | `docker-desktop` is Docker Desktop's internal WSL distribution, not a general Ubuntu shell | Run `wsl --set-default Ubuntu` in PowerShell, or launch `wsl -d Ubuntu` explicitly |
| `Docker is installed but its daemon is not reachable` | Ubuntu can see the Docker CLI but cannot reach the Docker Desktop engine | Start Docker Desktop, enable Ubuntu in **Settings → Resources → WSL Integration**, Apply/Restart, reopen Ubuntu, then verify `docker info` |
| `curl: (3) URL rejected: Malformed input to a URL function` after reading values from `.env` | The `.env` file probably has Windows CRLF line endings and the value contains a hidden `\\r` | `cp .env .env.bak && sed -i 's/\\r$//' .env`, then retry |
| `Keycloak is not reachable at http://localhost:8080` but the browser opens Keycloak | The helper's token request failed; one common Windows cause is CRLF in `.env` | Test the realm's OpenID endpoint, inspect `grep '^STATS_REALM_NAME=' .env \\| cat -v`, remove CRLF if present, then retry the token helper |
| New console user cannot get a token | Password, required actions or user setup is incomplete | Use `./docker/token.sh USER --prompt`; complete the account setup in Keycloak. No `.env` user entry is required. |
| `grep: .env: No such file or directory`, `stats-helpers: no .env` | The command ran outside this directory (for example in `~`) | `cd` into this directory and run it again |
| `Invalid user credentials` / `invalid_grant` | The password sent is not the one the running Keycloak was started with: commands run from another bundle's folder or a developer checkout, a password typed into Postman by hand, or a stale fixture password after a Keycloak password change | Run from the folder the stack was started from (`. docker/stats-helpers.sh` warns when it is a different one). For Postman, make the filled environment again. For console-managed users, use `./docker/token.sh USER --prompt` |
| `jq: parse error` right after asking for a token | The token request failed, and its error message went into the decoder | Use `tok` / `claims` or `./docker/token.sh`, which print Keycloak's reason instead |
| `zsh: no matches found` | A URL containing `?` lost its quotes | Paste the line exactly as written |
| `curl: (3)` or odd extra arguments after pasting | A `#` comment pasted into zsh becomes arguments | The blocks on these pages have none. For your own, run `setopt interactivecomments` once |
| `Bind for 0.0.0.0:8080 failed: port is already allocated` | Another Stats App stack (a developer stack, another bundle) is running | Stop it, then `./docker/start-stats-app.sh` |
| `401` on reads that worked earlier | Tokens last 15 minutes | Get a new token |
| A changed tenant, user or realm entry has no effect | Existing realms skip seed import; an old token retains old claims | Edit the user in Keycloak and obtain a new token. Recreating the container preserves the realm. |
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
shared or production network. Setting `STATS_INPUT_AUTH_MODE=oauth2` in `.env` and
restarting `stats-input` requires a client-credentials token from the `stats-input`
Keycloak client with the `stats.ingest` role instead; the demo and the Postman
collection send no token and expect `none`.

The runtime intentionally consists of separate containers. `stats-common` is a
library embedded in the three application images; it is not a fourth application.

All five stat types are built end to end. LTE checks include measured, 2G and
unknown-technology records. Primary LTE/netstats examples match the 9 October
live-export shapes using synthetic identifiers and addresses. Optional degraded
states, extra interfaces and numeric boundaries remain covered by source regression
tests. **`imsi` is stored as received and masked on every read API response** (carrier
prefix and last four digits visible, the rest `*`, length kept); `lac`/`cid`
are returned as reported. No LTE/netstats payload is written to any log at any level.

Input refuses, with a 400, a record PostgreSQL could not store — an over-long `uid`,
a blank `tenant`, an out-of-range `version`, a NUL character — instead of accepting it
and dead-lettering it later.