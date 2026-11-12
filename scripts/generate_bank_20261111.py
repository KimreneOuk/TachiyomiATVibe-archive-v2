#!/usr/bin/env python3
"""Generate the 2026-11-11 recipe calibration bank from today's downloaded
chapters/galleries, using the author's original PyTorch recipe exactly:
- FFDNet denoise sigma=25 at native resolution (long side <=1200 inside denoiser)
- author resize_pad(size=576) -> gray canvas, top-left aligned, white padded to 832
- NCHW [1,5,832,576] float32, ch0 = gray/255, ch1..4 = 0.0

Crash-proofing: one .npz per chapter, bank_manifest.json + CHECKPOINT.json
rewritten (atomically) after EVERY chapter; restart skips completed chapters.

Numeric only: no image is ever rendered or visually inspected.
"""

import hashlib
import json
import os
import re
import sys
import time
from datetime import datetime
from pathlib import Path

import numpy as np
from PIL import Image

REPO_MAIN = Path(r"C:\Users\ADMIN\Documents\Coding Related\tachiyomiATVIBE")
MCV2_REPO = REPO_MAIN / "models_local" / "qweasdd-manga-colorization-v2"
sys.path.insert(0, str(MCV2_REPO))

from denoising.denoiser import FFDNetDenoiser  # noqa: E402
from utils.utils import resize_pad  # noqa: E402

WORKTREE = Path(r"C:\Users\ADMIN\.traycer\worktrees\kimreneouk__tachiyomiatvibe\traycer-happy-badger-043b589375fe")
CALIB_ROOT = REPO_MAIN / "npu_route" / "calibration" / "rawkuma"
BANK_DIR = WORKTREE / "saved_models" / "calibration_bank_20261111"
BANK_DIR.mkdir(parents=True, exist_ok=True)

MTIME_CUTOFF = datetime(2026, 11, 11, 0, 0, 0).timestamp()
EXTS = {".jpg", ".jpeg", ".png", ".webp"}
WIDTH, HEIGHT = 576, 832

CHECKPOINT_PATH = BANK_DIR / "CHECKPOINT.json"
MANIFEST_PATH = BANK_DIR / "bank_manifest.json"


def sha256_of_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def natural_key(name: str):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", name)]


def atomic_json(path: Path, payload: dict):
    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)
        f.flush()
        os.fsync(f.fileno())
    tmp.replace(path)


def scan_bank():
    """Return {rel_chapter_folder: [today-mtime image files]}."""
    chapters = {}
    for f in sorted(CALIB_ROOT.rglob("*")):
        if not f.is_file() or f.suffix.lower() not in EXTS:
            continue
        if f.stat().st_mtime < MTIME_CUTOFF:
            continue
        rel = str(f.parent.relative_to(CALIB_ROOT))
        chapters.setdefault(rel, []).append(f)
    return chapters


def process_page(denoiser: FFDNetDenoiser, img_path: Path):
    with Image.open(img_path) as im:
        im.verify()
    with Image.open(img_path) as im:
        im_rgb = np.array(im.convert("RGB"))

    orig_h, orig_w = im_rgb.shape[:2]
    denoised_bgr = denoiser.get_denoised_image(im_rgb, sigma=25)
    work_gray, pad = resize_pad(denoised_bgr, size=WIDTH)

    canvas = np.ones((HEIGHT, WIDTH), dtype=np.uint8) * 255
    ch = min(HEIGHT, work_gray.shape[0])
    cw = min(WIDTH, work_gray.shape[1])
    canvas[:ch, :cw] = work_gray[:ch, :cw, 0]

    tensor = np.zeros((1, 5, HEIGHT, WIDTH), dtype=np.float32)
    tensor[0, 0] = canvas.astype(np.float32) / 255.0
    tensor[0, 1:5] = 0.0

    meta = {
        "file": img_path.name,
        "orig_w": orig_w,
        "orig_h": orig_h,
        "denoised_w": denoised_bgr.shape[1],
        "denoised_h": denoised_bgr.shape[0],
        "recipe_w": work_gray.shape[1],
        "recipe_h": work_gray.shape[0],
        "pad": list(pad) if pad else [0, 0],
        "clamped_to_canvas": bool(work_gray.shape[0] > HEIGHT or work_gray.shape[1] > WIDTH),
        "ch0_mean": round(float(tensor[0, 0].mean()), 6),
        "ch0_min": round(float(tensor[0, 0].min()), 6),
        "ch0_max": round(float(tensor[0, 0].max()), 6),
        "sha256": hashlib.sha256(tensor.tobytes()).hexdigest(),
    }
    return tensor, meta


def safe_name(rel_folder: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "_", rel_folder).strip("_")[:120]


def load_checkpoint():
    if CHECKPOINT_PATH.exists():
        try:
            return json.loads(CHECKPOINT_PATH.read_text(encoding="utf-8"))
        except Exception as e:
            print(f"WARNING: unreadable checkpoint ({e}); starting fresh", flush=True)
    return None


def verify_completed(chapter: dict) -> bool:
    npz_path = BANK_DIR / chapter["npz"]
    if not npz_path.is_file():
        return False
    if chapter.get("npz_sha256") and sha256_of_file(npz_path) != chapter["npz_sha256"]:
        print(f"  checkpoint sha mismatch for {npz_path.name}; will redo", flush=True)
        return False
    return True


