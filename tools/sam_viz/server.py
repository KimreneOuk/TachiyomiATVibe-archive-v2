#!/usr/bin/env python3
"""SAM/MCV2 feature visualizer server: serves the UI on 127.0.0.1:8765, proxies
inference to the probe app over `adb forward tcp:18765` (both engines), and
renders visualizations from the returned tensors. All traffic is loopback+USB."""

import base64
import io
import json
import os
import struct
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
from PIL import Image

PHONE_INFER_URL = "http://127.0.0.1:18765/infer"
PHONE_HEALTH_URL = "http://127.0.0.1:18765/health"
LISTEN_PORT = 8765
WORK = 512
GRID = 64
EMBED_DIM = 256
MAX_JOBS = 10

HERE = os.path.dirname(os.path.abspath(__file__))
JOBS = OrderedDict()
JOBS_LOCK = threading.Lock()


def phone_health():
    try:
        with urllib.request.urlopen(PHONE_HEALTH_URL, timeout=3) as response:
            return json.loads(response.read().decode())
    except Exception as failure:  # noqa: BLE001 — report any proxy failure to the UI
        return {"status": "unreachable", "detail": str(failure)}


def phone_infer(image_bytes, engine, hint_bytes=None):
    boundary = "----SamVizBoundary" + uuid.uuid4().hex
    parts = []
    parts.append(
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"engine\"\r\n\r\n{engine}\r\n".encode()
    )
    parts.append(
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"image\"; filename=\"page.png\"\r\n"
        f"Content-Type: application/octet-stream\r\n\r\n".encode() + image_bytes + b"\r\n"
    )
    if hint_bytes:
        parts.append(
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"hints\"; filename=\"hints.png\"\r\n"
            f"Content-Type: image/png\r\n\r\n".encode() + hint_bytes + b"\r\n"
        )
    body = b"".join(parts) + f"--{boundary}--\r\n".encode()
    request = urllib.request.Request(
        PHONE_INFER_URL,
        data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=600) as response:
        return response.read()


def parse_bundle(raw):
    if raw[:4] != b"SVIZ":
        raise ValueError(f"bad magic from phone: {raw[:16]!r}")
    version, header_len = struct.unpack_from("<II", raw, 4)
    if version != 2:
        raise ValueError(f"unsupported bundle version {version}")
    header = json.loads(raw[12 : 12 + header_len].decode("utf-8"))
    offset = 12 + header_len
    tensors = {}
    for section in header["sections"]:
        count = int(section["count"])
        tensors[section["name"]] = np.frombuffer(raw, dtype="<f4", count=count, offset=offset).astype(np.float32)
        offset += count * 4
    return header, tensors


def to_unit8(values, low_pct=2.0, high_pct=98.0):
    low, high = np.percentile(values, [low_pct, high_pct])
    if high - low < 1e-6:
        return np.zeros_like(values, dtype=np.float32)
    return np.clip((values - low) / (high - low), 0.0, 1.0)


def upscale(values, width=WORK, height=WORK):
    """Bilinear-upsamples a [0,1] float plane to an RGB uint8 plane."""
    plane = Image.fromarray((values * 255).astype(np.uint8), mode="L")
    return np.asarray(plane.resize((width, height), Image.BILINEAR), dtype=np.uint8)


def heat_png(values, width=WORK, height=WORK):
    """Black->red->yellow->white heat colormap of a [0,1] plane."""
    plane = Image.fromarray((values * 255).astype(np.uint8), mode="L").resize((width, height), Image.BILINEAR)
    v = np.asarray(plane, dtype=np.float32) / 255.0
    rgb = np.stack(
        [
            np.clip(v * 3.0, 0, 1),
            np.clip(v * 3.0 - 1.0, 0, 1),
            np.clip(v * 3.0 - 2.0, 0, 1),
        ],
        axis=-1,
    )
    return png_from_rgb((rgb * 255).astype(np.uint8))


def gray_png(values):
    return png_from_gray(upscale(values))


def png_from_rgb(rgb):
    buffer = io.BytesIO()
    Image.fromarray(rgb, mode="RGB").save(buffer, format="PNG")
    return buffer.getvalue()


def png_from_gray(gray):
    buffer = io.BytesIO()
    Image.fromarray(gray, mode="L").save(buffer, format="PNG")
    return buffer.getvalue()


def png_from_rgba(rgba):
    buffer = io.BytesIO()
    Image.fromarray(rgba, mode="RGBA").save(buffer, format="PNG")
    return buffer.getvalue()


