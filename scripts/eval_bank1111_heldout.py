#!/usr/bin/env python3
"""Held-out evaluation for the bank-1111 W8A8 model (numeric only, no image viewing).

Runs 3 models on the 40 deterministic held-out bank pages:
  1. fp32 base float tflite (mcv2_576x832_b6_int32.tflite)
  2. NEW bank-1111 W8A8 quantized tflite
  3. CURRENT recipe W8A8 quantized tflite (staged read-only copy)
Metrics per page: PSNR(each W8A8 arm vs fp32 raw output), saturation x100,
warmth (mean R-G) on raw [0,1] outputs. Mirrors the Nov 8 parity methodology
and the evaluation_report.json metric family.
"""

import gc
import hashlib
import json
import math
import time
from pathlib import Path

import numpy as np
from ai_edge_litert.interpreter import Interpreter

MODEL_FP32 = Path("/scratch/mcv2_576x832/mcv2_576x832_b6_int32.tflite")
MODEL_NEW = Path("/scratch/mcv2_576x832/bank1111_recipe_w8a8/mcv2_576x832_w8a8_bank1111.tflite")
MODEL_CUR = Path("/scratch/mcv2_576x832/bank1111_eval/current_recipe_w8a8.tflite")
PROGRESS_IN = Path("/scratch/mcv2_576x832/bank1111_run/progress.json")
BANK_DIR = Path("/scratch/mcv2_576x832/bank_1111")
OUT_NPZ = Path("/scratch/mcv2_576x832/bank1111_run/heldout_outs.npz")
REPORT_OUT = Path("/mnt/c/Users/ADMIN/.traycer/worktrees/kimreneouk__tachiyomiatvibe/traycer-happy-badger-043b589375fe/saved_models/recipe_calibrated_w8a8_bank1111/evaluation_report.json")

BASELINE = {"recipe_1111_heldout_mean_psnr_db": 38.46, "recipe_1111_fp32_sat": 9.97, "recipe_1111_new_sat": 9.77}


def sha256_arr(arr: np.ndarray) -> str:
    return hashlib.sha256(np.ascontiguousarray(arr, dtype=np.float32).tobytes()).hexdigest()


def psnr(ref: np.ndarray, pred: np.ndarray) -> float:
    mse = float(np.mean((ref.astype(np.float64) - pred.astype(np.float64)) ** 2))
    if mse == 0.0:
        return float("inf")
    return float(10.0 * math.log10(1.0 / mse))


def sat100(rgb01: np.ndarray) -> float:
    return float(np.mean(rgb01.max(axis=-1) - rgb01.min(axis=-1)) * 100.0)


def warmth(rgb01: np.ndarray) -> float:
    return float(np.mean(rgb01[..., 0] - rgb01[..., 1]))


def run_model(path: Path, tensors: list[np.ndarray], label: str) -> list[np.ndarray]:
    print(f"[eval] running {label} on {len(tensors)} samples", flush=True)
    inter = Interpreter(model_path=str(path), num_threads=4)
    inter.allocate_tensors()
    in_idx = inter.get_input_details()[0]["index"]
    out_idx = inter.get_output_details()[0]["index"]
    outs, lats = [], []
    for i, x in enumerate(tensors):
        t0 = time.perf_counter()
        inter.set_tensor(in_idx, np.ascontiguousarray(x))
        inter.invoke()
        out = inter.get_tensor(out_idx).astype(np.float32)
        lat = time.perf_counter() - t0
        assert np.all(np.isfinite(out)), f"{label} non-finite on sample {i}"
        outs.append(out)
        lats.append(lat)
        if (i + 1) % 10 == 0 or (i + 1) == len(tensors):
            print(f"  {label} {i+1}/{len(tensors)}: mean={out.mean():.4f} ({lat:.2f}s)", flush=True)
    del inter
    gc.collect()
    print(f"[eval] {label}: mean latency {np.mean(lats):.2f}s", flush=True)
    return outs


