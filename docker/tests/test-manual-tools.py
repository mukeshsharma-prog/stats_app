#!/usr/bin/env python3
"""The hand-run tools of the Docker bundle (DL-171), without Docker or Keycloak.

A fake curl stands in for Keycloak. The case that matters is the one that produced the
13 Oct report: a .env from another stack, Keycloak refusing the password, and the old
pasted commands decoding the string "null" into a jq parse error that pointed nowhere.
"""
import base64
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1]
REPO = SOURCE.parent

CLAIMS = {'tenant': 'tenant-alpha', 'aud': 'stats-api', 'realm_access': {'roles': ['stats.readOnly']}}


def jwt(claims):
    def part(obj):
        return base64.urlsafe_b64encode(json.dumps(obj).encode()).decode().rstrip('=')
    return part({'alg': 'RS256'}) + '.' + part(claims) + '.signature'


CURL = r'''#!/usr/bin/env python3
import json, os, sys
from urllib.parse import parse_qs
args = sys.argv[1:]
with open(os.environ['FAKE_CALLS'], 'a') as f:
    f.write(json.dumps(args) + '\n')
form = parse_qs(sys.stdin.read())
with open(os.environ['FAKE_FORMS'], 'a') as f:
    f.write(json.dumps(form) + '\n')
password = form['password'][0]
if password == os.environ['FAKE_GOOD_PASSWORD']:
    print(json.dumps({'access_token': os.environ['FAKE_TOKEN'], 'token_type': 'Bearer'}))
else:
    print(json.dumps({'error': 'invalid_grant', 'error_description': 'Invalid user credentials'}))
'''


class ManualToolsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'docker').mkdir()
        for name in ['token.sh', 'stats-helpers.sh']:
            shutil.copy2(SOURCE / name, self.root / 'docker' / name)
        fake = self.root / 'bin' / 'curl'
        fake.parent.mkdir()
        fake.write_text(CURL)
        fake.chmod(0o755)
        # A claims payload whose base64url length is not a multiple of 4, so the
        # padding the decoder restores is exercised.
        claims = dict(CLAIMS)
        while len(jwt(claims).split('.')[1]) % 4 == 0:
            claims['pad'] = claims.get('pad', '') + 'x'
        self.token = jwt(claims)
        self.env = dict(os.environ, PATH=f"{fake.parent}:{os.environ['PATH']}",
                        FAKE_CALLS=str(self.root / 'calls.jsonl'), FAKE_FORMS=str(self.root / 'forms.jsonl'), FAKE_TOKEN=self.token,
                        FAKE_GOOD_PASSWORD='right-one')
        self.env.pop('STATS_ENV_FILE', None)
        self.write_env('right-one')

    def write_env(self, alpha_password):
        (self.root / '.env').write_text(
            'STATS_REALM_NAME=statsapp\nSTATS_DEV_CLI_CLIENT_ID=stats-dev-cli\n'
            f'STATS_ALPHA_READER_PASSWORD={alpha_password}\nSTATS_NO_ROLE_PASSWORD=other\n')

    def run_token(self, *args, cwd=None):
        return subprocess.run(['sh', str(self.root / 'docker/token.sh'), *args], env=self.env,
                              cwd=cwd or tempfile.gettempdir(), text=True, capture_output=True, timeout=15)

    def calls(self):
        return [json.loads(line) for line in (self.root / 'calls.jsonl').read_text().splitlines()]

    def forms(self):
        return [json.loads(line) for line in (self.root / 'forms.jsonl').read_text().splitlines()]

    def test_token_reads_this_bundles_env_from_any_directory(self):
        result = self.run_token('alpha-reader')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), self.token)
        self.assertEqual(self.forms()[0]['password'], ['right-one'])
        self.assertEqual(self.forms()[0]['client_id'], ['stats-dev-cli'])
        self.assertTrue(any(a.endswith('/realms/statsapp/protocol/openid-connect/token') for a in self.calls()[0]))

    def test_user_name_maps_to_its_password_variable(self):
        self.run_token('no-role')
        self.assertEqual(self.forms()[0]['password'], ['other'])

    def test_a_user_renamed_in_env_finds_its_password_by_the_username_key(self):
        with (self.root / '.env').open('a') as f:
            f.write('STATS_ALPHA_READER_USERNAME=alice\n')
        result = self.run_token('alice')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.forms()[0]['password'], ['right-one'])
        self.assertEqual(self.forms()[0]['username'], ['alice'])

    @unittest.skipUnless(shutil.which('jq'), 'jq not installed')
    def test_claims_restore_base64url_padding(self):
        result = self.run_token('alpha-reader', '--claims')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout),
                         {'tenant': 'tenant-alpha', 'aud': 'stats-api', 'roles': ['stats.readOnly']})

    def test_refused_password_names_keycloaks_reason_and_the_likely_cause(self):
        self.write_env('from-another-stack')
        result = self.run_token('alpha-reader', '--claims')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, '')
        self.assertIn('Invalid user credentials', result.stderr)
        self.assertIn('different', result.stderr)

    def test_unknown_user_without_terminal_fails_without_calling_keycloak(self):
        result = self.run_token('nobody')
        self.assertEqual(result.returncode, 1)
        self.assertFalse((self.root / 'calls.jsonl').exists())

    def helpers(self, script, cwd):
        return subprocess.run(['bash', '-c', '. docker/stats-helpers.sh >/dev/null && ' + script],
                              env=dict(self.env, STATS_TOOLS_CONTAINER='true'), cwd=cwd,
                              text=True, capture_output=True, timeout=15)

    @unittest.skipUnless(shutil.which('jq'), 'jq not installed')
    def test_helpers_tok_and_claims(self):
        result = self.helpers('claims "$(tok alpha-reader)"', self.root)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)['tenant'], 'tenant-alpha')

    @unittest.skipUnless(shutil.which('jq'), 'jq not installed')
    def test_helpers_tok_refusal_is_not_the_string_null(self):
        self.write_env('from-another-stack')
        result = self.helpers('A=$(tok alpha-reader) || echo "refused"; printf "[%s]" "${A:-}"', self.root)
        self.assertIn('refused', result.stdout)
        self.assertIn('[]', result.stdout)
        self.assertNotIn('null', result.stdout)
        self.assertIn('Invalid user credentials', result.stderr)

    def test_helpers_refuse_outside_a_directory_with_env(self):
        elsewhere = self.root / 'elsewhere'
        (elsewhere / 'docker').mkdir(parents=True)
        shutil.copy2(SOURCE / 'stats-helpers.sh', elsewhere / 'docker' / 'stats-helpers.sh')
        result = subprocess.run(['bash', '-c', '. docker/stats-helpers.sh; echo "status $?"'],
                                env=self.env, cwd=elsewhere, text=True, capture_output=True, timeout=15)
        self.assertIn('status 1', result.stdout)
        self.assertIn('no .env', result.stderr)


