#!/usr/bin/env python3
"""Failure/recovery drill for the disposable stats-lte-netstats-verification stack.

Uses alternate loopback ports and ltecheck-* containers only. Never run against a
shared stack: this drill stops its database and Writer. Credentials are read from
the staging .env and are never printed. Run after the packaged Postman collection.
"""
import base64
import json
import os
import secrets
import sys
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
STAGE = Path("/tmp/stats-lte-netstats-verification")
ENV = {}
for line in (STAGE / ".env").read_text().splitlines():
    if line and not line.startswith("#") and "=" in line:
        key, value = line.split("=", 1)
        ENV[key] = value.strip().strip("\"'")


def request(port, path, method="GET", body=None, token=None, rabbit=False):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if rabbit:
        auth = ENV["RABBITMQ_USER"] + ":" + ENV["RABBITMQ_PASSWORD"]
        headers["Authorization"] = "Basic " + base64.b64encode(auth.encode()).decode()
    if body is not None:
        headers["Content-Type"] = "application/json"
        body = json.dumps(body, separators=(",", ":")).encode()
    req = urllib.request.Request("http://localhost:" + str(port) + path,
                                 data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            data = response.read()
            return response.status, json.loads(data) if data else None
    except urllib.error.HTTPError as failure:
        return failure.code, None


def wait(check, seconds=90):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        try:
            if check():
                return
        except (urllib.error.URLError, OSError, TimeoutError):
            pass
        time.sleep(0.25)
    raise AssertionError("Bounded polling timed out")


def queue(name):
    status, data = request(18072, "/api/queues/%2F/" + name, rabbit=True)
    assert status == 200
    return data


def expired_count(kind):
    with urllib.request.urlopen("http://localhost:18082/actuator/prometheus", timeout=5) as response:
        metrics = response.read().decode()
    match = re.search(r'^records_expired_on_arrival_total\{[^}]*type="' + kind + r'"[^}]*\} (\S+)',
                      metrics, re.MULTILINE)
    assert match
    return float(match.group(1))


def fixture(name):
    record = json.loads((ROOT / "docs/schemas/fixtures" / name).read_text())
    record = {k: v for k, v in record.items() if not k.startswith("$")}
    record.update(uid="smoke-" + str(uuid.uuid4()), tenant=ENV["STATS_TENANT_A_ID"],
                  maintenant=ENV["STATS_TENANT_B_ID"])
    record["ts"].update(server=int(time.time()), device=int(time.time()))
    if "imsi" in record["data"]:
        record["data"]["imsi"] = "001010123456789"
    return record


def post(record):
    status, data = request(18081, "/internal/stats", "POST", record)
    assert status in (200, 202) and data["status"] == "accepted" and data["traceId"]


def docker(action, service):
    assert service in ("postgres", "writer")
    subprocess.run(["docker", action, "ltecheck-" + service], check=True,
                   stdout=subprocess.DEVNULL)



def read(record):
    return request(18083, "/v1/stats/" + record["type"] + "/" + record["uid"], token=TOKEN)


def scoped_redrive():
    # Seed valid records to represent delivery rejected before these slices existed.
    # Their missing-handler cause is now resolved. The command uses a separate,
    # single-type operator identity, never the Writer or administrator account.
    for kind in ["ltestats", "netstats"]:
        record = fixture("valid/" + kind + "-production" + ("-healthy" if kind == "netstats" else "") + ".json")
        user = "smoke-redrive-" + kind
        password = secrets.token_urlsafe(24)
        status, _ = request(18072, "/api/users/" + user, "PUT",
                            {"password": password, "tags": ""}, rabbit=True)
        assert status in (201, 204)
        try:
            permissions = {"configure": r"^(app_statsstore\." + kind + r"|app_statsstore\." + kind + r"\.dlq)$",
                           "write": "^app_stats_input$", "read": r"^app_statsstore\." + kind + r"\.dlq$"}
            assert request(18072, "/api/permissions/%2F/" + user, "PUT", permissions, rabbit=True)[0] in (201, 204)
            topics = {"exchange": "app_stats_input", "write": "^" + kind + "$", "read": "^$"}
            assert request(18072, "/api/topic-permissions/%2F/" + user, "PUT", topics, rabbit=True)[0] in (201, 204)
            payload = json.dumps(record, separators=(",", ":"))
            status, data = request(18072, "/api/exchanges/%2F/app_stats_dlx/publish", "POST",
                                   {"properties": {"delivery_mode": 2, "headers": {"x-stats-attempt": 1}},
                                    "routing_key": kind + ".dlq", "payload": payload,
                                    "payload_encoding": "string"}, rabbit=True)
            assert status == 200 and data["routed"]
            env = os.environ.copy()
            env.update(RABBITMQ_REDRIVE_USER=user, RABBITMQ_REDRIVE_PASSWORD=password,
                       RABBITMQ_HOST="localhost", RABBITMQ_PORT="18062", RABBITMQ_REDRIVE_URI="")
            args = ("--type=" + kind + " --max-messages=1 --rate-per-second=1 --retention-days=90 "
                    "--change-id=local-lte-netstats-verification --root-cause-resolved=true")
            result = subprocess.run([str(ROOT / "gradlew"), ":stats-writer:redriveDlq", "--no-daemon", "--args=" + args],
                                    cwd=ROOT, env=env, capture_output=True, text=True, timeout=90)
            assert result.returncode == 0, "Scoped redrive command failed; credentials withheld"
            assert '"event":"redrive_completed"' in result.stdout and '"moved":1' in result.stdout
            wait(lambda: read(record)[0] == 200)
            assert read(record)[1]["data"] == record["data"]
            print(kind + ": dedicated scoped redrive confirmed, committed and read losslessly", flush=True)
        finally:
            assert request(18072, "/api/users/" + user, "DELETE", rabbit=True)[0] in (204, 404)


def main():
    global TOKEN
    form = urllib.parse.urlencode({"client_id": ENV["STATS_DEV_CLI_CLIENT_ID"],
                                  "grant_type": "password",
                                  "username": ENV["STATS_ALPHA_READER_USERNAME"],
                                  "password": ENV["STATS_ALPHA_READER_PASSWORD"]}).encode()
    with urllib.request.urlopen(urllib.request.Request(
            "http://localhost:18080/realms/statsapp/protocol/openid-connect/token", data=form)) as response:
        TOKEN = json.load(response)["access_token"]





    if "--redrive-only" in sys.argv:
        scoped_redrive()
        return

    for kind, poison in [("ltestats", "invalid/ltestats-shape-complete-mixed.json"),
                         ("netstats", "valid/netstats-extra-interface-overflow.json")]:
        bad = fixture(poison)
        q = "app_statsstore." + kind + ".dlq"
        before = queue(q)["messages_ready"]
        post(bad)
        wait(lambda: queue(q)["messages_ready"] == before + 1)
        assert read(bad)[0] == 404
        status, deliveries = request(18072, "/api/queues/%2F/" + q + "/get", "POST",
                                     {"count": 1, "ackmode": "ack_requeue_false",
                                      "encoding": "auto", "truncate": 100000}, rabbit=True)
        assert status == 200 and len(deliveries) == 1
        failed = deliveries[0]
        assert json.loads(failed["payload"]) == bad
        assert failed["properties"]["headers"]["x-stats-permanent"] is True
        assert failed["properties"]["headers"]["x-stats-attempt"] == 1
        expired = fixture("valid/" + kind + "-production" + ("-healthy" if kind == "netstats" else "") + ".json")
        expired["ts"].update(server=946684800, device=946684800)
        expired_before = expired_count(kind)
        post(expired)
        wait(lambda: expired_count(kind) == expired_before + 1)
        wait(lambda: queue("app_statsstore." + kind)["messages"] == 0)
        wait(lambda: queue(q)["messages_ready"] == before)
        assert read(expired)[0] == 404
        print(kind + ": payload/range failure -> own permanent DLQ; valid expiry -> skip", flush=True)

    pending = [fixture("valid/ltestats-production-2g-no-rsrp.json"),
               fixture("valid/netstats-extra-interface.json")]
    docker("stop", "postgres")
    try:
        for record in pending:
            post(record)
        for record in pending:
            wait(lambda r=record: queue("app_statsstore." + r["type"] + ".retry.1")["messages_ready"] >= 1,
                 seconds=50)
        print("Both types: confirmed Input acceptance during database outage; routed to retry tier 1", flush=True)
    finally:
        docker("start", "postgres")
    for record in pending:
        wait(lambda r=record: read(r)[0] == 200, seconds=90)
        assert read(record)[1]["data"] == record["data"]
        post(record)
    print("Both types: retry returned to own queue, committed, read losslessly, replay accepted", flush=True)
    docker("restart", "writer")
    wait(lambda: request(18082, "/actuator/health/readiness")[0] == 200)
    for record in pending:
        assert read(record)[0] == 200
        q = queue("app_statsstore." + record["type"])
        assert q["durable"] and q["arguments"]["x-queue-type"] == "quorum"
        assert "x-message-ttl" not in queue("app_statsstore." + record["type"] + ".dlq")["arguments"]
    print("Writer graceful restart: both committed records retained; durable quorum topology and inert DLQs verified", flush=True)
    scoped_redrive()


if __name__ == "__main__":
    main()
