"""Physical-device Home scroll sample; run on an already loaded Dev Home.

Raw gfxinfo is retained so refresh deadlines and UI/GPU timing can be audited.
This intentionally measures the installed app, including its real artwork cache.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time

p = argparse.ArgumentParser()
p.add_argument("label")
p.add_argument("--serial", default="6889f3db")
p.add_argument("--activity", default="MainActivity")
p.add_argument("--axis", choices=["vertical", "horizontal"], default="vertical")
args = p.parse_args()
adb = ["C:/platform-tools/adb.exe", "-s", args.serial, "shell"]

def shell(*cmd):
    return subprocess.check_output(adb + list(cmd), timeout=25).decode(errors="replace")

def check_screen():
    focus = next((line for line in shell("dumpsys", "window").splitlines() if "mCurrentFocus=" in line), "")
    if "com.nikhil.niktv.debug" not in focus or args.activity not in focus:
        raise RuntimeError("Stopped: comparison Home is no longer foreground: " + focus)

check_screen()
shell("dumpsys", "gfxinfo", "com.nikhil.niktv.debug", "reset")
for reverse in [False, False, True, True] * 2:
    check_screen()
    if args.axis == "vertical":
        start, end = ((1800, 1250), (1800, 480))
    else:
        start, end = ((2220, 950), (650, 950))
    if reverse:
        start, end = end, start
    shell("input", "swipe", *map(str, (*start, *end, 500)))
time.sleep(.25)
raw = shell("dumpsys", "gfxinfo", "com.nikhil.niktv.debug", "framestats")
out = Path("build/home-scroll")
out.mkdir(parents=True, exist_ok=True)
(out / (args.label + ".txt")).write_text(raw, encoding="utf-8")
seen = set()
summary = []
for line in raw.splitlines():
    line = line.strip()
    # gfxinfo repeats per-window sections after the process-wide summary.
    metric = line.split(":", 1)[0]
    if metric in seen:
        continue
    if any(s in line for s in
    ("Total frames", "Janky frames:", "percentile", "Number Missed Vsync", "Number Slow UI thread", "Number Slow bitmap uploads")):
        seen.add(metric)
        summary.append(line)
print(json.dumps({"label": args.label, "axis": args.axis, "summary": summary}, indent=2))
