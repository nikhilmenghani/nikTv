"""Generate silent testsrc HLS/DASH/VOD assets; requires ffmpeg on PATH.

Run from any directory: python tools/generate-playback-fixtures.py
The generated ZIP belongs to androidTest only and never uses provider media.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

repo = Path(__file__).resolve().parents[1]
build = (repo / "build").resolve()
assert build.is_relative_to(repo)
build.mkdir(exist_ok=True)
ffmpeg = shutil.which("ffmpeg")
if not ffmpeg:
    raise SystemExit("ffmpeg is required to regenerate the local playback fixtures")

with tempfile.TemporaryDirectory(prefix="playback-fixtures-", dir=build) as tmp:
    root = Path(tmp).resolve()
    assert root.is_relative_to(build)
    def run(*args):
        subprocess.run([ffmpeg, "-y", "-hide_banner", "-loglevel", "error", *args], check=True)
    source = ["-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo"]
    encode = ["-c:v", "libx264", "-preset", "ultrafast", "-b:v", "250k", "-g", "48", "-c:a", "aac", "-b:a", "32k"]
    run(*source, "-t", "120", *encode, "-f", "hls", "-hls_time", "2", "-hls_list_size", "0",
        "-hls_segment_filename", str(root / "seg%03d.ts"), str(root / "full.m3u8"))
    run(*source, "-t", "20", *encode, "-movflags", "+faststart", str(root / "movie.mp4"))
    run("-i", str(root / "movie.mp4"), "-c", "copy", "-f", "dash", "-seg_duration", "2", str(root / "manifest.mpd"))
    output = repo / "app/src/androidTest/assets/playback-fixtures.zip"
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(root.iterdir()):
            info = zipfile.ZipInfo(file.name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, file.read_bytes())
    print(f"Generated {output.relative_to(repo)} ({output.stat().st_size} bytes)")