def page_image(image_bytes, width, height):
    page = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    return np.asarray(page.resize((width, height), Image.BOX), dtype=np.uint8)


# --- Reference grade: faithful port of ColorReferenceAdjuster + ColorSpaceConverter (CIE Lab, D65) ---
LAB_EPSILON = 216.0 / 24389.0
LAB_KAPPA = 24389.0 / 27.0
WHITE_D65 = np.array([0.95047, 1.0, 1.08883], dtype=np.float64)
RGB_TO_XYZ = np.array([
    [0.4124564, 0.3575761, 0.1804375],
    [0.2126729, 0.7151522, 0.0721750],
    [0.0193339, 0.1191920, 0.9503041],
], dtype=np.float64)
XYZ_TO_RGB = np.array([
    [3.2404542, -1.5371385, -0.4985314],
    [-0.9692660, 1.8760108, 0.0415560],
    [0.0556434, -0.2040259, 1.0572252],
], dtype=np.float64)


def srgb_to_linear(values):
    values = values.astype(np.float64) / 255.0
    return np.where(values <= 0.04045, values / 12.92, ((values + 0.055) / 1.055) ** 2.4)


def linear_to_srgb(values):
    values = np.where(values <= 0.0031308, 12.92 * values, 1.055 * np.power(np.clip(values, 0.0, None), 1.0 / 2.4) - 0.055)
    return np.clip(values * 255.0 + 0.5, 0, 255).astype(np.uint8)


def srgb_to_lab(rgb_u8):
    xyz = srgb_to_linear(rgb_u8) @ RGB_TO_XYZ.T / WHITE_D65
    f = np.where(xyz > LAB_EPSILON, np.cbrt(xyz), (LAB_KAPPA * xyz + 16.0) / 116.0)
    l = 116.0 * f[..., 1] - 16.0
    a = 500.0 * (f[..., 0] - f[..., 1])
    b = 200.0 * (f[..., 1] - f[..., 2])
    return np.stack([l, a, b], axis=-1)


def lab_to_srgb(lab):
    l, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    fy = (l + 16.0) / 116.0
    fx = a / 500.0 + fy
    fz = fy - b / 200.0
    def fin(t):
        t3 = t ** 3
        return np.where(t3 > LAB_EPSILON, t3, (116.0 * t - 16.0) / LAB_KAPPA)
    xyz = np.stack([fin(fx), fin(fy), fin(fz)], axis=-1) * WHITE_D65
    return linear_to_srgb(xyz @ XYZ_TO_RGB.T)


def downscale_max_dim(rgb_u8, max_dim=384):
    image = Image.fromarray(rgb_u8, mode="RGB")
    scale = max_dim / max(image.width, image.height)
    if scale >= 1.0:
        return rgb_u8
    size = (max(1, int(image.width * scale)), max(1, int(image.height * scale)))
    return np.asarray(image.resize(size, Image.BILINEAR), dtype=np.uint8)


def midtone_stats(rgb_u8):
    lab = srgb_to_lab(downscale_max_dim(rgb_u8))
    l, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    mask = (l >= 15.0) & (l <= 85.0)
    if int(mask.sum()) <= 10:
        return 0.0, 0.0, 1.0, 1.0
    av, bv = a[mask], b[mask]
    return float(av.mean()), float(bv.mean()), float(av.std()), float(bv.std())


def reference_grade(colorized_u8, reference_u8, strength):
    """Global Lab a*/b* chroma transfer toward the reference — L untouched,
    ink (L<25) / paper (L>85) ramps attenuate, std ratio clamped [0.6,1.6]."""
    reference = np.asarray(
        Image.fromarray(reference_u8, mode="RGB").resize(
            (colorized_u8.shape[1], colorized_u8.shape[0]), Image.BOX), dtype=np.uint8)
    src_mean_a, src_mean_b, src_std_a, src_std_b = midtone_stats(colorized_u8)
    ref_mean_a, ref_mean_b, ref_std_a, ref_std_b = midtone_stats(reference_u8)
    scale_a = float(np.clip(ref_std_a / max(src_std_a, 1.0), 0.6, 1.6))
    scale_b = float(np.clip(ref_std_b / max(src_std_b, 1.0), 0.6, 1.6))
    lab = srgb_to_lab(colorized_u8).astype(np.float64)
    l, a, b = lab[..., 0], lab[..., 1], lab[..., 2]
    ramp = np.select(
        [l < 12.0, l < 25.0, l <= 82.0, l <= 94.0],
        [0.0, (l - 12.0) / 13.0, 1.0, (94.0 - l) / 12.0],
        default=0.0,
    )
    blend = min(max(strength, 0.0), 1.0) * ramp
    target_a = (a - src_mean_a) * scale_a + ref_mean_a
    target_b = (b - src_mean_b) * scale_b + ref_mean_b
    graded = np.stack([l, a * (1.0 - blend) + target_a * blend, b * (1.0 - blend) + target_b * blend], axis=-1)
    return lab_to_srgb(graded)


