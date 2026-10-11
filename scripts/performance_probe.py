#!/usr/bin/env python3
"""Collect isolated Android diagnostics without touching physical devices."""

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
    parser.add_argument("--output", default="build/performance")
    parser.add_argument("--apk", help="APK for startup diagnostics; use with --skip-instrumentation.")
    parser.add_argument("--skip-instrumentation", action="store_true")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("Only isolated emulator serials are accepted; physical devices are untouched.")
    root = Path(__file__).resolve().parents[1]
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)

    def adb(*command, timeout=120):
        result = subprocess.run(
            [args.adb, "-s", args.serial, *command],
            capture_output=True, text=True, timeout=timeout,
        )
        if result.returncode:
            raise RuntimeError(f"adb {command}: {result.stdout}\n{result.stderr}")
        return result.stdout

    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        parser.error("Selected target is not an emulator.")
    metadata = {
        "serial": args.serial,
        "api": adb("shell", "getprop", "ro.build.version.sdk").strip(),
        "abi": adb("shell", "getprop", "ro.product.cpu.abi").strip(),
        "screen": adb("shell", "wm", "size").strip(),
        "warning": "Emulator/debug diagnostics, not physical-device release benchmarks.",
    }
    package = "com.pinpin.app"
    debug_apk = Path(args.apk).resolve() if args.apk else root / "app/build/outputs/apk/debug/app-debug.apk"
    if args.apk and not args.skip_instrumentation:
        parser.error("--apk requires --skip-instrumentation.")
    test_apk = root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
    digest = hashlib.sha256()
    with debug_apk.open("rb") as apk_file:
        for chunk in iter(lambda: apk_file.read(1024 * 1024), b""):
            digest.update(chunk)
    metadata["apk_sha256"] = digest.hexdigest()
    metadata["apk_bytes"] = debug_apk.stat().st_size
    metadata["apk_path"] = str(debug_apk)
    adb("install", "-r", str(debug_apk))
    if not args.skip_instrumentation:
        adb("install", "-r", str(test_apk))
        adb("logcat", "-c")
        result = adb(
            "shell", "am", "instrument", "-w",
            f"{package}.test/androidx.test.runner.AndroidJUnitRunner", timeout=1800,
        )
        (output / "instrumentation.txt").write_text(result)
        logs = adb("logcat", "-d", "-v", "brief", "PinPinPerf:I", "*:S")
        (output / "performance-logcat.txt").write_text(logs)
        home_frames = adb(
            "shell", "run-as", package, "cat", "cache/pinpin-performance-home-frames.txt",
        ) if "home_history_scroll" in logs else ""
        if home_frames:
            (output / "frames-history-scroll.txt").write_text(home_frames)
        probes = []
        for line in logs.splitlines():
            start = line.find("{")
            if start >= 0:
                probes.append(json.loads(line[start:]))
        metadata["probes"] = probes
        metadata["instrumentation_passed"] = "OK (" in result and "FAILURES!!!" not in result
        if not metadata["instrumentation_passed"]:
            (output / "results.json").write_text(json.dumps(metadata, indent=2))
            raise RuntimeError("Instrumentation did not pass; inspect instrumentation.txt.")

    # Overlay tests grant this app-op; restore it before the startup baseline.
    adb("shell", "appops", "set", package, "SYSTEM_ALERT_WINDOW", "default")
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "input", "keyevent", "KEYCODE_MENU")
    samples = []
    for _ in range(12):
        text = adb(
            "shell", "am", "start", "-W", "-S", "-n", f"{package}/.ui.MainActivity",
        )
        fields = {key: int(value) for key, value in re.findall(r"(ThisTime|TotalTime|WaitTime):\s*(\d+)", text)}
        if "TotalTime" not in fields:
            raise RuntimeError(f"No startup time recorded: {text}")
        samples.append(fields)
        time.sleep(1)
    times = sorted(sample["TotalTime"] for sample in samples[2:])
    metadata["startup"] = {
        "mode": "force-stop process-cold, cache-warm, empty main screen, overlay permission off",
        "discarded_warmups": 2,
        "samples": samples[2:],
        "median_ms": statistics.median(times),
        "p95_ms": times[math.ceil(len(times) * 0.95) - 1],
    }
    (output / "memory-main.txt").write_text(adb("shell", "dumpsys", "meminfo", package))
    (output / "frames-main.txt").write_text(adb("shell", "dumpsys", "gfxinfo", package, "framestats"))
    pid = adb("shell", "pidof", package).strip()
    start_stat = adb("shell", "cat", f"/proc/{pid}/stat")
    (output / "cpu-idle-start.txt").write_text(start_stat)
    idle_start = time.monotonic()
    time.sleep(10)
    end_stat = adb("shell", "cat", f"/proc/{pid}/stat")
    idle_elapsed = time.monotonic() - idle_start
    (output / "cpu-idle-end.txt").write_text(end_stat)
    hz = int(adb("shell", "getconf", "CLK_TCK").strip())

    def cpu_ticks(text):
        fields = text.rsplit(")", 1)[1].split()
        return int(fields[11]) + int(fields[12])

    metadata["idle_cpu"] = {
        "sample_seconds": idle_elapsed,
        "clock_ticks_per_second": hz,
        "percent_of_one_core": (cpu_ticks(end_stat) - cpu_ticks(start_stat)) / hz / idle_elapsed * 100,
        "warning": "Empty foreground screen only; excludes active overlays, capture and background battery use.",
    }
    (output / "crash-log.txt").write_text(adb("logcat", "-b", "crash", "-d"))
    (output / "results.json").write_text(json.dumps(metadata, indent=2))
    summary = {key: value for key, value in metadata.items() if key != "probes"}
    summary["probe_count"] = len(metadata.get("probes", []))
    summary["output"] = str(output)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
