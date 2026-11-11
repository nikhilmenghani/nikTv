"""Offline guard for the publication prerequisites. Does not invoke GitHub."""
from pathlib import Path

for filename in ("android_build.yml", "android_dev_build.yml"):
    text = (Path(__file__).resolve().parents[1] / ".github/workflows" / filename).read_text()
    if filename == "android_build.yml":
        assert "needs: [prepare, validation, apk]" in text
        assert "check: [unit, debug-lint, release-lint]" in text
        assert "distribution: [standard, firetv]" in text
        assert text.count("    needs: prepare") == 2
        assert "bash tools/ci-unit-diagnostics.sh" in text and "timeout-minutes: 10" in text
        assert ":app:lintDebug" in text and ":app:lintRelease" in text
        assert "continue-on-error" not in text and "always()" not in text
        assert "Verify pre-created release tag" in text and '\"$TARGET\" == \"$GITHUB_SHA\"' in text
        assert "uses: actions/download-artifact@v4" in text and "Verify complete APK set" in text
        assert "target_commitish: ${{ github.sha }}" in text
        assert "compression-level: 0" in text
        print(f"PASS {filename}: parallel validation and isolated APK builds gate publication")
        continue
    validate = text.index("- name: Unit tests")
    publish = text.index("- name: Publish ")
    assert text.index("uses: actions/checkout@v4") < validate < text.index("- name: Configure signing") < publish
    validation = text[validate:text.index("- name: Configure signing")]
    unit_command = ":app:testDebugUnitTest" in validation
    bounded_units = "bash tools/ci-unit-diagnostics.sh" in validation
    if bounded_units:
        runner = (Path(__file__).resolve().parent / "ci-unit-diagnostics.sh").read_text()
        assert ":app:testDebugUnitTest" in runner and "exit \"$result\"" in runner
        assert "timeout --signal=TERM" in runner and "timeout-minutes: 10" in validation
    assert (unit_command or bounded_units) and ":app:lintDebug" in validation
    assert "continue-on-error" not in text and "always()" not in text
    assert "target_commitish: ${{ github.sha }}" in text[publish:]
    assert text.index("- name: Verify release tag ownership") < text.index("- name: Configure signing")
    assert '"$EXISTING" == "$GITHUB_SHA"' in text
    if filename == "android_build.yml": assert ":app:lintRelease" in validation
    if filename == "android_build.yml":
        assert text.index("- name: Verify pre-created release tag") < validate
        assert '"$TARGET" == "$GITHUB_SHA"' in text
        assert text.index("uses: actions/upload-artifact@v4") < publish
    assert "branches: [main]" in text and "workflow_dispatch:" in text
    assert "contents: write" in text  # Required by action-gh-release.
    print(f"PASS {filename}: validation gates publication and tag uses the shipping SHA")
