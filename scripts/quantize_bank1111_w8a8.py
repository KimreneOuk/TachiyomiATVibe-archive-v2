#!/usr/bin/env python3
"""Bank-1111 recalibration: W8A8 quantization + Qualcomm SM8650 AOT compile.

Uses ALL banked samples from the 2026-11-11 recipe bank (per-chapter npz packs),
minus a deterministic held-out set of ~40 pages. Calibration runs in CHUNKS with
previous_calibration_result chaining (mathematically identical min/max stats),
pickling a checkpoint after every chunk so a crash resumes instead of restarting.
The intermediate quantized .tflite is saved and sha-recorded BEFORE AOT compile.
"""

import hashlib
import json
import os
import pickle
import shutil
import time
from pathlib import Path

import numpy as np

from ai_edge_quantizer import quantizer as lq
from ai_edge_quantizer import recipe_manager
from ai_edge_quantizer.calibrator import CalibrationMode
from ai_edge_quantizer.qtyping import TFLOperationName
from ai_edge_litert.interpreter import Interpreter
from ai_edge_litert.aot import aot_compile
from ai_edge_litert.aot.vendors.qualcomm import target

MODEL_IN = Path("/scratch/mcv2_576x832/mcv2_576x832_b6_int32.tflite")
BANK_DIR = Path("/scratch/mcv2_576x832/bank_1111")
RUN_DIR = Path("/scratch/mcv2_576x832/bank1111_run")
OUT_DIR = Path("/scratch/mcv2_576x832/bank1111_recipe_w8a8")

WT_MODELS = Path("/mnt/c/Users/ADMIN/.traycer/worktrees/kimreneouk__tachiyomiatvibe/traycer-happy-badger-043b589375fe/saved_models/recipe_calibrated_w8a8_bank1111")

HELDOUT_COUNT = 40
CHUNK = 96
NUM_THREADS = 4

CHECKPOINT = RUN_DIR / "CALIB_CHECKPOINT.pkl"
PROGRESS = RUN_DIR / "progress.json"


def sha256_of_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def save_progress(state: dict):
    tmp = PROGRESS.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, indent=2))
    tmp.replace(PROGRESS)


def build_segments(chapters: list[dict]) -> list[tuple[int, int, int]]:
    """[(chapter_index, start, end)] each <= CHUNK samples."""
    segs = []
    for ci, ch in enumerate(chapters):
        n = ch["page_count"]
        for s in range(0, n, CHUNK):
            segs.append((ci, s, min(s + CHUNK, n)))
    return segs


