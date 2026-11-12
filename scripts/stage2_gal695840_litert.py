#!/usr/bin/env python3
"""Stage 2 (gal695840 3-arm test): run the three tflite arms on the same 24 canvas
tensors in WSL: NEW bank-1111 W8A8, CURRENT recipe W8A8 (staged read-only copy),
and the fp32 base float tflite.
Adapted from scripts/stage2_ch166_litert.py; all-new output dir."""

import json
import time
from pathlib import Path

import numpy as np
from ai_edge_litert.interpreter import Interpreter

WORKTREE = Path("/mnt/c/Users/ADMIN/.traycer/worktrees/kimreneouk__tachiyomiatvibe/traycer-happy-badger-043b589375fe")
GAL = WORKTREE / "scratch" / "gal695840_3arm"
IN_NPZ = GAL / "gal695840_inputs.npz"

MODELS = {
    "new_bank1111": Path("/scratch/mcv2_576x832/bank1111_recipe_w8a8/mcv2_576x832_w8a8_bank1111.tflite"),
    "current_recipe": Path("/scratch/mcv2_576x832/bank1111_eval/current_recipe_w8a8.tflite"),
    "fp32_tflite": Path("/scratch/mcv2_576x832/mcv2_576x832_b6_int32.tflite"),
}


def run_arm(model_path: Path, data: np.ndarray, label: str) -> np.ndarray:
    print(f"--- arm: {label} ({model_path.name}) ---", flush=True)
    interp = Interpreter(model_path=str(model_path), num_threads=4)
    interp.allocate_tensors()
    in_idx = interp.get_input_details()[0]["index"]
    out_idx = interp.get_output_details()[0]["index"]
    outs = []
    for i, arr in enumerate(data):
        t_p = time.time()
        interp.set_tensor(in_idx, np.ascontiguousarray(arr))
        interp.invoke()
        out = interp.get_tensor(out_idx)[0].copy().astype(np.float32)
        assert np.all(np.isfinite(out)), f"{label} non-finite page {i}"
        outs.append(out)
        if (i + 1) % 6 == 0 or (i + 1) == len(data):
            print(f"  {label} page {i+1:02d}/{len(data)} in {time.time()-t_p:.2f}s", flush=True)
    return np.stack(outs, axis=0)  # (24, 832, 576, 3)


def main():
    print("=== Stage 2 (gal695840): LiteRT Inference, 3 arms ===", flush=True)
    t0 = time.time()
    data = np.load(IN_NPZ)["inputs"]
    print(f"inputs: {data.shape}", flush=True)

    results = {}
    for label, path in MODELS.items():
        if not path.exists():
            raise FileNotFoundError(f"missing model: {path}")
        results[label] = run_arm(path, data, label)

    for label, outs in results.items():
        out_path = GAL / f"gal695840_{label}_outs.npz"
        np.savez_compressed(out_path, outputs=outs)
        print(f"saved {out_path} ({out_path.stat().st_size:,} B)", flush=True)

    json.dump({"elapsed_seconds": round(time.time() - t0, 1)},
              open(GAL / "stage2_meta.json", "w"))
    print(f"Stage 2 done in {time.time()-t0:.1f}s", flush=True)


if __name__ == "__main__":
    main()
