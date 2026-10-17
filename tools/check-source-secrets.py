"""Fail closed on credential-shaped source values; never print matching values."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys

PATTERNS = {
    "Google OAuth access token": re.compile(r"ya29\.[A-Za-z0-9_-]{20,}"),
    "Google OAuth refresh token": re.compile(r"1//[A-Za-z0-9_-]{30,}"),
    "Google API key": re.compile(r"AIza[0-9A-Za-z_-]{35}"),
    "GitHub token": re.compile(r"(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{30,})"),
    "Private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
}
KNOWN_HASHES = set(json.loads(Path(__file__).with_name("known-upstream-credential-hashes.json").read_text()))


def findings(content):
    result = [(kind, content.count(b"\n", 0, match.start()) + 1)
            for kind, pattern in PATTERNS.items()
            for match in re.finditer(pattern.pattern.encode(), content)]
    for match in re.finditer(rb"[A-Za-z0-9_.-]{40,}", content):
        if hashlib.sha256(match.group()).hexdigest() in KNOWN_HASHES:
            result.append(("Known upstream credential", content.count(b"\n", 0, match.start()) + 1))
    return result


def main():
    root = Path(__file__).resolve().parents[1]
    files = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
    found = 0
    for relative in filter(None, files):
        path = root / relative
        if not path.is_file():
            continue
        for kind, line in findings(path.read_bytes()):
            print(f"{relative}:{line}: {kind} must come from private configuration")
            found += 1
    print(f"Source credential scan: {found} findings")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