class BundleCarriesTheWalkthroughTest(unittest.TestCase):
    """Everything MANUAL.md tells the reader to run is in the bundle and in the tools image."""

    def test_every_script_the_manual_names_is_packaged(self):
        manual = (SOURCE / 'MANUAL.md').read_text()
        package = (SOURCE / 'package-bundle.sh').read_text()
        named = set(re.findall(r'docker/([a-z-]+\.sh)', manual))
        self.assertTrue({'stats-helpers.sh', 'shell.sh', 'token.sh', 'run-demo.sh'} <= named, named)
        for script in named:
            self.assertIn(f'"$ROOT/docker/{script}"', package, script)
        self.assertIn('"$ROOT/docker/MANUAL.md" "$PAYLOAD/MANUAL.md"', package)
        self.assertIn('stats-app/stats-tools:dev', package.split('docker save', 1)[1])

    def test_tools_image_carries_the_demo_its_fixtures_and_the_helpers(self):
        dockerfile = (SOURCE / 'Dockerfile.tools').read_text()
        allow = (SOURCE / 'Dockerfile.tools.dockerignore').read_text()
        for path in ['docs/schemas/fixtures', 'docker/demo.sh', 'docker/stats-helpers.sh',
                     'docker/tools-entrypoint.sh', 'docker/token.sh']:
            self.assertIn(f'COPY {path}', dockerfile, path)
            self.assertIn(f'!{path}', allow, path)
        self.assertNotIn('.env', [line.strip() for line in allow.splitlines()])

    def test_pasted_blocks_carry_no_comments(self):
        # An interactive zsh (macOS's default shell) without `setopt interactivecomments`
        # passes a pasted "# ..." to the command as more arguments: curl then fetches "#".
        # The pages are pasted, never run as scripts, so a comment is a broken command.
        for page in ['MANUAL.md', 'BUNDLE.md', '../postman/README.md']:
            text = (SOURCE / page).read_text()
            for language, block in re.findall(r'```([^\n]*)\n(.*?)```', text, re.S):
                if language not in ('', 'bash', 'sh'):
                    continue
                for line in block.splitlines():
                    self.assertIsNone(re.search(r'(^|\s)#', line), f'{page}: {line}')

    def test_every_fixture_the_demo_posts_exists(self):
        demo = (SOURCE / 'demo.sh').read_text()
        fixtures = set(re.findall(r'\$FX/([\w/.-]+\.json)', demo))
        self.assertTrue(fixtures)
        for fixture in fixtures:
            self.assertTrue((REPO / 'docs/schemas/fixtures' / fixture).is_file(), fixture)


if __name__ == '__main__':
    unittest.main()
