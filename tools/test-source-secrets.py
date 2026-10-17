import importlib.util
from pathlib import Path
import unittest
import hashlib

spec = importlib.util.spec_from_file_location("checker", Path(__file__).with_name("check-source-secrets.py"))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class SourceCredentialTests(unittest.TestCase):
    def test_detects_without_returning_values(self):
        for value in ("ya" + "29." + "a" * 40, "1/" + "/" + "b" * 40,
                      "AI" + "za" + "c" * 35, "gh" + "p_" + "d" * 36):
            result = checker.findings(("first line\n" + value).encode())
            self.assertEqual(len(result), 1)
            self.assertEqual(result[0][1], 2)
            self.assertNotIn(value, str(result))

    def test_environment_references_are_safe(self):
        self.assertEqual(checker.findings(b'BuildConfig.NIKTUBE_BOTGUARD_API_KEY {{NIKTUBE_OAUTH_ACCESS_TOKEN}}'), [])

    def test_binary_content_is_not_skipped(self):
        self.assertTrue(checker.findings(b'\x00' + ("ya" + "29." + "x" * 40).encode()))

    def test_known_nonstandard_credential_is_detected(self):
        value = b"fixture_" + b"z" * 60 + b"."
        digest = hashlib.sha256(value).hexdigest()
        checker.KNOWN_HASHES.add(digest)
        try:
            self.assertEqual(checker.findings(b'key="' + value + b'"'), [("Known upstream credential", 1)])
        finally:
            checker.KNOWN_HASHES.remove(digest)


if __name__ == "__main__":
    unittest.main()