def main():
    t_start = time.time()
    print("=== Recipe Calibration Bank Generator (2026-11-11) ===", flush=True)
    print(f"Source root: {CALIB_ROOT}", flush=True)
    print(f"Bank dir:    {BANK_DIR}", flush=True)
    print(f"mtime cutoff: {datetime.fromtimestamp(MTIME_CUTOFF)} local", flush=True)

    chapters = scan_bank()
    total_files = sum(len(v) for v in chapters.values())
    order = sorted(chapters.keys())
    print(f"Chapters: {len(order)}, today-mtime pages: {total_files}", flush=True)
    for rel in order:
        print(f"  {rel}: {len(chapters[rel])} pages", flush=True)

    manifest = {
        "created_at": datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source_root": str(CALIB_ROOT),
        "mtime_cutoff": "2026-11-11T00:00:00 local",
        "recipe": "FFDNet sigma=25 native-res -> resize_pad(576) -> 832x576 white canvas (top-left) -> NCHW [1,5,832,576] f32, ch0 gray/255, ch1-4 zero",
        "chapters": {},
    }
    checkpoint = {
        "config_echo": {
            "source_root": str(CALIB_ROOT),
            "mtime_cutoff": "2026-11-11T00:00:00 local",
            "extensions": sorted(EXTS),
            "canvas": [HEIGHT, WIDTH],
            "expected_chapters": len(order),
            "expected_total_samples": total_files,
            "generator_script": "scripts/generate_bank_20261111.py",
        },
        "started_at": datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"),
        "completed_chapters": {},
        "total_samples": 0,
        "last_updated": None,
    }

    prev = load_checkpoint()
    if prev:
        checkpoint["started_at"] = prev.get("started_at", checkpoint["started_at"])
        checkpoint["completed_chapters"] = prev.get("completed_chapters", {})
        checkpoint["total_samples"] = prev.get("total_samples", 0)
        old_manifest = MANIFEST_PATH if MANIFEST_PATH.exists() else None
        if old_manifest:
            try:
                manifest["chapters"] = json.loads(old_manifest.read_text(encoding="utf-8"))["chapters"]
            except Exception:
                manifest["chapters"] = {}

    denoiser = FFDNetDenoiser("cpu", _sigma=25, _weights_dir=str(MCV2_REPO / "denoising" / "models"))
    print("FFDNet denoiser loaded on CPU.", flush=True)

    done_count = 0
    for rel in order:
        chapter_key = rel
        files = sorted(chapters[rel], key=lambda p: natural_key(p.name))

        prev_entry = checkpoint["completed_chapters"].get(chapter_key)
        if prev_entry and verify_completed(prev_entry):
            done_count += len(prev_entry.get("pages", {}))
            print(f"[SKIP completed] {rel} ({prev_entry['page_count']} pages)", flush=True)
            continue

        t_ch = time.time()
        npz_name = f"{safe_name(rel)}.npz"
        tensors, pages_meta, skipped = [], {}, {}
        for j, path in enumerate(files):
            t_p = time.time()
            try:
                tensor, meta = process_page(denoiser, path)
                tensors.append(tensor)
                pages_meta[meta["file"]] = meta
            except Exception as e:
                skipped[path.name] = str(e)
                print(f"  [SKIP page] {rel}/{path.name}: {e}", flush=True)
                continue
            if (j + 1) % 10 == 0 or (j + 1) == len(files):
                print(f"  [{rel} {j+1}/{len(files)}] {path.name} {time.time()-t_p:.2f}s", flush=True)

        if not tensors:
            print(f"  [EMPTY] {rel}: no processable pages; skipping chapter", flush=True)
            continue

        arr = np.stack(tensors, axis=0)  # (N, 1, 5, 832, 576)
        npz_path = BANK_DIR / npz_name
        np.savez_compressed(npz_path, inputs=arr)
        npz_sha = sha256_of_file(npz_path)
        npz_size = npz_path.stat().st_size

        entry = {
            "source_folder": rel,
            "page_count": int(arr.shape[0]),
            "npz": npz_name,
            "npz_size": npz_size,
            "npz_sha256": npz_sha,
            "processed_at": datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"),
            "pages": pages_meta,
            "skipped": skipped,
        }
        manifest["chapters"][chapter_key] = {
            k: entry[k] for k in ("source_folder", "page_count", "npz", "npz_size", "npz_sha256", "processed_at")
        }
        manifest["chapters"][chapter_key]["skipped_count"] = len(skipped)

        checkpoint["completed_chapters"][chapter_key] = {
            "page_count": entry["page_count"],
            "npz": npz_name,
            "npz_sha256": npz_sha,
            "pages": {k: {"sha256": v["sha256"]} for k, v in pages_meta.items()},
            "skipped_count": len(skipped),
        }
        checkpoint["total_samples"] = sum(c["page_count"] for c in checkpoint["completed_chapters"].values())
        checkpoint["last_updated"] = datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")

        atomic_json(MANIFEST_PATH, manifest)
        atomic_json(CHECKPOINT_PATH, checkpoint)

        done_count += entry["page_count"]
        print(
            f"[DONE {len(checkpoint['completed_chapters'])}/{len(order)}] {rel}: {entry['page_count']} pages "
            f"({time.time()-t_ch:.1f}s) -> {npz_name} ({npz_size:,} B, sha {npz_sha[:16]}...) | bank progress {done_count}/{total_files}",
            flush=True,
        )

    total_pages = sum(c["page_count"] for c in manifest["chapters"].values())
    print(f"=== BANK COMPLETE: {len(manifest['chapters'])} chapters, {total_pages} pages, wall {time.time()-t_start:.1f}s ===", flush=True)


if __name__ == "__main__":
    main()