def render_pca(features):
    """PCA 256->3 of the 4096 embedding vectors; same-color areas = same entity."""
    flat = features.reshape(GRID * GRID, EMBED_DIM)
    centered = flat - flat.mean(axis=0, keepdims=True)
    covariance = centered.T @ centered / len(flat)
    eigenvalues, eigenvectors = np.linalg.eigh(covariance)
    components = centered @ eigenvectors[:, np.argsort(eigenvalues)[::-1][:3]]
    normalized = np.stack([to_unit8(components[:, i], 1.0, 99.0) for i in range(3)], axis=-1)
    small = (normalized * 255).astype(np.uint8).reshape(GRID, GRID, 3)
    plane = Image.fromarray(small, mode="RGB").resize((WORK, WORK), Image.BILINEAR)
    return png_from_rgb(np.asarray(plane, dtype=np.uint8))


CLUSTER_PALETTE = np.array([
    [230, 57, 70], [29, 114, 216], [42, 157, 74], [255, 183, 3],
    [156, 71, 216], [0, 172, 193], [244, 121, 32], [122, 92, 255],
    [188, 71, 73], [73, 80, 87], [233, 196, 106], [42, 42, 59],
], dtype=np.uint8)


def render_clusters(features, k=8):
    """k-means over the 4096x256 identity grid: discrete regions, colors arbitrary."""
    flat = features.reshape(GRID * GRID, EMBED_DIM).astype(np.float32)
    norms = np.linalg.norm(flat, axis=1, keepdims=True)
    unit = flat / np.maximum(norms, 1e-6)
    rng = np.random.default_rng(0)
    centers = unit[rng.choice(len(unit), size=k, replace=False)].copy()
    for _ in range(12):
        distances = (unit @ centers.T)
        labels = np.argmax(distances, axis=1)
        for c in range(k):
            members = unit[labels == c]
            if len(members):
                centers[c] = members.mean(axis=0)
                centers[c] /= max(np.linalg.norm(centers[c]), 1e-6)
    small = CLUSTER_PALETTE[labels % len(CLUSTER_PALETTE)].reshape(GRID, GRID, 3)
    plane = Image.fromarray(small, mode="RGB").resize((WORK, WORK), Image.NEAREST)
    return png_from_rgb(np.asarray(plane, dtype=np.uint8))


def render_boundary(features):
    """Per-cell embedding gradient magnitude: where SAM says 'region change'."""
    grid = features.reshape(GRID, GRID, EMBED_DIM)
    gx = np.linalg.norm(np.diff(grid, axis=1), axis=-1)
    gy = np.linalg.norm(np.diff(grid, axis=0), axis=-1)
    magnitude = np.zeros((GRID, GRID), dtype=np.float32)
    magnitude[:, 1:] += gx
    magnitude[:, :-1] += gx
    magnitude[1:, :] += gy
    magnitude[:-1, :] += gy
    red = to_unit8(magnitude)
    rgb = np.stack([np.full_like(red, 255.0), (1.0 - red) * 255.0, (1.0 - red) * 255.0], axis=-1)
    plane = Image.fromarray(rgb.astype(np.uint8), mode="RGB").resize((WORK, WORK), Image.BILINEAR)
    return png_from_rgb(np.asarray(plane, dtype=np.uint8))


def render_similarity(features, cx, cy):
    """Cosine similarity of the clicked cell against all 4096 cells."""
    vectors = features.reshape(GRID * GRID, EMBED_DIM)
    query = vectors[cy * GRID + cx]
    norm = np.linalg.norm(query)
    if norm < 1e-6:
        raise ValueError("zero embedding at clicked cell")
    similarity = vectors @ (query / norm)
    return heat_png(to_unit8(similarity.reshape(GRID, GRID), 1.0, 99.0))


def render_channel(features, channel):
    return gray_png(to_unit8(features[:, :, channel]))


