"""Provision NikTV's private sync repository without emitting credentials."""
import base64
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request


def main():
    root = Path(__file__).resolve().parents[1]
    token = ""
    for path in [root / "gradle.properties", Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "gradle.properties"]:
        if path.is_file():
            for line in path.read_text(encoding="utf-8-sig").splitlines():
                if "=" in line and not line.lstrip().startswith(("#", "!")):
                    key, value = line.split("=", 1)
                    if key.strip().lower() == "g_token":
                        token = value.strip().strip("\"'")
    if not token:
        raise RuntimeError("No g_token property found in project or user Gradle configuration.")

    def api(method, path, payload=None, missing=False):
        request = urllib.request.Request("https://api.github.com" + path, method=method,
            headers={"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2026-03-10", "User-Agent": "NikTV-provisioning"},
            data=None if payload is None else json.dumps(payload).encode())
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code == 404 and missing:
                return None
            capability = "Contents read/write on niktv-data"
            if path == "/user/repos":
                capability = "private repository creation: classic repo scope, or fine-grained Administration/Repository creation write"
            if error.code == 401:
                capability = "valid, unexpired authentication for GET /user"
            raise RuntimeError(f"GitHub HTTP {error.code}; required capability: {capability}.") from None

    user = api("GET", "/user")
    path = f"/repos/{user['login']}/niktv-data"
    repo = api("GET", path, missing=True)
    created = repo is None
    if created:
        repo = api("POST", "/user/repos", {"name": "niktv-data", "private": True,
                   "description": "Private NikTV account sync data", "auto_init": True})
    if not repo.get("private"):
        raise RuntimeError("Existing niktv-data is public; refusing to write.")
    manifest_file = api("GET", path + "/contents/manifest.json", missing=True)
    if manifest_file:
        manifest = json.loads(base64.b64decode(manifest_file["content"]))
        if manifest.get("application") != "niktv" or manifest.get("schemaVersion") != 1:
            raise RuntimeError("Existing repository has an unrelated or unsupported application manifest; refusing to write.")
        if str(manifest.get("accountId")) != str(user["id"]):
            raise RuntimeError("Application manifest account identity mismatch; refusing to write.")
    elif not created:
        raise RuntimeError("Existing repository has no NikTV application marker; refusing to overwrite unrelated contents.")
    else:
        manifest = {"application": "niktv", "schemaVersion": 1, "accountId": str(user["id"]),
                    "dataFile": "account.json"}
        api("PUT", path + "/contents/manifest.json", {"message": "Initialize NikTV sync manifest",
            "branch": repo["default_branch"], "content": base64.b64encode((json.dumps(manifest, indent=2) + "\n").encode()).decode()})
        readme = "# NikTV private account data\n\nVersion 1 NikTV sync repository. Authentication tokens, portal credentials, resolved URLs and downloaded media must never be stored here.\n"
        existing = api("GET", path + "/contents/README.md")
        api("PUT", path + "/contents/README.md", {"message": "Describe NikTV private sync repository",
            "branch": repo["default_branch"], "sha": existing["sha"],
            "content": base64.b64encode(readme.encode()).decode()})
    print(json.dumps({"url": repo["html_url"], "private": True, "defaultBranch": repo["default_branch"],
                      "accountId": str(user["id"]), "created": created, "manifestValidated": True}))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        if isinstance(error, RuntimeError):
            print(str(error), file=sys.stderr)
        else:
            print("Provisioning failed (" + type(error).__name__ + "); credentials were not logged.", file=sys.stderr)
        sys.exit(1)
