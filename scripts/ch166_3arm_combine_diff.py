#!/usr/bin/env python3
"""ch166 3-arm combine diff: NEW bank-1111 W8A8 vs ORIGINAL recipe (PyTorch fp32)
vs CURRENT APK recipe W8A8 — raw model outputs vs post-combine outputs, per mode.

Modes mirror the app's combine selection:
  vibrant  = current production merge (strength 0.35)
  original = faithful merge (strength 1.0: model lightness+chroma outside ink)

Measures, per arm per mode: raw/post saturation, saturation gain, gamut clamp
rates, Lab roundtrip baseline, combiner PSNR; plus cross-arm PSNRs.
Numeric only — nothing here renders or views images.
"""

import json
import time
from datetime import datetime, timezone
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

WORKTREE = Path(r"C:\Users\ADMIN\.traycer\worktrees\kimreneouk__tachiyomiatvibe\traycer-happy-badger-043b589375fe")
CH166 = WORKTREE / "scratch" / "ch166_3arm"
CHAPTER_DIR = Path(r"C:\Users\ADMIN\Documents\Coding Related\tachiyomiATVIBE") / "npu_route" / "calibration" / "rawkuma" / "isekai-walking" / "chapter-166.433236"
METRICS_OUT = WORKTREE / "saved_models" / "recipe_calibrated_w8a8_bank1111" / "ch166_3arm_combine_diff.json"

MODES = {"vibrant": 0.35, "original": 1.0}

WHITE_X, WHITE_Y, WHITE_Z = 0.95047, 1.0, 1.08883
LAB_KAPPA, LAB_EPSILON = 903.3, 0.008856


def srgb_linearize(c):
    c = np.clip(c, 0.0, 1.0)
    return np.where(c > 0.04045, ((c + 0.055) / 1.055) ** 2.4, c / 12.92)


def rgb_to_lab(rgb):
    lr, lg, lb = srgb_linearize(rgb[..., 0]), srgb_linearize(rgb[..., 1]), srgb_linearize(rgb[..., 2])
    x = 0.412453 * lr + 0.357580 * lg + 0.180423 * lb
    y = 0.212671 * lr + 0.715160 * lg + 0.072169 * lb
    z = 0.019334 * lr + 0.119193 * lg + 0.950227 * lb

    def f(val):
        return np.where(val > LAB_EPSILON, np.cbrt(val), (LAB_KAPPA * val + 16.0) / 116.0)

    fx, fy, fz = f(x / WHITE_X), f(y / WHITE_Y), f(z / WHITE_Z)
    L = 116.0 * fy - 16.0
    a = 500.0 * (fx - fy)
    b = 200.0 * (fy - fz)
    return np.stack([L, a, b], axis=-1)


def lab_to_rgb_instr(lab):
    """Device-faithful lab_to_rgb that reports gamut clipping in the linear domain."""
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    fy = (L + 16.0) / 116.0
    fx, fz = fy + a / 500.0, fy - b / 200.0

    def f_inv(val):
        c = val ** 3
        return np.where(c > LAB_EPSILON, c, (val * 116.0 - 16.0) / LAB_KAPPA)

    x, y, z = WHITE_X * f_inv(fx), WHITE_Y * f_inv(fy), WHITE_Z * f_inv(fz)
    lin_r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
    lin_g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
    lin_b = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
    lin = np.stack([lin_r, lin_g, lin_b], axis=-1)
    clipped = (lin < 0.0) | (lin > 1.0)
    comp_pct = float(clipped.mean() * 100.0)
    pixel_pct = float(clipped.any(axis=-1).mean() * 100.0)

    def srgb_comp(c):
        c = np.clip(c, 0.0, 1.0)
        return np.where(c > 0.0031308, 1.055 * (c ** (1.0 / 2.4)) - 0.055, 12.92 * c)

    rgb = np.stack([srgb_comp(lin_r), srgb_comp(lin_g), srgb_comp(lin_b)], axis=-1)
    return np.clip(rgb, 0.0, 1.0), comp_pct, pixel_pct