def main():
    t0 = time.time()
    RUN_DIR.mkdir(parents=True, exist_ok=True)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    WT_MODELS.mkdir(parents=True, exist_ok=True)

    manifest = json.loads((BANK_DIR / "bank_manifest.json").read_text(encoding="utf-8"))
    chapters = sorted(manifest["chapters"].values(), key=lambda c: c["source_folder"])
    total = sum(c["page_count"] for c in chapters)
    print(f"=== Bank-1111 W8A8 pipeline ===", flush=True)
    print(f"bank chapters: {len(chapters)}, total samples: {total}", flush=True)

    # Deterministic held-out selection over the global ordered sample list
    heldout_positions = set(int(x) for x in np.linspace(0, total - 1, HELDOUT_COUNT, dtype=int))
    global_idx = 0
    calib_segments, heldout_meta = [], []
    for ci, ch in enumerate(chapters):
        for local_i in range(ch["page_count"]):
            if global_idx in heldout_positions:
                heldout_meta.append({"global_index": global_idx, "chapter": ch["source_folder"],
                                     "npz": ch["npz"], "local_index": local_i})
            global_idx += 1

    # Calibration segments = all samples, split into chunks, minus held-out pages
    raw_segments = build_segments(chapters)
    for ci, s, e in raw_segments:
        keep_local = [i for i in range(s, e) if (sum(c2["page_count"] for c2 in chapters[:ci]) + i) not in heldout_positions]
        if keep_local:
            # contiguous runs only (held-out picks are spread, so runs may split)
            runs, start = [], keep_local[0]
            for a, b in zip(keep_local, keep_local[1:]):
                if b != a + 1:
                    runs.append((start, a + 1)); start = b
            runs.append((start, keep_local[-1] + 1))
            for rs, re in runs:
                calib_segments.append((ci, rs, re))
    calib_total = sum(e - s for _, s, e in calib_segments)
    print(f"held-out: {len(heldout_meta)}, calibration samples: {calib_total}, segments: {len(calib_segments)}", flush=True)
    save_progress({
        "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "bank_dir": str(BANK_DIR), "total_samples": total,
        "heldout_count": len(heldout_meta), "calib_samples": calib_total,
        "heldout_meta": heldout_meta,
        "completed_segments": [], "calib_seconds": 0.0,
    })

    # Resume support
    prev_result, done_segs = None, []
    if CHECKPOINT.exists():
        try:
            blob = pickle.loads(CHECKPOINT.read_bytes())
            prev_result, done_segs = blob["calibration_result"], blob["completed_segments"]
            print(f"resuming from checkpoint: {len(done_segs)}/{len(calib_segments)} segments done", flush=True)
        except Exception as e:
            print(f"checkpoint unreadable ({e}); starting fresh", flush=True)

    qt = lq.Quantizer(float_model=str(MODEL_IN))
    rp = recipe_manager.RecipeManager()
    rp.add_static_config(
        regex=".*",
        operation_name=TFLOperationName.CONV_2D,
        activation_num_bits=8,
        weight_num_bits=8,
    )
    qt.load_quantization_recipe(rp.get_quantization_recipe())
    print("conv-only wi8/ai8 recipe configured", flush=True)

    interp_in = Interpreter(model_path=str(MODEL_IN))
    input_name = interp_in.get_input_details()[0]["name"]
    print(f"input tensor: '{input_name}', shape {interp_in.get_input_details()[0]['shape']}", flush=True)

    st = json.loads(PROGRESS.read_text())
    cache = {}
    def chapter_array(ci: int) -> np.ndarray:
        if ci not in cache:
            if len(cache) >= 2:
                for k in list(cache)[:-1]:
                    del cache[k]
            ch = chapters[ci]
            cache[ci] = np.load(BANK_DIR / ch["npz"])["inputs"]
        return cache[ci]

    seg_idx = 0
    for ci, s, e in calib_segments:
        seg_idx += 1
        if seg_idx <= len(done_segs):
            continue
        t_c = time.time()
        arrs = [a for a in chapter_array(ci)[s:e]]
        chunk = {"serving_default": [{input_name: a} for a in arrs]}
        prev_result = qt.calibrate(chunk, num_threads=NUM_THREADS,
                                   mode=CalibrationMode.CALIBRATION_PROFILER_BASED,
                                   previous_calibration_result=prev_result)
        done_segs.append(seg_idx)
        dt = time.time() - t_c
        blob = pickle.dumps({"calibration_result": prev_result, "completed_segments": done_segs})
        CHECKPOINT.write_bytes(blob)
        st = json.loads(PROGRESS.read_text())
        st["completed_segments"] = done_segs
        st["calib_seconds"] += dt
        save_progress(st)
        print(f"[calib {seg_idx}/{len(calib_segments)}] {chapters[ci]['source_folder']}[{s}:{e}] "
              f"{e-s} samples in {dt:.1f}s (eta {(len(calib_segments)-seg_idx)*(st['calib_seconds']/seg_idx)/60:.0f} min)", flush=True)

    print(f"calibration complete: {calib_total} samples in {st['calib_seconds']:.0f}s", flush=True)

    # Quantize + serialize (mid-quantization checkpoint artifact)
    t_q = time.time()
    QUANT_OUT = OUT_DIR / "mcv2_576x832_w8a8_bank1111.tflite"
    qt.quantize(calibration_result=prev_result, serialize_to_path=str(QUANT_OUT))
    q_sha = sha256_of_file(QUANT_OUT)
    print(f"quantized model saved: {QUANT_OUT} ({QUANT_OUT.stat().st_size:,} B) sha256={q_sha} ({time.time()-t_q:.1f}s)", flush=True)

    interp = Interpreter(model_path=str(QUANT_OUT))
    interp.allocate_tensors()
    details = interp.get_tensor_details()
    n_int8 = sum(1 for td in details if "int8" in str(td["dtype"]))
    print(f"quantized model reload check: tensors={len(details)}, int8 tensors={n_int8}", flush=True)

    # Copy quantized tflite into the worktree BEFORE AOT (checkpoint artifact)
    wt_quant = WT_MODELS / QUANT_OUT.name
    shutil.copy2(QUANT_OUT, wt_quant)
    print(f"copied to worktree: {wt_quant} (sha256={sha256_of_file(wt_quant)})", flush=True)

    # AOT compile for SM8650
    print("=== AOT compile: Qualcomm SM8650 ===", flush=True)
    soc = target.Target(soc_model=target.SocModel.SM8650)
    t_a = time.time()
    result = aot_compile.aot_compile(str(QUANT_OUT), output_dir=str(OUT_DIR / "aot_out"), target=[soc], keep_going=True)
    print(f"AOT compilation completed in {time.time()-t_a:.1f}s", flush=True)
    report_text = None
    try:
        report_text = result.compilation_report()
        print(f"Compilation Report:\n{report_text}", flush=True)
    except Exception as e:
        print(f"report fetch warning: {e}", flush=True)

    # Copy artifacts + summary into the worktree
    summary = {
        "created_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "base_float_model": str(MODEL_IN),
        "bank_dir": str(BANK_DIR),
        "bank_chapters": len(chapters),
        "bank_total_samples": total,
        "calibration_samples_used": calib_total,
        "heldout_count": len(heldout_meta),
        "heldout_meta": heldout_meta,
        "quantized_tflite": {"name": QUANT_OUT.name, "size": QUANT_OUT.stat().st_size, "sha256": q_sha},
        "int8_tensors": n_int8,
        "total_tensors": len(details),
        "aot_report": report_text,
        "calib_seconds": st["calib_seconds"],
    }
    aot_files = []
    for f in sorted((OUT_DIR / "aot_out").rglob("*")):
        if f.is_file():
            shutil.copy2(f, WT_MODELS / f.name)
            aot_files.append({"name": f.name, "size": f.stat().st_size, "sha256": sha256_of_file(f)})
            print(f"  AOT artifact: {f.name} ({f.stat().st_size:,} B, sha256={sha256_of_file(f)})", flush=True)
    summary["aot_artifacts"] = aot_files
    (WT_MODELS / "quantization_summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"summary written: {WT_MODELS / 'quantization_summary.json'}", flush=True)
    print(f"=== PIPELINE DONE in {time.time()-t0:.1f}s ===", flush=True)


if __name__ == "__main__":
    main()
