#!/usr/bin/env python3
"""Stage 1 (ch166 3-arm test): preprocess isekai-walking chapter-166 with the
author recipe (FFDNet sigma=25 -> resize_pad 576 -> 832x576 white canvas).
Adapted from scripts/stage1_prepare_ch34_tensors.py; all-new output dir."""

import json
import re
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

CHAPTER_DIR = REPO_MAIN / "npu_route" / "calibration" / "rawkuma" / "isekai-walking" / "chapter-166.433236"
OUT_DIR = Path(r"C:\Users\ADMIN\.traycer\worktrees\kimreneouk__tachiyomiatvibe\traycer-happy-badger-043b589375fe\scratch\ch166_3arm")
OUT_NPZ = OUT_DIR / "ch166_inputs.npz"
OUT_PADS = OUT_DIR / "ch166_pads.json"
EXTS = {".jpg", ".jpeg", ".png", ".webp"}


def natural_key(name: str):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", name)]


def main():
    print("=== Stage 1 (ch166): Preparing Input Tensors ===", flush=True)
    t0 = time.time()
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    files = sorted((f for f in CHAPTER_DIR.iterdir() if f.suffix.lower() in EXTS and f.is_file()),
                   key=lambda p: natural_key(p.name))
    print(f"chapter dir: {CHAPTER_DIR}", flush=True)
    print(f"pages: {len(files)}", flush=True)

    denoiser = FFDNetDenoiser("cpu", _sigma=25, _weights_dir=str(MCV2_REPO / "denoising" / "models"))
    print("FFDNet denoiser loaded.", flush=True)

    tensors, pads = [], []
    for t_idx, src_path in enumerate(files):
        t_p = time.time()
        with Image.open(src_path) as im:
            rgb = np.array(im.convert("RGB"))
        orig_h, orig_w = rgb.shape[:2]

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
            "torch_index": t_idx,
            "file": src_path.name,
            "orig_w": orig_w,
            "orig_h": orig_h,
            "pad": list(pad) if pad else [0, 0],
            "crop_h": 832 - pad[0] if pad and pad[0] > 0 else 832,
            "crop_w": 576 - pad[1] if pad and pad[1] > 0 else 576,
        })
        print(f"  page {t_idx+1:02d}/{len(files)} {src_path.name} {orig_w}x{orig_h} in {time.time()-t_p:.2f}s", flush=True)

    np.savez_compressed(OUT_NPZ, inputs=np.stack(tensors, axis=0))
    with open(OUT_PADS, "w", encoding="utf-8") as f:
        json.dump(pads, f, indent=2)
    print(f"Stage 1 done in {time.time()-t0:.1f}s. Saved {OUT_NPZ} ({OUT_NPZ.stat().st_size:,} B)", flush=True)


if __name__ == "__main__":
    main()
