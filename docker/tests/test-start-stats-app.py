#!/usr/bin/env python3
"""Exercise startup credential safety and monitoring readiness without real Docker."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


DOCKER = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
a = sys.argv[1:]
root = pathlib.Path(os.environ['FAKE_ROOT'])
with (root / 'calls.jsonl').open('a') as f:
    f.write(json.dumps(a) + '\n')
exists = os.environ['FAKE_POSTGRES'] == '1' or (root / 'started').exists()
if a[0] == 'info':
    pass
elif a[:2] == ['volume', 'ls']:
    if os.environ['FAKE_VOLUMES'] == '1' and any(x.endswith('=postgres-data') for x in a):
        print('stats-app-bundle_postgres-data')
elif a[0] == 'inspect':
    if a[3] == 'stats-keycloak' and ('com.docker.compose.project' in a[2] or '.Mounts' in a[2]):
        if os.environ.get('FAKE_LEGACY_KEYCLOAK') == '1':
            if 'com.docker.compose.project' in a[2]:
                print('stats-app-bundle')
            sys.exit(0)
        sys.exit(1)
    if not exists:
        sys.exit(1)
    template = a[2]
    if 'com.docker.compose.project' in template:
        print('stats-app-bundle')
    elif template == '{{.Image}}':
        print('sha256:existing-postgres')
    elif a[3] == os.environ.get('FAKE_UNHEALTHY'):
        print('unhealthy')
    else:
        print('healthy')
elif a[0] == 'version':
    print(os.environ.get('FAKE_ARCH', 'arm64'))
elif a[0] == 'run':
    sys.exit(int(os.environ['FAKE_AUTH_FAILURE']))
elif a[0] == 'compose':
    (root / 'started').touch()
elif a[0] == 'exec':
    if os.environ.get('FAKE_MONITORING_DOWN') != '1':
        print('{} => 4 @ 1')
elif a[0] not in ['load', 'logs']:
    sys.exit(2)
'''


class StartupCredentialsTest(unittest.TestCase):
    def exercise(self, volumes=False, postgres=False, auth_failure=False, env_exists=False,
                 unhealthy='', monitoring_down=False, arch='arm64', legacy_keycloak=False,
                 archives=('stats-app-images-amd64.tar', 'stats-app-images-arm64.tar')):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / 'docker').mkdir()
        source = Path(__file__).resolve().parents[1]
        for name in ['start-stats-app.sh', 'check-monitoring.sh']:
            shutil.copy2(source / name, root / 'docker' / name)
        fake = root / 'bin' / 'docker'
        fake.parent.mkdir()
        fake.write_text(DOCKER)
        fake.chmod(0o755)
        for archive in archives:
            (root / archive).touch()
        if env_exists:
            (root / '.env').write_text('POSTGRES_USER=test\nPOSTGRES_PASSWORD=test-only\n')
        env = dict(os.environ, PATH=str(fake.parent) + ':' + os.environ['PATH'],
                   FAKE_ROOT=str(root), FAKE_VOLUMES=str(int(volumes)),
                   FAKE_POSTGRES=str(int(postgres)), FAKE_AUTH_FAILURE=str(int(auth_failure)),
                   FAKE_UNHEALTHY=unhealthy, FAKE_MONITORING_DOWN=str(int(monitoring_down)),
                   FAKE_ARCH=arch, FAKE_LEGACY_KEYCLOAK=str(int(legacy_keycloak)),
                   MONITORING_TIMEOUT_SECONDS='1')
        result = subprocess.run(['sh', str(root / 'docker/start-stats-app.sh')],
                                env=env, text=True, capture_output=True, timeout=15)
        calls = [json.loads(line) for line in (root / 'calls.jsonl').read_text().splitlines()]
        return root, result, calls

    def test_existing_volume_without_env_never_generates_credentials_or_changes_containers(self):
        root, result, calls = self.exercise(volumes=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("previous deployment's .env", result.stderr)
        self.assertFalse((root / '.env').exists())
        self.assertFalse(any(c[0] in ['run', 'load', 'compose'] for c in calls))

    def test_wrong_credentials_stop_before_image_loading_or_container_changes(self):
        root, result, calls = self.exercise(volumes=True, postgres=True,
                                            auth_failure=True, env_exists=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Cannot authenticate', result.stderr)
        self.assertEqual((root / '.env').read_text(), 'POSTGRES_USER=test\nPOSTGRES_PASSWORD=test-only\n')
        self.assertTrue(any(c[0] == 'run' for c in calls))
        self.assertFalse(any(c[0] in ['load', 'compose'] for c in calls))

    def test_matching_credentials_allow_upgrade_and_monitoring_readiness(self):
        _, result, calls = self.exercise(volumes=True, postgres=True, env_exists=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('Stats App is ready', result.stdout)
        self.assertLess(next(i for i,c in enumerate(calls) if c[0] == 'run'),
                        next(i for i,c in enumerate(calls) if c[0] == 'compose'))
        self.assertTrue(any(c[0] == 'exec' for c in calls))

    def test_fresh_install_still_generates_private_credentials_and_starts(self):
        root, result, calls = self.exercise()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((root / '.env').stat().st_mode & 0o777, 0o600)
        self.assertIn('POSTGRES_RO_PASSWORD=', (root / '.env').read_text())
        self.assertTrue(any(c[0] == 'compose' for c in calls))

    def test_legacy_keycloak_is_not_replaced_before_migration(self):
        _, result, calls = self.exercise(postgres=True, env_exists=True, legacy_keycloak=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('migrate-keycloak-data.sh', result.stderr)
        self.assertFalse(any(c[0] == 'compose' for c in calls))

    def test_degraded_monitoring_reports_ready_and_exits_three(self):
        # DL-167: every application is healthy, so the stack is usable and says so;
        # the monitoring problem is printed, not hidden, and gets its own exit status.
        _, result, _ = self.exercise(monitoring_down=True)
        self.assertEqual(result.returncode, 3, result.stderr)
        self.assertIn('Stats App is ready', result.stdout)
        self.assertIn('monitoring is DEGRADED', result.stderr)

    def test_monitoring_is_up_before_the_applications_are_waited_for(self):
        # DL-167: an app that fails must find Prometheus already running to show it.
        _, result, calls = self.exercise(unhealthy='stats-api')
        self.assertEqual(result.returncode, 1)
        self.assertIn('9090/targets shows stats-api as down', result.stderr)
        waited = [c[3] for c in calls if c[0] == 'inspect' and 'State.Health' in c[2]]
        self.assertLess(waited.index('stats-prometheus'), waited.index('stats-input'))

    def test_loads_the_image_archive_matching_the_engine_architecture(self):
        for arch in ['amd64', 'arm64']:
            with self.subTest(arch=arch):
                root, result, calls = self.exercise(arch=arch)
                self.assertEqual(result.returncode, 0, result.stderr)
                loads = [c for c in calls if c[0] == 'load']
                self.assertEqual(loads, [['load', '-i', str(root / f'stats-app-images-{arch}.tar')]])

    def test_older_single_archive_bundle_still_loads(self):
        root, result, calls = self.exercise(archives=('stats-app-images.tar',))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(['load', '-i', str(root / 'stats-app-images.tar')], calls)

    def test_missing_architecture_stops_before_any_container_change(self):
        _, result, calls = self.exercise(arch='amd64', archives=('stats-app-images-arm64.tar',))
        self.assertEqual(result.returncode, 1)
        self.assertIn('no images for this Docker engine', result.stderr)
        self.assertFalse(any(c[0] in ['load', 'compose'] for c in calls))


class MonitoringIsIndependentTest(unittest.TestCase):
    """Prometheus must never wait for the health of what it monitors (DL-167)."""

    def test_prometheus_depends_on_targets_being_started_never_healthy(self):
        import yaml
        compose = Path(__file__).resolve().parents[1] / 'docker-compose.yml'
        services = yaml.safe_load(compose.read_text())['services']
        depends = services['prometheus']['depends_on']
        self.assertTrue({'stats-input', 'stats-writer', 'stats-api'} <= set(depends))
        for target, rule in depends.items():
            with self.subTest(target=target):
                self.assertEqual(rule['condition'], 'service_started')


if __name__ == '__main__':
    unittest.main()
