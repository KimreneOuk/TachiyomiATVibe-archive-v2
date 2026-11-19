#!/usr/bin/env python3
"""Provisions the five pinned MangaLight .tflite models into the SAM viz probe app.

Primary source is the installed main app's extracted model cache (same pinned
files the colorization engine uses), pulled via `run-as` over adb. Alternatively
pass --dir with a laptop-side model directory. Files are written into the probe
app's filesDir/models via `adb shell run-as <probe> sh -c 'cat > ...'` (binary-
safe stdin, no shared temp dirs needed). The probe app re-verifies size+sha256
on open, so a successful "ready" health check is the final authority.
"""

import argparse
import hashlib
import io
import os
import subprocess
import sys
import tempfile

MAIN_PACKAGE_CANDIDATES = [
    "app.kanade.tachiyomi.vibe.debug",
    "app.kanade.tachiyomi.vibe",
]
PROBE_PACKAGE = "eu.kanade.translation.samvizprobe"
MAIN_REMOTE_DIR_MANGALIGHT = "no_backup/tachiyomi-models/manga-light-hybrid"
MAIN_REMOTE_DIR_MCV2 = "no_backup/tachiyomi-models/coloring"

# (remote dir, name, expected bytes, sha256) — identical pins to the engine classes.
ARTIFACTS = [
    (MAIN_REMOTE_DIR_MANGALIGHT, "sam0.tflite", 36_312_536, "06a0436722c853e54c23a3b810a39f7bb47b77a33e74cbbd1763aa7cffe8253f"),
    (MAIN_REMOTE_DIR_MANGALIGHT, "sam1.tflite", 32_891_512, "7da91f7f4d00abd4110e6da9583897d833d0c65f60e4e341baca74896c5bc7c7"),
    (MAIN_REMOTE_DIR_MANGALIGHT, "sam2.tflite", 23_498_360, "cc0b844f39f6e5a1ca8faa6742102947ba904889cb725c19b49a9cb7ca2aeed0"),
    (MAIN_REMOTE_DIR_MANGALIGHT, "sam3.tflite", 52_386_032, "2f9deffb67b09f4d0d328c287c01e31ddfe83b30c06daf03bb26a1b5147ec382"),
    (MAIN_REMOTE_DIR_MANGALIGHT, "generator.tflite", 179_058_976, "49f13c8d8dd8f543ebed6a3b76ee60fcf3195680ec9acd93d4fe83e3cfcb72da"),
    (MAIN_REMOTE_DIR_MCV2, "manga_colorize_npu_sm8650_w8a8.tflite", 55_628_120, "bd44365fa699122d778e52bcd3c0eb6d61ec4ae0b3fc01079afaf8fc17fb2cc3"),
]


def adb_path():
    default = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
    return os.environ.get("ADB", default)


def run(cmd, binary_output=False, stdin_file=None):
    result = subprocess.run(cmd, capture_output=True, stdin=stdin_file)
    if result.returncode != 0:
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(cmd)}\nstderr: {result.stderr.decode(errors='replace')}")
    return result.stdout if binary_output else result.stdout.decode(errors="replace")


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def resolve_main_package(adb, serial):
    """Debug applicationIds carry a .debug suffix; pick whichever candidate can
    serve both pinned model caches through run-as."""
    required = {MAIN_REMOTE_DIR_MANGALIGHT: "generator.tflite", MAIN_REMOTE_DIR_MCV2: "manga_colorize_npu_sm8650_w8a8.tflite"}
    for package in MAIN_PACKAGE_CANDIDATES:
        args = [adb]
        if serial:
            args += ["-s", serial]
        try:
            ok = True
            for remote_dir, marker in required.items():
                listing = run(args + ["shell", "-T", f"run-as {package} ls {remote_dir}"])
                if marker not in listing:
                    print(f"  {package}: {remote_dir} lacks {marker}")
                    ok = False
            if ok:
                return package
        except RuntimeError:
            print(f"  {package}: run-as unavailable (not debuggable or not installed)")
    raise RuntimeError(
        "no candidate package exposes both pinned model caches — colorize one page "
        "with each engine in the main app first"
    )


def fetch_from_device(remote_dir, name, main_package, adb, serial):
    remote = f"{remote_dir}/{name}"
    args = [adb]
    if serial:
        args += ["-s", serial]
    args += ["exec-out", "run-as", main_package, "cat", remote]
    print(f"  pulling {remote} from {main_package} …", flush=True)
    return run(args, binary_output=True)


def push_into_probe(name, data, adb, serial):
    # adb shell stdin truncates large streams on Windows; stage via adb push
    # into /data/local/tmp and copy as the probe user (its dir is +x-traversable).
    base = [adb]
    if serial:
        base += ["-s", serial]
    staged = f"/data/local/tmp/samviz_{name}"
    handle = tempfile.NamedTemporaryFile(suffix=".tflite", delete=False)
    try:
        handle.write(data)
        handle.close()
        print(f"  staging {name} via adb push …", flush=True)
        run(base + ["push", handle.name, staged])
        run(base + ["shell", "-T", f"chmod 644 {staged}"])
        print(f"  copying into {PROBE_PACKAGE} files/models/{name} …", flush=True)
        run(base + ["shell", "-T", f"run-as {PROBE_PACKAGE} cp {staged} files/models/{name}"])
        run(base + ["shell", "-T", f"rm -f {staged}"])
        size_out = run(base + ["shell", "-T", f"run-as {PROBE_PACKAGE} wc -c files/models/{name}"]).strip()
        print(f"  device reports: {size_out}")
    finally:
        os.unlink(handle.name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default=None, help="adb device serial (defaults to the only connected device)")
    parser.add_argument("--dir", default=None, help="local directory holding the five .tflite files instead of pulling from the installed app")
    parser.add_argument("--adb", default=None, help="path to adb (defaults to SDK platform-tools)")
    options = parser.parse_args()

    adb = options.adb or adb_path()
    if not os.path.isfile(adb):
        sys.exit(f"adb not found at {adb}; pass --adb or set ADB")
    serial = options.serial

    listing = run([adb] + (["-s", serial] if serial else []) + ["devices"])
    if listing.count("\tdevice") == 0:
        sys.exit(f"no adb device connected:\n{listing}")

    if options.dir:
        print(f"Source: local directory {options.dir}")
        main_package = None
    else:
        main_package = resolve_main_package(adb, serial)
        print(f"Source: installed app {main_package}")

    for remote_dir, name, expected_bytes, expected_sha in ARTIFACTS:
        print(f"{name}:")
        if options.dir:
            path = os.path.join(options.dir, name)
            if not os.path.isfile(path):
                sys.exit(f"  missing {path}")
            with open(path, "rb") as handle:
                data = handle.read()
        else:
            data = fetch_from_device(remote_dir, name, main_package, adb, serial)
        problems = []
        if len(data) != expected_bytes:
            problems.append(f"size {len(data)} != {expected_bytes}")
        digest = hashlib.sha256(data).hexdigest()
        if digest != expected_sha:
            problems.append(f"sha256 {digest} != {expected_sha}")
        if problems:
            sys.exit("  MISMATCH: " + "; ".join(problems))
        print(f"  ok ({len(data)} bytes, sha256 verified)")
        push_into_probe(name, data, adb, serial)

    print("\nAll six artifacts provisioned and pinned-verified. Start the server in the")
    print("probe app (SAM Viz Probe > Start server), then check GET /health on the laptop.")


if __name__ == "__main__":
    main()
