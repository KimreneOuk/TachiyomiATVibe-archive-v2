#!/usr/bin/env python3
"""Stage 3 (gal695840 3-arm test): ink merge all arms, numeric metrics, and the
user-facing comparison artifact for nhentai 695840 pages 045-068.
Adapted from scripts/stage3_ch166_metrics.py; all-new output dirs.
Images are written to disk for the USER to inspect; nothing here is rendered to,
or viewed by, the agent."""

import json
import time
from pathlib import Path

import cv2
import numpy as np
from PIL import Image

WORKTREE = Path(r"C:\Users\ADMIN\.traycer\worktrees\kimreneouk__tachiyomiatvibe\traycer-happy-badger-043b589375fe")
GAL = WORKTREE / "scratch" / "gal695840_3arm"

ARTIFACT_DIR = Path(r"C:\Users\ADMIN\.traycer\epics\7f9db2bc-6dee-4e24-89e3-af5704d3e90a\artifacts\bank1111-gal695840-3arm")
IMAGES_DIR = ARTIFACT_DIR / "images"
METRICS_OUT = WORKTREE / "saved_models" / "recipe_calibrated_w8a8_bank1111" / "gal695840_3arm_metrics.json"
QUANT_SUMMARY = WORKTREE / "saved_models" / "recipe_calibrated_w8a8_bank1111" / "quantization_summary.json"

# Ink-aware merge constants (identical to ch34/ch166 methodology)
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


def lab_to_rgb(lab):
    L, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    fy = (L + 16.0) / 116.0
    fx, fz = fy + a / 500.0, fy - b / 200.0
    def f_inv(val):
        c = val ** 3
        return np.where(c > LAB_EPSILON, c, (val * 116.0 - 16.0) / LAB_KAPPA)
    x, y, z = WHITE_X * f_inv(fx), WHITE_Y * f_inv(fy), WHITE_Z * f_inv(fz)
    def srgb_comp(lin):
        c = np.clip(lin, 0.0, 1.0)
        return np.where(c > 0.0031308, 1.055 * (c ** (1.0 / 2.4)) - 0.055, 12.92 * c)
    r = srgb_comp(3.2404542 * x - 1.5371385 * y - 0.4985314 * z)
    g = srgb_comp(-0.9692660 * x + 1.8760108 * y + 0.0415560 * z)
    b_out = srgb_comp(0.0556434 * x - 0.2040259 * y + 1.0572252 * z)
    return np.clip(np.stack([r, g, b_out], axis=-1), 0.0, 1.0)


