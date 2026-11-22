#!/usr/bin/env python3
"""Offline checks of the real workflow signing step. Requires JDK keytool
for the key-generation tests (they skip without it). No network or GitHub
secrets are used; any key generation and git work happens only in temp dirs.
B3HMAN fork: no keystore is shipped with the repo - the first build must
generate a fresh B3HMAN key and persist it, never reuse upstream's public
CI key, so no test here may assume that key or its pin file exists.
"""
import base64
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


def signing_step():
    lines = (ROOT / '.github/workflows/build.yml').read_text().splitlines()
    start = next(i for i, line in enumerate(lines) if line.strip() == '- name: Prepare signing keystore (stable across builds)')
    run = next(i for i in range(start + 1, len(lines)) if lines[i].strip() == 'run: |')
    body = []
    for line in lines[run + 1:]:
        if line.strip() and not line.startswith('          '):
            break
        body.append(line[10:] if line.startswith('          ') else '')
    return '\n'.join(body) + '\n'


class AutoSigningTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.work = Path(self.tmp.name)
        self.runner = self.work / 'runner'
        self.runner.mkdir()
        self.env_file = self.work / 'github-env'
        self.env = dict(os.environ, RUNNER_TEMP=str(self.runner), GITHUB_ENV=str(self.env_file),
                        KEYSTORE_BASE64='', SECRET_KEYSTORE_PASSWORD='', SECRET_KEY_ALIAS='',
                        SECRET_KEY_PASSWORD='', GITHUB_REF_TYPE='branch', GITHUB_REF_NAME='main')

    def invoke(self, cwd=ROOT):
        return subprocess.run(['bash', '-euo', 'pipefail', '-c', signing_step()],
                              cwd=cwd, env=self.env, text=True, capture_output=True)

    def exports(self):
        return dict(line.split('=', 1) for line in self.env_file.read_text().splitlines())

    @unittest.skipIf(shutil.which('keytool') is None, 'keytool (JDK) is required')
    def test_no_secrets_generates_fresh_key_when_no_keystore(self):
        # Fork policy: with no keystore shipped, the first build mints a
        # fresh B3HMAN key and persists it for later builds.
        checkout = self.work / 'fresh'
        (checkout / '.github').mkdir(parents=True)
        subprocess.run(['git', 'init', '-q'], cwd=checkout, check=True)
        result = self.invoke(checkout)
        self.assertEqual(result.returncode, 0, result.stderr)
        values = self.exports()
        self.assertEqual(values['AETHER_SIGNING_MODE'], 'test')
        self.assertTrue(Path(values['KEYSTORE_PATH']).is_file())
        self.assertTrue((checkout / '.github/ci-keystore.jks.b64').is_file())
        self.assertIn('First build: generating a CI keystore', result.stdout)

    def test_optional_private_signing_still_has_priority(self):
        self.env.update(KEYSTORE_BASE64=base64.b64encode(b'b3hman-test-keystore').decode(),
                        SECRET_KEYSTORE_PASSWORD='test-placeholder', SECRET_KEY_ALIAS='test-placeholder',
                        SECRET_KEY_PASSWORD='test-placeholder')
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.exports()['AETHER_SIGNING_MODE'], 'release')
        self.assertEqual(Path(self.exports()['KEYSTORE_PATH']).name, 'release.keystore')

    @unittest.skipIf(not (ROOT / '.github/expected-signer-ci.txt').exists(),
                     'fork ships no pinned CI signer until its first build')
    def test_missing_key_with_existing_pin_fails_instead_of_rotating(self):
        checkout = self.work / 'missing';(checkout / '.github').mkdir(parents=True)
        shutil.copy2(ROOT / '.github/expected-signer-ci.txt', checkout / '.github/expected-signer-ci.txt')
        result = self.invoke(checkout)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('is missing', result.stdout)
        self.assertFalse((self.runner / 'ci-release.keystore').exists())

    def test_corrupt_base64_fails_closed(self):
        checkout = self.work / 'corrupt';(checkout / '.github').mkdir(parents=True)
        (checkout / '.github/ci-keystore.jks.b64').write_text('not-valid-base64!')
        self.assertNotEqual(self.invoke(checkout).returncode, 0)

    def test_signer_comparison_remains_enabled(self):
        text = (ROOT / '.github/workflows/build.yml').read_text()
        self.assertTrue('if [ "$EXPECTED_CI" != "$SHA" ]; then' in text, 'CI fingerprint comparison must remain enabled')
        self.assertIn('ERROR: the CI signing certificate changed!', text)
        self.assertNotIn('continue-on-error: true', text[text.index('- name: Prepare signing keystore'):text.index('- name: Prepare signing keystore') + 100])


if __name__ == '__main__':
    unittest.main(verbosity=2)
