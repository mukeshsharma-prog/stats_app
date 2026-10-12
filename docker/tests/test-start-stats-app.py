#!/usr/bin/env python3
"""Exercise startup credential safety without touching real Docker resources."""
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
    if not exists:
        sys.exit(1)
    template = a[2]
    if 'com.docker.compose.project' in template:
        print('stats-app-bundle')
    elif template == '{{.Image}}':
        print('sha256:existing-postgres')
    else:
        print('healthy')
elif a[0] == 'run':
    sys.exit(int(os.environ['FAKE_AUTH_FAILURE']))
elif a[0] == 'compose':
    (root / 'started').touch()
elif a[0] == 'exec':
    print('{} => 4 @ 1')
elif a[0] not in ['load', 'logs']:
    sys.exit(2)
'''


class StartupCredentialsTest(unittest.TestCase):
    def exercise(self, volumes=False, postgres=False, auth_failure=False, env_exists=False):
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
        (root / 'stats-app-images.tar').touch()
        if env_exists:
            (root / '.env').write_text('POSTGRES_USER=test\nPOSTGRES_PASSWORD=test-only\n')
        env = dict(os.environ, PATH=str(fake.parent) + ':' + os.environ['PATH'],
                   FAKE_ROOT=str(root), FAKE_VOLUMES=str(int(volumes)),
                   FAKE_POSTGRES=str(int(postgres)), FAKE_AUTH_FAILURE=str(int(auth_failure)))
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


if __name__ == '__main__':
    unittest.main()
