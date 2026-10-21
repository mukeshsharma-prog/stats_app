#!/usr/bin/env python3
"""Verify arbitrary users and form encoding without contacting Keycloak."""
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from urllib.parse import parse_qs

CURL = '''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
Path(os.environ['FORM_FILE']).write_text(sys.stdin.read())
print(json.dumps({'access_token':os.environ['TEST_TOKEN']}) if not os.environ.get('DENY') else json.dumps({'error':'invalid_grant','error_description':'Invalid user credentials'}))
'''


class TokenTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        (self.root / 'docker').mkdir()
        shutil.copy2(Path(__file__).resolve().parents[1] / 'token.sh', self.root / 'docker/token.sh')
        (self.root / 'bin').mkdir()
        curl = self.root / 'bin/curl'
        curl.write_text(CURL)
        curl.chmod(0o755)
        claims = {'tenant':'zra_test', 'aud':['stats-api'], 'realm_access':{'roles':['stats.readOnly']}}
        payload = base64.urlsafe_b64encode(json.dumps(claims).encode()).decode().rstrip('=')
        self.token = 'header.' + payload + '.signature'
        self.env = dict(os.environ, PATH=str(self.root / 'bin') + ':' + os.environ['PATH'],
                        FORM_FILE=str(self.root / 'form'), TEST_TOKEN=self.token)

    def run_token(self, *args, password=''):
        return subprocess.run(['sh', str(self.root / 'docker/token.sh'), *args], input=password,
                              env=self.env, capture_output=True, text=True, timeout=10)

    def form(self):
        return parse_qs((self.root / 'form').read_text())

    def test_arbitrary_keycloak_user_without_env_and_special_password(self):
        password = 'space & plus+ percent% equals= unicodeé'
        result = self.run_token('console-user', '--password-stdin', password=password + '\n')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), self.token)
        self.assertEqual(self.form()['username'], ['console-user'])
        self.assertEqual(self.form()['password'], [password])
        self.assertNotIn(password, result.stderr)

    def test_fixture_credentials_remain_compatible(self):
        (self.root / '.env').write_text('STATS_NEW_USERNAME=renamed-reader\nSTATS_NEW_PASSWORD=fixture-secret\n')
        result = self.run_token('renamed-reader')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.form()['password'], ['fixture-secret'])

    def test_stdin_overrides_stale_fixture_password(self):
        (self.root / '.env').write_text('STATS_NEW_USERNAME=reader\nSTATS_NEW_PASSWORD=stale\n')
        result = self.run_token('reader', '--password-stdin', password='current\n')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.form()['password'], ['current'])

    def test_claims_mode_does_not_print_bearer_token(self):
        result = self.run_token('reader', '--claims', '--password-stdin', password='secret\n')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['tenant'], 'zra_test')
        self.assertNotIn(self.token, result.stdout)

    def test_invalid_credentials_fail_without_token_or_password(self):
        self.env['DENY'] = '1'
        result = self.run_token('reader', '--password-stdin', password='wrong-secret\n')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')
        self.assertIn('Invalid user credentials', result.stderr)
        self.assertNotIn('wrong-secret', result.stderr)

    def test_empty_stdin_fails_before_request(self):
        result = self.run_token('reader', '--password-stdin', password='\n')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / 'form').exists())


if __name__ == '__main__':
    unittest.main()