def smoothstep(low, high, val):
    t = np.clip((val - low) / abs(high - low), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def ink_merge_instr(src_rgb, colored_raw, strength):
    """Variant C merge identical to the device CPU twin; returns merged uint8 + clamp stats."""
    orig_h, orig_w = src_rgb.shape[:2]
    colored_scaled = cv2.resize(colored_raw, (orig_w, orig_h), interpolation=cv2.INTER_LINEAR)
    src_f = src_rgb.astype(np.float32) / 255.0
    orig_lab = rgb_to_lab(src_f)
    col_lab = rgb_to_lab(colored_scaled)
    gray = 0.299 * src_f[..., 0] + 0.587 * src_f[..., 1] + 0.114 * src_f[..., 2]
    weight = strength * smoothstep(0.12, 0.45, gray)
    merged_L = orig_lab[..., 0] * (1.0 - weight) + col_lab[..., 0] * weight
    merged_lab = np.stack([merged_L, col_lab[..., 1], col_lab[..., 2]], axis=-1)
    merged_rgb, comp_pct, pixel_pct = lab_to_rgb_instr(merged_lab)
    merged = (merged_rgb * 255.0 + 0.5).clip(0, 255).astype(np.uint8)
    return merged, comp_pct, pixel_pct


def compute_psnr(a, b):
    if a.shape != b.shape:
        b = cv2.resize(b, (a.shape[1], a.shape[0]), interpolation=cv2.INTER_AREA)
    mse = np.mean((a.astype(np.float64) / 255.0 - b.astype(np.float64) / 255.0) ** 2)
    return float(10.0 * np.log10(1.0 / max(mse, 1e-10)))


def compute_sat(rgb_uint8):
    f = rgb_uint8.astype(np.float32) / 255.0
    return float(np.mean(np.max(f, axis=-1) - np.min(f, axis=-1)) * 100.0)


def compute_warmth(rgb_uint8):
    f = rgb_uint8.astype(np.float32) / 255.0
    return float(np.mean(f[..., 0] - f[..., 1]))


def measure_arm(src_rgb, raw_crop, strength):
    """Raw + combined metrics for one arm on one page."""
    raw_u8 = (np.clip(raw_crop, 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8)
    merged, comp_pct, pixel_pct = ink_merge_instr(src_rgb, raw_crop, strength)
    rt_rgb, rt_comp, _ = lab_to_rgb_instr(rgb_to_lab(np.clip(raw_crop, 0.0, 1.0)))
    return {
        "raw_sat": round(compute_sat(raw_u8), 2),
        "raw_warmth": round(compute_warmth(raw_u8), 4),
        "comb_sat": round(compute_sat(merged), 2),
        "comb_warmth": round(compute_warmth(merged), 4),
        "sat_gain": round(compute_sat(merged) - compute_sat(raw_u8), 2),
        "clamp_comp_pct": round(comp_pct, 3),
        "clamp_pixel_pct": round(pixel_pct, 3),
        "roundtrip_clamp_comp_pct": round(rt_comp, 3),
        "combiner_psnr": round(compute_psnr(merged, raw_u8), 2),
    }


def main():
    print("=== ch166 3-arm combine diff (modes: vibrant=0.35 / original=1.0) ===", flush=True)
    t0 = time.time()
    pads = json.loads((CH166 / "ch166_pads.json").read_text(encoding="utf-8"))
    stacks = {
        "new_bank1111": np.load(CH166 / "ch166_new_bank1111_outs.npz")["outputs"],
        "current_recipe": np.load(CH166 / "ch166_current_recipe_outs.npz")["outputs"],
        "fp32_torch": np.load(CH166 / "ch166_fp32_torch_outs.npz")["outputs"],
    }
    print(f"arm stacks: {{k: v.shape for k, v in stacks.items()}}", flush=True)

    sources = [np.array(Image.open(CHAPTER_DIR / item["file"]).convert("RGB")) for item in pads]

    mode_reports = {}
    for mode_label, strength in MODES.items():
        print(f"\n--- mode {mode_label} (strength={strength}) ---", flush=True)
        pages = []
        acc = {arm: {k: [] for k in (
            "raw_sat", "comb_sat", "sat_gain", "clamp_comp_pct", "clamp_pixel_pct",
            "roundtrip_clamp_comp_pct", "combiner_psnr", "raw_warmth", "comb_warmth")}
            for arm in stacks}
        cross = {k: [] for k in (
            "psnr_raw_new_vs_torch", "psnr_raw_cur_vs_torch",
            "psnr_comb_new_vs_torch", "psnr_comb_cur_vs_torch", "psnr_comb_new_vs_cur")}

        for i, item in enumerate(pads):
            src_rgb = sources[i]
            crop_h, crop_w = item["crop_h"], item["crop_w"]
            per_arm = {}
            merged = {}
            for arm, stack in stacks.items():
                raw_crop = np.clip(stack[i][:crop_h, :crop_w].astype(np.float32), 0.0, 1.0)
                per_arm[arm] = measure_arm(src_rgb, raw_crop, strength)
                for k, v in per_arm[arm].items():
                    if k in acc[arm]:
                        acc[arm][k].append(v)
                merged[arm], _, _ = ink_merge_instr(src_rgb, raw_crop, strength)

            raw_torch = np.clip(stacks["fp32_torch"][i][:crop_h, :crop_w].astype(np.float32), 0.0, 1.0)
            raw_new = np.clip(stacks["new_bank1111"][i][:crop_h, :crop_w].astype(np.float32), 0.0, 1.0)
            raw_cur = np.clip(stacks["current_recipe"][i][:crop_h, :crop_w].astype(np.float32), 0.0, 1.0)
            c = {
                "psnr_raw_new_vs_torch": round(compute_psnr(raw_new, raw_torch), 2),
                "psnr_raw_cur_vs_torch": round(compute_psnr(raw_cur, raw_torch), 2),
                "psnr_comb_new_vs_torch": round(compute_psnr(merged["new_bank1111"], merged["fp32_torch"]), 2),
                "psnr_comb_cur_vs_torch": round(compute_psnr(merged["current_recipe"], merged["fp32_torch"]), 2),
                "psnr_comb_new_vs_cur": round(compute_psnr(merged["new_bank1111"], merged["current_recipe"]), 2),
            }
            for k, v in c.items():
                cross[k].append(v)

            pages.append({"page": item["page"], "file": item["file"], "arms": per_arm, "cross": c})
            a_new, a_cur = per_arm["new_bank1111"], per_arm["current_recipe"]
            print(
                f"  p{item['page']:02d} sat {a_new['raw_sat']:.1f}->{a_new['comb_sat']:.1f} | "
                f"clamp% new {a_new['clamp_pixel_pct']:.2f} cur {a_cur['clamp_pixel_pct']:.2f}",
                flush=True,
            )

        def mean(arm, key):
            return round(float(np.mean(acc[arm][key])), 2)

        mode_reports[mode_label] = {
            "strength": strength,
            "arms": {arm: {k: mean(arm, k) for k in acc[arm]} for arm in stacks},
            "cross": {k: round(float(np.mean(v)), 2) for k, v in cross.items()},
            "pages": pages,
        }

        print(f"\n=== SUMMARY mode={mode_label} (means over {len(pages)} pages) ===", flush=True)
        print(f"{'metric':<28}{'new_bank1111':>14}{'current_apk':>14}{'fp32_torch':>14}", flush=True)
        for k in ("raw_sat", "comb_sat", "sat_gain", "clamp_comp_pct", "clamp_pixel_pct",
                  "combiner_psnr", "comb_warmth"):
            row = f"{k:<28}"
            for arm in ("new_bank1111", "current_recipe", "fp32_torch"):
                row += f"{mode_reports[mode_label]['arms'][arm][k]:>14}"
            print(row, flush=True)
        print(f"{'psnr comb vs torch comb':<28}{mode_reports[mode_label]['cross']['psnr_comb_new_vs_torch']:>14}"
              f"{mode_reports[mode_label]['cross']['psnr_comb_cur_vs_torch']:>14}{'--':>14}", flush=True)
        print(f"{'psnr comb new vs cur':<28}{mode_reports[mode_label]['cross']['psnr_comb_new_vs_cur']:>14}", flush=True)

    out = {
        "chapter": "isekai-walking chapter-166.433236",
        "pages": len(pads),
        "created_at": datetime.now(timezone.utc).isoformat(),
        "modes": mode_reports,
    }
    METRICS_OUT.write_text(json.dumps(out, indent=2), encoding="utf-8")
    print(f"\nwritten: {METRICS_OUT}", flush=True)
    print(f"done in {time.time() - t0:.1f}s", flush=True)


if __name__ == "__main__":
    main()