def build_images(engine, tensors, image_bytes, hint_bytes, header, reference_bytes=None, ref_strength=1.0):
    pngs = {}
    extra = header.get("extra", {})
    if engine == "mangalight":
        pngs["original"] = png_from_rgb(page_image(image_bytes, WORK, WORK))
        values = np.clip((tensors["rgb_pred"] + 1.0) * 0.5, 0.0, 1.0)
        colorized = (values.reshape(WORK, WORK, 3) * 255).astype(np.uint8)
        pngs["colorized"] = png_from_rgb(colorized)
        pngs["luma"] = png_from_gray((np.clip(tensors["luma"], 0.0, 1.0) * 255).astype(np.uint8))
        pngs["pca"] = render_pca(tensors["level0"])
        pngs["boundary"] = render_boundary(tensors["level0"])
        pngs["clusters"] = render_clusters(tensors["level0"])
    else:
        width = int(extra.get("contentWidth", 0))
        height = int(extra.get("contentHeight", 0))
        if width <= 0 or height <= 0:
            raise ValueError("bundle missing content dimensions")
        pngs["original"] = png_from_rgb(page_image(image_bytes, width, height))
        colorized = (np.clip(tensors["rgb"], 0.0, 1.0).reshape(height, width, 3) * 255).astype(np.uint8)
        pngs["colorized"] = png_from_rgb(colorized)
        pngs["luma"] = png_from_gray(
            np.asarray(Image.open(io.BytesIO(image_bytes)).convert("L").resize((width, height), Image.BOX), dtype=np.uint8)
        )
        if hint_bytes:
            hint = Image.open(io.BytesIO(hint_bytes)).convert("RGBA").resize((width, height), Image.BILINEAR)
            pngs["hints"] = png_from_rgba(np.asarray(hint, dtype=np.uint8))
    if reference_bytes:
        reference = np.asarray(Image.open(io.BytesIO(reference_bytes)).convert("RGB"), dtype=np.uint8)
        pngs["colorized_ref"] = png_from_rgb(reference_grade(colorized, reference, ref_strength))
    # Where the reference grade applies: ink/paper ramp over the colorized luma.
    lab_l = srgb_to_lab(colorized)[..., 0]
    ramp = np.select(
        [lab_l < 12.0, lab_l < 25.0, lab_l <= 82.0, lab_l <= 94.0],
        [0.0, (lab_l - 12.0) / 13.0, 1.0, (94.0 - lab_l) / 12.0],
        default=0.0,
    )
    pngs["refmask"] = heat_png(ramp, colorized.shape[1], colorized.shape[0])
    return pngs