def smoothstep(low, high, val):
    t = np.clip((val - low) / abs(high - low), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def ink_merge(src_rgb: np.ndarray, colored_raw: np.ndarray, strength: float = 0.35) -> np.ndarray:
    orig_h, orig_w = src_rgb.shape[:2]
    colored_scaled = cv2.resize(colored_raw, (orig_w, orig_h), interpolation=cv2.INTER_LINEAR)
    src_f = src_rgb.astype(np.float32) / 255.0
    orig_lab = rgb_to_lab(src_f)
    col_lab = rgb_to_lab(colored_scaled)
    gray = 0.299 * src_f[..., 0] + 0.587 * src_f[..., 1] + 0.114 * src_f[..., 2]
    weight = strength * smoothstep(0.12, 0.45, gray)
    merged_L = orig_lab[..., 0] * (1.0 - weight) + col_lab[..., 0] * weight
    merged_lab = np.stack([merged_L, col_lab[..., 1], col_lab[..., 2]], axis=-1)
    merged_rgb = lab_to_rgb(merged_lab)
    return (merged_rgb * 255.0 + 0.5).clip(0, 255).astype(np.uint8)


def compute_psnr(a: np.ndarray, b: np.ndarray) -> float:
    if a.shape != b.shape:
        b = cv2.resize(b, (a.shape[1], a.shape[0]), interpolation=cv2.INTER_AREA)
    mse = np.mean((a.astype(np.float64) / 255.0 - b.astype(np.float64) / 255.0) ** 2)
    return float(10.0 * np.log10(1.0 / max(mse, 1e-10)))


def compute_sat(rgb_uint8: np.ndarray) -> float:
    f = rgb_uint8.astype(np.float32) / 255.0
    return float(np.mean(np.max(f, axis=-1) - np.min(f, axis=-1)) * 100.0)


def compute_warmth(rgb_uint8: np.ndarray) -> float:
    f = rgb_uint8.astype(np.float32) / 255.0
    return float(np.mean(f[..., 0] - f[..., 1]))


def heldout_local_indices() -> set:
    """Local indices of this gallery's pages that were held out of calibration."""
    qs = json.loads(QUANT_SUMMARY.read_text(encoding="utf-8"))
    idxs = {m["local_index"] for m in qs.get("heldout_meta", []) if "695840" in m.get("npz", "")}
    print(f"held-out local indices for gal695840: {sorted(idxs)}", flush=True)
    return idxs


def build_html(summary: dict, pages: list) -> str:
    def build_card(p: dict) -> str:
        n = p["page"]
        p_name = f"p{n:02d}"
        set_badge = ('<span class="pill heldout">HELD-OUT page</span>'
                     if p["heldout"] else '<span class="pill">calibration page</span>')
        cols = [
            ("col-src", "Source (grayscale)", ""),
            ("col-new", "★ NEW bank-1111 W8A8", "accent"),
            ("col-cur", "CURRENT recipe W8A8", ""),
            ("col-ref", "PyTorch FP32 (author pipeline)", "muted"),
        ]
        col_html = ""
        for cls, label, style in cols:
            tag = cls.replace("col-", "")
            if tag in ("new", "cur"):
                metric = f"{p[f'psnr_{tag}_vs_ref']:.2f} dB"
            elif tag == "ref":
                metric = "reference"
            else:
                metric = "input"
            col_html += f"""
        <div class="img-col {cls}">
          <div class="col-title"><span class="{style}">{label}</span><span class="col-metric">{metric}</span></div>
          <figure class="frame"><img src="images/{p_name}_{tag}.jpg" loading="lazy" alt="{label} page {n}"></figure>
        </div>"""
        return f"""
    <section class="page-card">
      <header class="card-head">
        <h3>Page {p['file_no']:03d} <span class="muted">({p['orig_size'][0]}×{p['orig_size'][1]})</span></h3>
        <div class="pills">
          {set_badge}
          <span class="pill">new vs FP32: <b>{p['psnr_new_vs_ref']:.2f} dB</b></span>
          <span class="pill">current vs FP32: <b>{p['psnr_cur_vs_ref']:.2f} dB</b></span>
          <span class="pill">Δ(new−cur): <b class="{'good' if p['psnr_new_vs_ref'] >= p['psnr_cur_vs_ref'] else 'bad'}">{p['psnr_new_vs_ref'] - p['psnr_cur_vs_ref']:+.2f} dB</b></span>
          <span class="pill">Sat n/c/ref: <b>{p['sat_new']:.1f}/{p['sat_cur']:.1f}/{p['sat_ref']:.1f}</b></span>
        </div>
      </header>
      <div class="img-grid">{col_html}
      </div>
    </section>"""

    cards = "\n".join(build_card(p) for p in pages)

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>nhentai 695840 p045-068 — bank-1111 vs current recipe W8A8 vs FP32</title>
<style>
  * {{ box-sizing: border-box; }}
  body {{ margin: 0; padding: 16px; font-family: var(--font-sans, system-ui, sans-serif);
         background: var(--color-background-primary); color: var(--color-text-primary);
         line-height: 1.5; }}
  h1 {{ font-size: 1.3rem; margin: 0 0 4px; }}
  h2 {{ font-size: 1.05rem; margin: 20px 0 8px; }}
  .muted {{ color: var(--color-text-tertiary); font-weight: 400; font-size: 0.9rem; }}
  .lead {{ color: var(--color-text-secondary); font-size: 0.9rem; margin: 0 0 16px; }}
  .stats {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
            gap: 12px; margin-bottom: 16px; }}
  .stat {{ background: var(--color-background-secondary); border: 1px solid var(--color-border-secondary);
           border-radius: var(--border-radius-lg); padding: 12px; min-width: 0; }}
  .stat .k {{ font-size: 0.72rem; color: var(--color-text-tertiary); text-transform: uppercase;
              letter-spacing: 0.05em; }}
  .stat .v {{ font-size: 1.2rem; font-weight: 700; }}
  .stat .s {{ font-size: 0.78rem; color: var(--color-text-secondary); }}
  .good {{ color: var(--color-text-success, #1a7f37); }} .bad {{ color: var(--color-text-danger, #d1242f); }}
  .heldout {{ border-color: var(--color-text-info); color: var(--color-text-info); font-weight: 600; }}
  .controls {{ display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 16px; }}
  .controls button {{ background: var(--color-background-secondary); color: var(--color-text-primary);
    border: 1px solid var(--color-border-secondary); border-radius: var(--border-radius-md);
    padding: 6px 12px; font-size: 0.82rem; cursor: pointer; }}
  .controls button[aria-pressed="true"] {{ background: var(--color-background-inverse);
    color: var(--color-text-inverse); border-color: transparent; font-weight: 600; }}
  .page-card {{ background: var(--color-background-secondary); border: 1px solid var(--color-border-secondary);
    border-radius: var(--border-radius-lg); padding: 14px; margin-bottom: 20px; min-width: 0; }}
  .card-head {{ display: flex; justify-content: space-between; align-items: flex-start; gap: 8px;
    flex-wrap: wrap; border-bottom: 1px solid var(--color-border-secondary);
    padding-bottom: 8px; margin-bottom: 12px; }}
  .card-head h3 {{ font-size: 0.95rem; margin: 0; }}
  .pills {{ display: flex; gap: 6px; flex-wrap: wrap; }}
  .pill {{ font-size: 0.78rem; background: var(--color-background-tertiary); padding: 3px 8px;
    border-radius: 999px; border: 1px solid var(--color-border-secondary); white-space: nowrap; }}
  .img-grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }}
  .img-col {{ min-width: 0; }}
  .col-title {{ display: flex; justify-content: space-between; gap: 6px; font-size: 0.78rem;
    color: var(--color-text-secondary); margin-bottom: 6px; }}
  .accent {{ color: var(--color-text-info); font-weight: 600; }}
  .frame {{ margin: 0; border: 1px solid var(--color-border-secondary);
    border-radius: var(--border-radius-md); overflow: hidden; background: #000; }}
  .frame img {{ width: 100%; height: auto; display: block; max-width: 100%; }}
  table {{ border-collapse: collapse; width: 100%; font-size: 0.82rem; }}
  th, td {{ border: 1px solid var(--color-border-secondary); padding: 4px 8px; text-align: right; }}
  th:first-child, td:first-child {{ text-align: left; }}
  .scroll {{ overflow-x: auto; margin-top: 8px; }}
</style>
</head>
<body>
<h1>nhentai 695840 (Fuyuyasumi) — pages 045–068 · 3-arm comparison <span class="muted">bank-1111 recalibration vs current recipe W8A8</span></h1>
<p class="lead">24 pages · author recipe preprocessing (FFDNet σ25 → resize_pad 576 → 832×576 canvas) · ink merge 0.35 ·
arms: NEW bank-calibrated W8A8 · CURRENT recipe-calibrated W8A8 · PyTorch FP32 reference (generator_clean.pth) ·
note: this gallery is part of the calibration bank, so these are seen pages (one held-out page in range, marked) —
a qualitative visual check, not an unbiased eval.</p>

<div class="stats">
  <div class="stat"><div class="k">New vs FP32 mean PSNR</div><div class="v">{summary['mean_new_vs_ref']:.2f} dB</div>
    <div class="s">median {summary['median_new_vs_ref']:.2f} dB</div></div>
  <div class="stat"><div class="k">Current vs FP32 mean PSNR</div><div class="v">{summary['mean_cur_vs_ref']:.2f} dB</div>
    <div class="s">median {summary['median_cur_vs_ref']:.2f} dB</div></div>
  <div class="stat"><div class="k">Δ mean (new − current)</div>
    <div class="v {'good' if summary['mean_delta'] >= 0 else 'bad'}">{summary['mean_delta']:+.2f} dB</div>
    <div class="s">{summary['pages_new_better']}/24 pages new ≥ current</div></div>
  <div class="stat"><div class="k">Saturation (n / c / ref)</div>
    <div class="v">{summary['mean_sat_new']:.1f} / {summary['mean_sat_cur']:.1f} / {summary['mean_sat_ref']:.1f}</div>
    <div class="s">mean over pages</div></div>
  <div class="stat"><div class="k">Warmth R−G (n / c / ref)</div>
    <div class="v">{summary['mean_warm_new']:.3f} / {summary['mean_warm_cur']:.3f} / {summary['mean_warm_ref']:.3f}</div>
    <div class="s">mean over pages</div></div>
</div>

<div class="controls" role="group" aria-label="Display columns">
  <button aria-pressed="true" data-mode="all">All 4 arms</button>
  <button aria-pressed="false" data-mode="quant">Quantized only</button>
  <button aria-pressed="false" data-mode="newref">New vs FP32</button>
</div>

{cards}

<h2>Per-page metrics</h2>
<div class="scroll"><table>
  <thead><tr><th>Page</th><th>Set</th><th>PSNR new vs ref</th><th>PSNR cur vs ref</th><th>Δ</th>
  <th>Sat new</th><th>Sat cur</th><th>Sat ref</th><th>Warmth new</th><th>Warmth cur</th><th>Warmth ref</th></tr></thead>
  <tbody>
  {''.join(f"<tr><td>{p['file_no']:03d}</td><td>{'held-out' if p['heldout'] else 'calib'}</td><td>{p['psnr_new_vs_ref']:.2f}</td><td>{p['psnr_cur_vs_ref']:.2f}</td><td>{p['psnr_new_vs_ref'] - p['psnr_cur_vs_ref']:+.2f}</td><td>{p['sat_new']:.1f}</td><td>{p['sat_cur']:.1f}</td><td>{p['sat_ref']:.1f}</td><td>{p['warmth_new']:.3f}</td><td>{p['warmth_cur']:.3f}</td><td>{p['warmth_ref']:.3f}</td></tr>" for p in pages)}
  </tbody>
</table></div>

<script>
  function setMode(mode, btn) {{
    document.querySelectorAll('.controls button').forEach(b => b.setAttribute('aria-pressed', b === btn ? 'true' : 'false'));
    document.querySelectorAll('.col-src, .col-cur, .col-ref').forEach(el => el.style.display =
      mode === 'all' ? '' : (mode === 'quant' && el.classList.contains('col-cur')) ? '' : 'none');
    document.querySelectorAll('.col-new').forEach(el => el.style.display = '');
    if (mode === 'newref') document.querySelectorAll('.col-ref').forEach(el => el.style.display = '');
  }}
  document.querySelectorAll('.controls button').forEach(b =>
    b.addEventListener('click', () => setMode(b.dataset.mode, b)));
</script>
</body>
</html>"""


def main():
    print("=== Stage 3 (gal695840): Merge, Metrics & Artifact ===", flush=True)
    t0 = time.time()
    IMAGES_DIR.mkdir(parents=True, exist_ok=True)
    METRICS_OUT.parent.mkdir(parents=True, exist_ok=True)

    pads = json.loads((GAL / "gal695840_pads.json").read_text(encoding="utf-8"))
    heldout_idx = heldout_local_indices()
    raw = {
        "new_bank1111": np.load(GAL / "gal695840_new_bank1111_outs.npz")["outputs"],
        "current_recipe": np.load(GAL / "gal695840_current_recipe_outs.npz")["outputs"],
        "fp32_torch": np.load(GAL / "gal695840_fp32_torch_outs.npz")["outputs"],
        "fp32_tflite": np.load(GAL / "gal695840_fp32_tflite_outs.npz")["outputs"],
    }
    print(f"loaded 4 arm stacks: {[v.shape for v in raw.values()]}", flush=True)

    page_results = []
    for i, item in enumerate(pads):
        page_num = item["page"]
        src_rgb = np.array(Image.open(GAL / "src" / item["src_copy"]).convert("RGB"))
        orig_h, orig_w = src_rgb.shape[:2]

        merged = {}
        for label, stack in raw.items():
            crop_h, crop_w = item["crop_h"], item["crop_w"]
            raw_crop = stack[i][:crop_h, :crop_w]
            merged[label] = ink_merge(src_rgb, raw_crop, strength=0.35)

        new_m, cur_m, ref_m = merged["new_bank1111"], merged["current_recipe"], merged["fp32_torch"]
        entry = {
            "page": page_num,
            "file_no": item["file_no"],
            "file": item["file"],
            "heldout": item["local_index"] in heldout_idx,
            "orig_size": [orig_w, orig_h],
            "psnr_new_vs_ref": round(compute_psnr(new_m, ref_m), 2),
            "psnr_cur_vs_ref": round(compute_psnr(cur_m, ref_m), 2),
            "psnr_new_vs_fp32tflite_raw": round(compute_psnr(raw["new_bank1111"][i][:crop_h, :crop_w],
                                                             raw["fp32_tflite"][i][:crop_h, :crop_w]), 2),
            "psnr_cur_vs_fp32tflite_raw": round(compute_psnr(raw["current_recipe"][i][:crop_h, :crop_w],
                                                             raw["fp32_tflite"][i][:crop_h, :crop_w]), 2),
            "sat_new": round(compute_sat(new_m), 1),
            "sat_cur": round(compute_sat(cur_m), 1),
            "sat_ref": round(compute_sat(ref_m), 1),
            "warmth_new": round(compute_warmth(new_m), 4),
            "warmth_cur": round(compute_warmth(cur_m), 4),
            "warmth_ref": round(compute_warmth(ref_m), 4),
        }
        page_results.append(entry)
        print(f"  page {item['file_no']:03d} ({'heldout' if entry['heldout'] else 'calib'}): "
              f"new {entry['psnr_new_vs_ref']:.2f} dB | cur {entry['psnr_cur_vs_ref']:.2f} dB | "
              f"sat {entry['sat_new']:.1f}/{entry['sat_cur']:.1f}/{entry['sat_ref']:.1f}", flush=True)

        max_dim = 1200
        scale = min(1.0, max_dim / max(orig_h, orig_w))
        tw, th = int(orig_w * scale), int(orig_h * scale)
        p_name = f"p{page_num:02d}"
        cv2.imwrite(str(IMAGES_DIR / f"{p_name}_src.jpg"), cv2.cvtColor(cv2.resize(src_rgb, (tw, th), interpolation=cv2.INTER_AREA), cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 85])
        for tag, m in (("new", new_m), ("cur", cur_m), ("ref", ref_m)):
            cv2.imwrite(str(IMAGES_DIR / f"{p_name}_{tag}.jpg"), cv2.cvtColor(cv2.resize(m, (tw, th), interpolation=cv2.INTER_AREA), cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 85])

    new_ps = [p["psnr_new_vs_ref"] for p in page_results]
    cur_ps = [p["psnr_cur_vs_ref"] for p in page_results]
    summary = {
        "gallery": "nhentai 695840 - [Misaki (Aisu Mochi)] Fuyuyasumi, Himitsu no Dousei",
        "pages_range": "045-068",
        "total_pages": len(page_results),
        "mean_new_vs_ref": round(float(np.mean(new_ps)), 2),
        "median_new_vs_ref": round(float(np.median(new_ps)), 2),
        "mean_cur_vs_ref": round(float(np.mean(cur_ps)), 2),
        "median_cur_vs_ref": round(float(np.median(cur_ps)), 2),
        "mean_delta": round(float(np.mean(new_ps) - np.mean(cur_ps)), 2),
        "pages_new_better": sum(1 for a, b in zip(new_ps, cur_ps) if a >= b),
        "mean_psnr_new_vs_fp32tflite_raw": round(float(np.mean([p["psnr_new_vs_fp32tflite_raw"] for p in page_results])), 2),
        "mean_psnr_cur_vs_fp32tflite_raw": round(float(np.mean([p["psnr_cur_vs_fp32tflite_raw"] for p in page_results])), 2),
        "mean_sat_new": round(float(np.mean([p["sat_new"] for p in page_results])), 1),
        "mean_sat_cur": round(float(np.mean([p["sat_cur"] for p in page_results])), 1),
        "mean_sat_ref": round(float(np.mean([p["sat_ref"] for p in page_results])), 1),
        "mean_warm_new": round(float(np.mean([p["warmth_new"] for p in page_results])), 4),
        "mean_warm_cur": round(float(np.mean([p["warmth_cur"] for p in page_results])), 4),
        "mean_warm_ref": round(float(np.mean([p["warmth_ref"] for p in page_results])), 4),
        "pages": page_results,
        "elapsed_seconds": round(time.time() - t0, 1),
    }

    (ARTIFACT_DIR / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    (ARTIFACT_DIR / "index.html").write_text(build_html(summary, page_results), encoding="utf-8")
    METRICS_OUT.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"Stage 3 done in {time.time()-t0:.1f}s", flush=True)
    print(f"artifact: {ARTIFACT_DIR / 'index.html'}", flush=True)
    print(f"metrics:  {METRICS_OUT}", flush=True)


if __name__ == "__main__":
    main()
