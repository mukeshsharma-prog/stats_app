#!/usr/bin/env python3
"""Run the requested A/B/C checks against a running development stack.

Writes exact curl argument lists, status codes, bodies and PASS/FAIL to a Markdown
report. Authentication headers are passed on stdin and are never included in the
report. Inserts three uniquely named synthetic lorastats records; does not delete data.
"""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, default=ROOT / 'build/zra-isolation.md')
    args = parser.parse_args()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    env_file = Path(os.environ.get('STATS_ENV_FILE', str(ROOT / '.env')))
    env = dict(line.split('=', 1) for line in env_file.read_text().splitlines()
               if line and not line.startswith('#') and '=' in line)
    api = os.environ.get('API_BASE_URL', 'http://localhost:8083')
    input_url = os.environ.get('INPUT_BASE_URL', 'http://localhost:8081')
    username = env.get('STATS_ZRA_READER_USERNAME', 'zra-reader')
    expected_tenant = env.get('STATS_ZRA_TENANT_ID', 'zra_test')
    report = ['# zra-reader tenant isolation', '',
              f'Run time (UTC): {time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime())}', '',
              'Synthetic data only. Bearer tokens/passwords are not recorded. '
              '`curl --config -` receives the Authorization header on stdin.', '']
    failures = []

    def save():
        args.report.write_text('\n'.join(report) + '\n')

    def record(label, command, status, body, passed):
        report.extend([f'## {"PASS" if passed else "FAIL"}: {label}', '',
                       '```sh', command, '```', '', f'HTTP status: {status}', '',
                       '```json', body, '```', ''])
        save()
        print(f'{"PASS" if passed else "FAIL"}: {label} (HTTP {status})', flush=True)
        if not passed:
            failures.append(label)

    def token(user):
        proc = subprocess.run([str(ROOT / 'docker/token.sh'), user],
                              capture_output=True, text=True)
        if proc.returncode:
            record('Token acquisition for ' + user,
                   shlex.join(['./docker/token.sh', user]), 'N/A (helper failed)',
                   proc.stderr.strip(), False)
            raise SystemExit(1)
        return proc.stdout.strip()

    def ingest_token():
        # A client-credentials token from the stats-input client, for
        # STATS_INPUT_AUTH_MODE=oauth2. The secret travels to curl on stdin.
        keycloak = os.environ.get('KEYCLOAK_BASE_URL', 'http://localhost:8080')
        realm = env.get('STATS_REALM_NAME', 'statsapp')
        client = env.get('STATS_INPUT_CLIENT_ID', 'stats-input')
        url = f'{keycloak}/realms/{realm}/protocol/openid-connect/token'
        config = ('data = "grant_type=client_credentials"\n'
                  f'data = "client_id={client}"\n'
                  f'data = "client_secret={env.get("STATS_INPUT_CLIENT_SECRET", "")}"\n')
        proc = subprocess.run(['curl', '--silent', '--show-error', '--max-time', '30',
                               '--config', '-', url], input=config, text=True, capture_output=True)
        try:
            return json.loads(proc.stdout)['access_token']
        except (ValueError, KeyError):
            record('Ingest token acquisition', shlex.join(['curl', '--config', '-', url]),
                   'N/A (token request failed)', (proc.stderr or proc.stdout).strip(), False)
            raise SystemExit(1)

    ingest = ingest_token() if env.get('STATS_INPUT_AUTH_MODE') == 'oauth2' else None
    zra = token(username)
    alpha = token(env.get('STATS_ALPHA_READER_USERNAME', 'alpha-reader'))
    claims_proc = subprocess.run([str(ROOT / 'docker/token.sh'), username, '--claims'],
                                 capture_output=True, text=True, check=True)
    claims = json.loads(claims_proc.stdout)
    record('Keycloak claims', shlex.join(['./docker/token.sh', username, '--claims']),
           'N/A (helper decodes a successfully issued token)', claims_proc.stdout.strip(),
           claims.get('tenant') == expected_tenant and
           env.get('STATS_READ_ONLY_ROLE', 'stats.readOnly') in claims.get('roles', []) and
           env.get('STATS_API_AUDIENCE', 'stats-api') in claims.get('aud', []))
    if failures:
        raise SystemExit(1)
    now = int(time.time())
    run = uuid.uuid4().hex[:12]
    fixture = json.loads((ROOT / 'docs/schemas/fixtures/valid/lorastats-production-canonical.json').read_text())
    records = {}
    data_dir = args.report.parent / (args.report.stem + '-requests')
    data_dir.mkdir(parents=True, exist_ok=True)
    for label, tenant, parent in [('A', expected_tenant, expected_tenant),
                                   ('B', 'tenant-alpha', 'tenant-alpha'),
                                   ('C', 'tenant-alpha', expected_tenant)]:
        item = {k: v for k, v in fixture.items() if not k.startswith('$')}
        item.update(uid=f'ZRA-{run}-{label}', tenant=tenant, maintenant=parent,
                    clsbox=uuid.uuid4().hex[:12].upper(),
                    ts=dict(server=now, device=now, datastore=now, converter=now))
        records[label] = item
        (data_dir / f'{label}.json').write_text(json.dumps(item, indent=2) + '\n')

    def request(method, url, bearer=None, data=None):
        with tempfile.NamedTemporaryFile() as response:
            cmd = ['curl', '--silent', '--show-error', '--max-time', '30',
                   '--output', response.name, '--write-out', '%{http_code}', '-X', method]
            config = ''
            if bearer:
                cmd.extend(['--config', '-'])
                config += f'header = "Authorization: Bearer {bearer}"\n'
            if data:
                cmd.extend(['-H', 'Content-Type: application/json', '--data-binary', '@' + str(data)])
                if ingest:
                    if not bearer:
                        cmd.extend(['--config', '-'])
                    config += f'header = "Authorization: Bearer {ingest}"\n'
                elif env.get('STATS_INPUT_AUTH_MODE') != 'none':
                    raise RuntimeError('This check supports Input auth modes none/oauth2 only.')
            cmd.append(url)
            result = subprocess.run(cmd, input=config, text=True, capture_output=True, check=True)
            body = Path(response.name).read_text()
            # The response filename is temporary; show it as $BODY for replay.
            shown = cmd.copy()
            shown[shown.index('--output') + 1] = '$BODY'
            return int(result.stdout), body, shlex.join(shown)

    def exposed(body):
        try:
            value = json.loads(body)
        except ValueError:
            return False
        items = value.get('items', [value]) if isinstance(value, dict) else []
        return any(isinstance(item, dict) and
                   (item.get('uid') == records['C']['uid'] or
                    ('tenant' in item and item['tenant'] != expected_tenant)) for item in items)

    def stop_on_leak(status, body, command):
        if exposed(body):
            record('STOP: tenant isolation breach (foreign record exposed)', command, status, body, False)
            raise SystemExit('STOP: foreign tenant data appeared for zra-reader; see report.')

    for label in records:
        status, body, command = request('POST', input_url + '/internal/stats', data=data_dir / f'{label}.json')
        record('Load record ' + label, command, status, body, status == 200)
        if failures:
            raise SystemExit(1)
    # Input is asynchronous. Prove all records persisted before asserting exclusion.
    # In particular, B/C must be readable by alpha, otherwise 404 would prove nothing.
    for label, bearer in [('A', zra), ('B', alpha), ('C', alpha)]:
        deadline = time.monotonic() + 45
        while True:
            status, body, command = request('GET', api + '/v1/stats/lorastats/' + records[label]['uid'], bearer)
            if label == 'A':
                stop_on_leak(status, body, command)
            if status == 200 or time.monotonic() >= deadline:
                break
            time.sleep(1)
        record('Persistence prerequisite ' + label, command, status, body,
               status == 200 and json.loads(body).get('tenant') == records[label]['tenant'])
        if failures:
            raise SystemExit(1)

    list_path = f'/v1/stats/lorastats?from={now - 1}&to={now + 1}&limit=1000'
    paths = [(list_path, 200, 'List contains only A'),
             ('/v1/stats/lorastats/' + records['A']['uid'], 200, 'UID A is visible'),
             ('/v1/stats/lorastats/' + records['B']['uid'], 404, 'UID B is hidden'),
             ('/v1/stats/lorastats/' + records['C']['uid'], 404, 'UID C is hidden despite matching maintenant'),
             (f'/v1/stats/lorastats/{records["B"]["clsbox"]}/{now - 1}', 200, 'Gateway B is empty')]
    for index, (path, expected, label) in enumerate(paths):
        status, body, command = request('GET', api + path, zra)
        stop_on_leak(status, body, command)
        passed = status == expected
        if passed and index == 0:
            value = json.loads(body)
            passed = [item['uid'] for item in value['items']] == [records['A']['uid']] and value['nextCursor'] is None
        elif passed and index == 1:
            passed = json.loads(body).get('uid') == records['A']['uid']
        elif passed and index == 4:
            value = json.loads(body)
            passed = value['items'] == [] and value['nextCursor'] is None
        record(label, command, status, body, passed)
    for path, _, label in paths:
        status, body, command = request('GET', api + path)
        record('No token: ' + label, command, status, body, status == 401)
    report.extend([f'Result: {"FAIL" if failures else "PASS"}; {len(failures)} failed checks.', ''])
    save()
    raise SystemExit(bool(failures))


if __name__ == '__main__':
    main()
