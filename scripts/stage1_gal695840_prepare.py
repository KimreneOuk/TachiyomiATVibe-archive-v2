#!/usr/bin/env python3
"""Stage 1 (gal695840 3-arm test): preprocess nhentai gallery 695840 pages 045-068
with the author recipe (FFDNet sigma=25 -> resize_pad 576 -> 832x576 white canvas).
Adapted from scripts/stage1_ch166_prepare.py; all-new output dir.
The gallery folder name breaks direct Win32 final-component access, so files are
enumerated from the parent (same pattern the bank builder used successfully)."""

import json
import os
import re
import shutil
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

REPO_MAIN = Path(r"C:\Users\ADMIN\Documents\Coding Related\tachiyomiATVIBE")
MCV2_REPO = REPO_MAIN / "models_local" / "qweasdd-manga-colorization-v2"
sys.path.insert(0, str(MCV2_REPO))
from denoising.denoiser import FFDNetDenoiser  # noqa: E402
from utils.utils import resize_pad  # noqa: E402

NH_ROOT = REPO_MAIN / "npu_route" / "calibration" / "rawkuma" / "nhentai"
PAGE_LO, PAGE_HI = 45, 68
WORKTREE = Path(r"C:\Users\ADMIN\.traycer\worktrees\kimreneouk__tachiyomiatvibe\traycer-happy-badger-043b589375fe")
OUT_DIR = WORKTREE / "scratch" / "gal695840_3arm"
OUT_NPZ = OUT_DIR / "gal695840_inputs.npz"
OUT_PADS = OUT_DIR / "gal695840_pads.json"
SRC_COPY_DIR = OUT_DIR / "src"
EXTS = {".jpg", ".jpeg", ".png", ".webp"}


def natural_key(name: str):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", name)]


def list_gallery_files():
    """Enumerate gallery files via parent iterdir, falling back to \\?\ paths."""
    cands = [p for p in NH_ROOT.iterdir() if p.is_dir() and p.name.startswith("695840")]
    print(f"candidate dirs: {[c.name for c in cands]}", flush=True)
    for cand in cands:
        try:
            files = [p for p in cand.iterdir() if p.is_file() and p.suffix.lower() in EXTS]
            if files:
                return cand, files, False
        except OSError as e:
            print(f"  iterdir failed on {cand.name!r}: {e}", flush=True)
    # fallback: extended-length path (\\?\) via os.scandir; return str paths
    for cand in cands:
        ext = "\\\\?\\" + str(cand)
        try:
            entries = [e for e in os.scandir(ext) if e.is_file()
                       and Path(e.name).suffix.lower() in EXTS]
            if entries:
                return cand, [Path(e.path) for e in entries], True
        except OSError as e:
            print(f"  scandir failed on {cand.name!r}: {e}", flush=True)
    raise RuntimeError("no gallery files found under any 695840 candidate dir")


def open_rgb(path) -> np.ndarray:
    with Image.open(path) as im:
        return np.array(im.convert("RGB"))


def main():
    print("=== Stage 1 (gal695840): Preparing Input Tensors, pages 45-68 ===", flush=True)
    t0 = time.time()
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    SRC_COPY_DIR.mkdir(parents=True, exist_ok=True)

    gal_dir, files, used_ext_path = list_gallery_files()
    files = sorted(files, key=lambda p: natural_key(p.name))
    print(f"gallery: {gal_dir.name!r} ({len(files)} files, ext_path={used_ext_path})", flush=True)

    selected = []
    for p in files:
        m = re.match(r"^(\d+)", p.name)
        if not m:
            continue
        num = int(m.group(1))
        if PAGE_LO <= num <= PAGE_HI:
            selected.append((num, p))
    print(f"selected pages {PAGE_LO}-{PAGE_HI}: {len(selected)} files", flush=True)
    if len(selected) != PAGE_HI - PAGE_LO + 1:
        raise RuntimeError(f"expected {PAGE_HI-PAGE_LO+1} pages, got {len(selected)}")

    denoiser = FFDNetDenoiser("cpu", _sigma=25, _weights_dir=str(MCV2_REPO / "denoising" / "models"))
    print("FFDNet denoiser loaded.", flush=True)

    tensors, pads = [], []
    for t_idx, (num, src_path) in enumerate(selected):
        t_p = time.time()
        rgb = open_rgb(src_path)
        orig_h, orig_w = rgb.shape[:2]

        # stable local copy for stage 3 (folder name is not directly addressable)
        copy_name = f"p{t_idx + 1:02d}_orig{src_path.suffix.lower()}"
        shutil.copyfile(src_path, SRC_COPY_DIR / copy_name)

        denoised = denoiser.get_denoised_image(rgb, sigma=25)
        work, pad = resize_pad(denoised, size=576)

        canvas = np.ones((832, 576), dtype=np.uint8) * 255
        ch = min(832, work.shape[0])
        cw = min(576, work.shape[1])
        canvas[:ch, :cw] = work[:ch, :cw, 0]

        tensor = np.zeros((1, 5, 832, 576), dtype=np.float32)
        tensor[0, 0] = canvas.astype(np.float32) / 255.0
        tensors.append(tensor)
        pads.append({
            "page": t_idx + 1,
            "file_no": num,
            "local_index": num - 1,
            "file": src_path.name,
            "src_copy": copy_name,
            "orig_w": orig_w,
            "orig_h": orig_h,
            "pad": list(pad) if pad else [0, 0],
            "crop_h": 832 - pad[0] if pad and pad[0] > 0 else 832,
            "crop_w": 576 - pad[1] if pad and pad[1] > 0 else 576,
        })
        print(f"  page {t_idx+1:02d} (file {num:03d}) {orig_w}x{orig_h} in {time.time()-t_p:.2f}s", flush=True)

    np.savez_compressed(OUT_NPZ, inputs=np.stack(tensors, axis=0))
    with open(OUT_PADS, "w", encoding="utf-8") as f:
        json.dump(pads, f, indent=2)
    print(f"Stage 1 done in {time.time()-t0:.1f}s. Saved {OUT_NPZ} ({OUT_NPZ.stat().st_size:,} B)", flush=True)


if __name__ == "__main__":
    main()
