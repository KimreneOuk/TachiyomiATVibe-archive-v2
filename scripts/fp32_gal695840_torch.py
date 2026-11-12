#!/usr/bin/env python3
"""FP32 arm (gal695840 3-arm test): run the author's PyTorch Generator
(generator_clean.pth) on the same 832x576 canvas tensors as the NPU arms.
Output: [24, 832, 576, 3] float32 in [0,1] (colorize() mapping: tanh * 0.5 + 0.5).
Adapted from scripts/fp32_ch166_torch.py; all-new output dir."""

import json
import sys
import time
from pathlib import Path

import numpy as np
import torch

REPO_MAIN = Path(r"C:\Users\ADMIN\Documents\Coding Related\tachiyomiATVIBE")
MCV2_REPO = REPO_MAIN / "models_local" / "qweasdd-manga-colorization-v2"
sys.path.insert(0, str(MCV2_REPO))
from networks.models import Generator  # noqa: E402

WORKTREE = Path(r"C:\Users\ADMIN\.traycer\worktrees\kimreneouk__tachiyomiatvibe\traycer-happy-badger-043b589375fe")
IN_NPZ = WORKTREE / "scratch" / "gal695840_3arm" / "gal695840_inputs.npz"
OUT_NPZ = WORKTREE / "scratch" / "gal695840_3arm" / "gal695840_fp32_torch_outs.npz"
GEN_PATH = REPO_MAIN / "generator" / "generator_clean.pth"


def main():
    print("=== FP32 PyTorch arm (gal695840) ===", flush=True)
    t0 = time.time()
    torch.set_num_threads(8)
    print(f"torch threads: {torch.get_num_threads()}", flush=True)

    data = np.load(IN_NPZ)["inputs"]  # (24, 1, 5, 832, 576)
    print(f"inputs: {data.shape} {data.dtype}", flush=True)

    gen = Generator()
    state = torch.load(GEN_PATH, map_location="cpu")
    gen.load_state_dict(state)
    gen.eval()
    print(f"generator loaded from {GEN_PATH} ({GEN_PATH.stat().st_size:,} B)", flush=True)

    outs = []
    for i, arr in enumerate(data):
        t_p = time.time()
        x = torch.from_numpy(np.ascontiguousarray(arr))
        with torch.no_grad():
            y = gen(x)
        fake = y[0] if isinstance(y, (tuple, list)) else y
        out = fake[0].detach().cpu().permute(1, 2, 0).numpy().astype(np.float32) * 0.5 + 0.5
        assert np.all(np.isfinite(out))
        outs.append(out)
        print(f"  page {i+1:02d}/{len(data)}: mean={out.mean():.4f} min={out.min():.4f} max={out.max():.4f} in {time.time()-t_p:.1f}s", flush=True)

    np.savez_compressed(OUT_NPZ, outputs=np.stack(outs, axis=0))
    print(f"FP32 arm done in {time.time()-t0:.1f}s -> {OUT_NPZ} ({OUT_NPZ.stat().st_size:,} B)", flush=True)


if __name__ == "__main__":
    main()
