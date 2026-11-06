"""Offline guard for the publication prerequisites. Does not invoke GitHub."""
from pathlib import Path

for filename in ("android_build.yml", "android_dev_build.yml"):
    text = (Path(__file__).resolve().parents[1] / ".github/workflows" / filename).read_text()
    validate = text.index("- name: Validate shipping sources")
    publish = text.index("- name: Publish ")
    assert text.index("uses: actions/checkout@v4") < validate < text.index("- name: Configure signing") < publish
    validation = text[validate:text.index("- name: Configure signing")]
    assert ":app:testDebugUnitTest" in validation and ":app:lintDebug" in validation
    assert "continue-on-error" not in text and "always()" not in text
    assert "target_commitish: ${{ github.sha }}" in text[publish:]
    assert text.index("- name: Verify release tag ownership") < text.index("- name: Configure signing")
    assert '"$EXISTING" == "$GITHUB_SHA"' in text
    if filename == "android_build.yml": assert ":app:lintRelease" in validation
    assert "branches: [main]" in text and "workflow_dispatch:" in text
    assert "contents: write" in text  # Required by action-gh-release.
    print(f"PASS {filename}: validation gates publication and tag uses the shipping SHA")