def store_job(engine, image_bytes, hint_bytes, tensors, header, pngs):
    job = uuid.uuid4().hex[:12]
    with JOBS_LOCK:
        JOBS[job] = {
            "engine": engine,
            "image_bytes": image_bytes,
            "hint_bytes": hint_bytes,
            "tensors": tensors,
            "header": header,
            "pngs": pngs,
        }
        while len(JOBS) > MAX_JOBS:
            JOBS.popitem(last=False)
    return job


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):  # keep the console readable
        sys.stderr.write("%s\n" % (fmt % args))

    def _send(self, status, content_type, payload):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

    def _json(self, status, body):
        self._send(status, "application/json", json.dumps(body).encode())

    def _png(self, payload):
        self._send(200, "image/png", payload)

    def _params(self):
        from urllib.parse import parse_qs, urlparse

        query = parse_qs(urlparse(self.path).query)
        return {key: values[0] for key, values in query.items()}

    def _job(self, params):
        with JOBS_LOCK:
            return JOBS.get(params.get("job", ""))

    def do_GET(self):
        path = self.path.split("?")[0]
        params = self._params()
        if path in ("/", "/index.html"):
            with open(os.path.join(HERE, "index.html"), "rb") as handle:
                self._send(200, "text/html; charset=utf-8", handle.read())
        elif path == "/api/status":
            self._json(200, phone_health())
        elif path == "/api/image":
            job = self._job(params)
            if job is None:
                return self._json(404, {"error": "unknown job"})
            payload = job["pngs"].get(params.get("kind", ""))
            if payload is None:
                available = ", ".join(sorted(job["pngs"]))
                return self._json(404, {"error": f"no kind {params.get('kind')} (have: {available})"})
            self._png(payload)
        elif path == "/api/similarity":
            job = self._job(params)
            if job is None:
                return self._json(404, {"error": "unknown job"})
            if job["tensors"] is None or "level0" not in job["tensors"]:
                return self._json(400, {"error": "similarity requires a MangaLight job"})
            try:
                cx = min(GRID - 1, max(0, int(float(params.get("x", 0)) * GRID)))
                cy = min(GRID - 1, max(0, int(float(params.get("y", 0)) * GRID)))
                self._png(render_similarity(job["tensors"]["level0"], cx, cy))
            except Exception as failure:  # noqa: BLE001
                self._json(500, {"error": str(failure)})
        elif path == "/api/channel":
            job = self._job(params)
            if job is None:
                return self._json(404, {"error": "unknown job"})
            if job["tensors"] is None or "level0" not in job["tensors"]:
                return self._json(400, {"error": "channels require a MangaLight job"})
            try:
                self._png(render_channel(job["tensors"]["level0"], int(params.get("c", 0))))
            except Exception as failure:  # noqa: BLE001
                self._json(500, {"error": str(failure)})
        else:
            self._json(404, {"error": f"unknown path {path}"})

    def _preview_ref(self):
        """Live reference grade over a stored job's colorized PNG — no phone involved."""
        params = self._params()
        job = self._job(params)
        if job is None:
            return self._json(404, {"error": "unknown job"})
        length = int(self.headers.get("Content-Length", "0"))
        reference_bytes = self.rfile.read(length)
        if not reference_bytes:
            return self._json(400, {"error": "empty body — send the reference image bytes"})
        try:
            colorized = np.asarray(Image.open(io.BytesIO(job["pngs"]["colorized"])).convert("RGB"), dtype=np.uint8)
            reference = np.asarray(Image.open(io.BytesIO(reference_bytes)).convert("RGB"), dtype=np.uint8)
            strength = float(params.get("strength", 1.0))
            self._png(png_from_rgb(reference_grade(colorized, reference, strength)))
        except KeyError:
            return self._json(400, {"error": "job has no colorized output"})
        except Exception as failure:  # noqa: BLE001
            return self._json(500, {"error": f"reference preview failed: {failure}"})

    def do_POST(self):
        route = self.path.split("?")[0]
        if route == "/api/preview_ref":
            return self._preview_ref()
        if route != "/api/infer":
            return self._json(404, {"error": f"unknown POST path {route}"})
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        params = self._params()
        reference_bytes = None
        ref_strength = 1.0
        try:
            if "application/json" in self.headers.get("Content-Type", ""):
                request = json.loads(body.decode("utf-8"))
                engine = request.get("engine", "mangalight")
                image_bytes = base64.b64decode(request["image_b64"])
                hint_bytes = base64.b64decode(request["hints_b64"]) if request.get("hints_b64") else None
                reference_bytes = base64.b64decode(request["reference_b64"]) if request.get("reference_b64") else None
                ref_strength = float(request.get("ref_strength", 1.0))
            else:
                engine = params.get("engine", "mangalight")
                image_bytes = body
                hint_bytes = None
        except Exception as failure:  # noqa: BLE001
            return self._json(400, {"error": f"bad request body: {failure}"})
        if not image_bytes:
            return self._json(400, {"error": "empty body — send the image bytes"})
        if engine not in ("mangalight", "mcv2"):
            return self._json(400, {"error": f"unknown engine {engine}"})
        started = time.time()
        try:
            raw = phone_infer(image_bytes, engine, hint_bytes)
        except urllib.error.HTTPError as failure:
            detail = failure.read().decode(errors="replace")
            return self._json(failure.code, {"error": f"phone rejected inference: {detail}"})
        except Exception as failure:  # noqa: BLE001
            return self._json(502, {"error": f"cannot reach phone: {failure}"})
        try:
            header, tensors = parse_bundle(raw)
            pngs = build_images(engine, tensors, image_bytes, hint_bytes, header, reference_bytes, ref_strength)
        except Exception as failure:  # noqa: BLE001
            return self._json(502, {"error": f"bad bundle from phone: {failure}"})
        job = store_job(engine, image_bytes, hint_bytes, tensors if engine == "mangalight" else None, header, pngs)
        self._json(
            200,
            {
                "job": job,
                "engine": engine,
                "timings": header.get("timings", {}),
                "extra": header.get("extra", {}),
                "roundtripMs": int((time.time() - started) * 1000),
                "images": {kind: f"/api/image?job={job}&kind={kind}" for kind in pngs},
            },
        )


def main():
    health = phone_health()
    print(f"Phone: {json.dumps(health)[:300]}")
    if health.get("status") != "ready":
        print("WARNING: phone is not ready — start the SAM Viz Probe app service first.")
    server = ThreadingHTTPServer(("127.0.0.1", LISTEN_PORT), Handler)
    print(f"SAM viz UI on http://127.0.0.1:{LISTEN_PORT}  (Ctrl+C to stop)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