def sha256_of_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def main():
    t0 = time.time()
    progress = json.loads(PROGRESS_IN.read_text(encoding="utf-8"))
    heldout = progress["heldout_meta"]
    print(f"held-out pages: {len(heldout)}", flush=True)

    chapter_cache = {}
    tensors, meta = [], []
    for h in heldout:
        npz_name = h["npz"]
        if npz_name not in chapter_cache:
            chapter_cache = {npz_name: np.load(BANK_DIR / npz_name)["inputs"]}
        tensors.append(chapter_cache[npz_name][h["local_index"]])
        meta.append({"chapter": h["chapter"], "file_index": h["local_index"], "global_index": h["global_index"]})
    print(f"loaded {len(tensors)} held-out tensors {tensors[0].shape}", flush=True)

    fp32_outs = run_model(MODEL_FP32, tensors, "fp32_base")
    new_outs = run_model(MODEL_NEW, tensors, "new_bank1111_w8a8")
    cur_outs = run_model(MODEL_CUR, tensors, "current_recipe_w8a8")

    pages = []
    for i, m in enumerate(meta):
        ref = fp32_outs[i]
        new_p = psnr(ref, new_outs[i])
        cur_p = psnr(ref, cur_outs[i])
        pages.append({
            **m,
            "psnr_new_vs_fp32_db": round(new_p, 2),
            "psnr_cur_vs_fp32_db": round(cur_p, 2),
            "sat_fp32": round(sat100(ref), 2),
            "sat_new": round(sat100(new_outs[i]), 2),
            "sat_cur": round(sat100(cur_outs[i]), 2),
            "warmth_fp32": round(warmth(ref), 4),
            "warmth_new": round(warmth(new_outs[i]), 4),
            "warmth_cur": round(warmth(cur_outs[i]), 4),
        })
        print(f"  page {i+1:02d} ({m['chapter'][:40]}#{m['file_index']}): "
              f"new {new_p:.2f} dB | cur {cur_p:.2f} dB | sat {sat100(ref):.2f}/{sat100(new_outs[i]):.2f}/{sat100(cur_outs[i]):.2f}", flush=True)

    new_ps = [p["psnr_new_vs_fp32_db"] for p in pages]
    cur_ps = [p["psnr_cur_vs_fp32_db"] for p in pages]
    report = {
        "evaluated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "held_out_samples_count": len(pages),
        "heldout_selection": "np.linspace over global bank order (deterministic), excluded from calibration",
        "models": {
            "fp32_base": str(MODEL_FP32),
            "new_bank1111_w8a8": {"path": str(MODEL_NEW), "sha256": sha256_of_file(MODEL_NEW)},
            "current_recipe_w8a8": str(MODEL_CUR),
        },
        "metrics": {
            "new_bank1111_w8a8": {
                "mean_psnr_db": round(float(np.mean(new_ps)), 2),
                "median_psnr_db": round(float(np.median(new_ps)), 2),
                "min_psnr_db": round(float(np.min(new_ps)), 2),
                "saturation_mean": round(float(np.mean([p["sat_new"] for p in pages])), 2),
                "saturation_delta_vs_fp32": round(float(np.mean([p["sat_new"] for p in pages]) - np.mean([p["sat_fp32"] for p in pages])), 2),
                "warmth_mean": round(float(np.mean([p["warmth_new"] for p in pages])), 4),
            },
            "current_recipe_w8a8_same_set": {
                "mean_psnr_db": round(float(np.mean(cur_ps)), 2),
                "median_psnr_db": round(float(np.median(cur_ps)), 2),
                "min_psnr_db": round(float(np.min(cur_ps)), 2),
                "saturation_mean": round(float(np.mean([p["sat_cur"] for p in pages])), 2),
                "saturation_delta_vs_fp32": round(float(np.mean([p["sat_cur"] for p in pages]) - np.mean([p["sat_fp32"] for p in pages])), 2),
                "warmth_mean": round(float(np.mean([p["warmth_cur"] for p in pages])), 4),
            },
            "fp32_baseline": {
                "saturation_mean": round(float(np.mean([p["sat_fp32"] for p in pages])), 2),
                "warmth_mean": round(float(np.mean([p["warmth_fp32"] for p in pages])), 4),
            },
            "psnr_improvement_new_vs_current_same_set_db": round(float(np.mean(new_ps) - np.mean(cur_ps)), 2),
        },
        "baseline_from_recipe_1111_run": BASELINE,
        "pages": pages,
    }
    REPORT_OUT.write_text(json.dumps(report, indent=2), encoding="utf-8")
    np.savez_compressed(OUT_NPZ,
                        fp32=np.stack(fp32_outs), new=np.stack(new_outs), cur=np.stack(cur_outs),
                        meta=np.array(json.dumps(meta)))
    print(f"report -> {REPORT_OUT}", flush=True)
    print(f"outputs -> {OUT_NPZ}", flush=True)
    print(f"=== EVAL DONE in {time.time()-t0:.1f}s ===", flush=True)
    print(json.dumps(report["metrics"], indent=2), flush=True)


if __name__ == "__main__":
    main()
