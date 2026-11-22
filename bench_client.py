"""bench_client — routes panel-mode jobs to the desktop bench (port 8777),
which owns the FLUX.2 panel pipeline: Panel Studio detection, per-panel
generation, feathered stitch and optional Real-ESRGAN upscale. MCT2 stays a
thin caller so the stitch geometry lives in exactly one codebase.
"""
import time
from typing import Optional

import requests

BENCH_URL = "http://127.0.0.1:8777"
POLL_SECS = 1.0
SUBMIT_TIMEOUT = 120
POLL_TIMEOUT = 15
DOWNLOAD_TIMEOUT = 180
RUN_TIMEOUT_SECS = 900
TERMINAL_FAIL = ("error", "cancelled", "canceled")

# MCT2 sends full checkpoint names; the bench expects engine/encoder keys.
_ENGINE_KEYS = {
    "flux-2-klein-4b-fp8.safetensors": "fp8",
    "flux-2-klein-4b-nvfp4.safetensors": "nvfp4",
}
_ENCODER_KEYS = {
    "qwen_3_4b_fp4_flux2.safetensors": "fp4",
    "qwen_3_4b.safetensors": "bf16",
}


def colorize_panel(
    image_bytes: bytes,
    ref_bytes: Optional[bytes],
    prompt_text: str,
    steps: int,
    megapixels: float,
    unet_name: Optional[str],
    encoder_name: Optional[str],
    panel_upscale: bool,
    cancel_event=None,
) -> Optional[bytes]:
    """Submit one panel-mode job and block until the stitched PNG is ready.
    Returns None when cancelled (matches generate_single_image's contract)."""
    fields = {
        "model": "flux2klein",
        "f2_engine": _ENGINE_KEYS.get(unet_name or "", "nvfp4"),
        "f2_encoder": _ENCODER_KEYS.get(encoder_name or "", "fp4"),
        "f2_prompt": prompt_text,
        "f2_steps": str(int(steps)),
        "f2_megapixels": str(float(megapixels)),
        "f2_panel_mode": "1",
        "f2_panel_upscale": "1" if panel_upscale else "0",
    }
    files = [("image", ("page.png", image_bytes))]
    if ref_bytes:
        files.append(("refs", ("ref.png", ref_bytes)))
    resp = requests.post(f"{BENCH_URL}/api/colorize", data=fields, files=files,
                         timeout=SUBMIT_TIMEOUT)
    if not resp.ok:
        raise RuntimeError(f"bench rejected panel job ({resp.status_code}): {resp.text[:300]}")
    job_id = resp.json()["job_id"]

    deadline = time.time() + RUN_TIMEOUT_SECS
    while time.time() < deadline:
        if cancel_event is not None and cancel_event.is_set():
            requests.post(f"{BENCH_URL}/api/cancel/{job_id}", timeout=POLL_TIMEOUT)
            return None
        rec = requests.get(f"{BENCH_URL}/api/job/{job_id}", timeout=POLL_TIMEOUT).json()
        status = rec.get("status")
        if status == "done":
            url = (rec.get("result") or {}).get("url")
            if not url:
                raise RuntimeError("bench job done but result carries no url")
            png = requests.get(f"{BENCH_URL}{url}", timeout=DOWNLOAD_TIMEOUT)
            if not png.ok:
                raise RuntimeError(f"bench result download failed ({png.status_code})")
            return png.content
        if status in TERMINAL_FAIL:
            raise RuntimeError(f"bench panel job failed: {rec.get('error') or status}")
        time.sleep(POLL_SECS)
    raise TimeoutError(f"bench panel job timed out after {RUN_TIMEOUT_SECS}s")
