import argparse
import base64
import hashlib
import io
import json
import mimetypes
import os
import queue
import sys
import threading
import time
from http.server import HTTPServer, ThreadingHTTPServer, SimpleHTTPRequestHandler
from urllib.parse import urlparse, parse_qs, unquote

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

import numpy as np
import onnxruntime as ort
from PIL import Image
import cv2

DEFAULT_MODEL_PATH = r"C:\Users\ADMIN\.traycer\epics\8a8756ac-c25a-4455-8a5d-da6fb2b75972\scratch\mcv2_576x832\mcv2-fp32-b5-832x576.onnx"
DEFAULT_SAMPLES_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "samples")

# Global Inference Engine
class ColorizeEngine:
    def __init__(self, model_path: str):
        self.model_path = model_path
        print(f"Loading ONNX model from: {model_path}", flush=True)
        t0 = time.perf_counter()
        so = ort.SessionOptions()
        so.intra_op_num_threads = 2
        so.inter_op_num_threads = 1
        self.session = ort.InferenceSession(model_path, so, providers=["CPUExecutionProvider"])
        t1 = time.perf_counter()
        print(f"ONNX model loaded in {t1 - t0:.2f}s", flush=True)

    def process(self, target_path: str, mode: str, reference_path: str = None, 
                flatten_strength: int = 21, manual_hint: list = None):
        t_start = time.perf_counter()
        if not os.path.exists(target_path):
            raise FileNotFoundError(f"Target image not found: {target_path}")

        # 1. Target preprocessing & letterboxing
        target_img = Image.open(target_path).convert("L")
        orig_w, orig_h = target_img.size

        scale = min(576.0 / orig_w, 832.0 / orig_h)
        cw = max(1, round(orig_w * scale))
        ch = max(1, round(orig_h * scale))
        top = (832 - ch) // 2
        left = (576 - cw) // 2

        resized_target = target_img.resize((cw, ch), Image.Resampling.BILINEAR)
        target_arr = np.array(resized_target, dtype=np.float32) / 255.0

        inp = np.ones((1, 5, 832, 576), dtype=np.float32)
        inp[0, 0, top:top+ch, left:left+cw] = target_arr
        inp[0, 1:5, :, :] = 0.0

        hint_vis_pil = None

        # 2. Hint channels
        if mode == "reference":
            if not reference_path or not os.path.exists(reference_path):
                raise FileNotFoundError(f"Reference image not found: {reference_path}")
            ref_img = Image.open(reference_path).convert("RGB")
            ref_cv = cv2.cvtColor(np.array(ref_img), cv2.COLOR_RGB2BGR)

            k = int(flatten_strength)
            if k < 1:
                k = 1
            if k % 2 == 0:
                k += 1

            if k > 1:
                flattened_cv = cv2.medianBlur(ref_cv, k)
                flattened_cv = cv2.GaussianBlur(flattened_cv, (k, k), 0)
            else:
                flattened_cv = ref_cv

            flattened_rgb = cv2.cvtColor(flattened_cv, cv2.COLOR_BGR2RGB)
            flattened_pil = Image.fromarray(flattened_rgb)

            ref_resized = flattened_pil.resize((cw, ch), Image.Resampling.BILINEAR)
            ref_arr = np.array(ref_resized, dtype=np.float32) / 255.0

            hint_encoded = (ref_arr - 0.5) / 0.5
            inp[0, 1, top:top+ch, left:left+cw] = hint_encoded[:, :, 0]
            inp[0, 2, top:top+ch, left:left+cw] = hint_encoded[:, :, 1]
            inp[0, 3, top:top+ch, left:left+cw] = hint_encoded[:, :, 2]
            inp[0, 4, top:top+ch, left:left+cw] = 1.0

            # Hint visualization: flattened reference resized to original target aspect ratio
            hint_vis_pil = ref_resized.resize((orig_w, orig_h), Image.Resampling.BILINEAR)

        elif mode == "manual":
            hint_rgb = np.zeros((832, 576, 3), dtype=np.float32)
            hint_mask = np.zeros((832, 576), dtype=np.float32)

            strokes = manual_hint or []
            for s in strokes:
                cx = int(round(left + float(s.get("x", 0.5)) * cw))
                cy = int(round(top + float(s.get("y", 0.5)) * ch))
                r = max(1, int(round(float(s.get("radius", 0.05)) * cw)))
                raw_rgb = s.get("rgb", [255, 0, 0])
                if any(v > 1.0 for v in raw_rgb):
                    color = [c / 255.0 for c in raw_rgb]
                else:
                    color = [float(c) for c in raw_rgb]
                cv2.circle(hint_rgb, (cx, cy), r, color, -1)
                cv2.circle(hint_mask, (cx, cy), r, 1.0, -1)

            mask_bool = hint_mask > 0.5
            hint_encoded = np.zeros((832, 576, 3), dtype=np.float32)
            hint_encoded[mask_bool] = (hint_rgb[mask_bool] - 0.5) / 0.5

            inp[0, 1, :, :] = hint_encoded[:, :, 0]
            inp[0, 2, :, :] = hint_encoded[:, :, 1]
            inp[0, 3, :, :] = hint_encoded[:, :, 2]
            inp[0, 4, :, :] = hint_mask

            # Hint visualization: overlay hints onto target lineart
            vis_canvas = np.repeat(target_arr[:, :, None], 3, axis=2)
            local_mask = mask_bool[top:top+ch, left:left+cw]
            local_rgb = hint_rgb[top:top+ch, left:left+cw]
            vis_canvas[local_mask] = local_rgb[local_mask]
            hint_vis_pil = Image.fromarray((np.clip(vis_canvas, 0, 1) * 255).astype(np.uint8)).resize(
                (orig_w, orig_h), Image.Resampling.BILINEAR
            )

        # 3. Model Inference
        t_infer_start = time.perf_counter()
        out_raw = self.session.run(["rgb"], {"input": inp})[0]
        t_infer_end = time.perf_counter()

        # 4. Post-processing: crop and resize to original page size
        cropped = out_raw[0, top:top+ch, left:left+cw, :]
        cropped_clipped = np.clip(cropped, 0.0, 1.0)
        mean_rgb = [float(np.mean(cropped_clipped[:, :, c])) for c in range(3)]
        out_uint8 = (cropped_clipped * 255.0).astype(np.uint8)
        out_pil = Image.fromarray(out_uint8).resize((orig_w, orig_h), Image.Resampling.BILINEAR)

        t_end = time.perf_counter()

        # Helper to encode PIL to base64 data URI
        def pil_to_data_uri(img, fmt="PNG"):
            if img is None:
                return None
            buf = io.BytesIO()
            img.save(buf, format=fmt)
            b64 = base64.b64encode(buf.getvalue()).decode("ascii")
            return f"data:image/{fmt.lower()};base64,{b64}"

        return {
            "image": pil_to_data_uri(out_pil, "PNG"),
            "hint_image": pil_to_data_uri(hint_vis_pil, "PNG"),
            "timing": {
                "inference_s": round(t_infer_end - t_infer_start, 3),
                "total_s": round(t_end - t_start, 3),
                "cached": False,
            },
            "stats": {
                "mean_rgb": [round(c, 4) for c in mean_rgb],
                "target_size": [orig_w, orig_h],
                "content_box": [cw, ch, left, top],
            }
        }


# Global Job Queue & Cache
CACHE = {}
CACHE_LOCK = threading.Lock()
JOBS = {}
JOBS_LOCK = threading.Lock()
JOB_QUEUE = queue.Queue()
ENGINE = None

def compute_cache_key(target: str, mode: str, reference: str = None, 
                      flatten_strength: int = 21, manual_hint: list = None):
    t_norm = os.path.normcase(os.path.abspath(target)) if target else ""
    r_norm = os.path.normcase(os.path.abspath(reference)) if (mode == "reference" and reference) else ""
    f_str = int(flatten_strength) if mode == "reference" else 0
    if mode == "manual" and manual_hint:
        m_str = json.dumps(manual_hint, sort_keys=True)
        m_digest = hashlib.sha256(m_str.encode("utf-8")).hexdigest()
    else:
        m_digest = ""
    return (t_norm, mode, r_norm, f_str, m_digest)

def worker_loop():
    while True:
        job_id, params = JOB_QUEUE.get()
        try:
            with JOBS_LOCK:
                JOBS[job_id]["status"] = "running"

            res = ENGINE.process(
                target_path=params["target"],
                mode=params["mode"],
                reference_path=params.get("reference"),
                flatten_strength=params.get("flatten_strength", 21),
                manual_hint=params.get("manual_hint"),
            )

            # Store in cache
            cache_key = params["cache_key"]
            with CACHE_LOCK:
                CACHE[cache_key] = res

            with JOBS_LOCK:
                JOBS[job_id]["status"] = "completed"
                JOBS[job_id]["result"] = res

        except Exception as e:
            import traceback
            traceback.print_exc()
            with JOBS_LOCK:
                JOBS[job_id]["status"] = "failed"
                JOBS[job_id]["error"] = str(e)
        finally:
            JOB_QUEUE.task_done()


class ColorizeHandler(SimpleHTTPRequestHandler):
    def end_headers(self):
        # Enable CORS and disable caching for API
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(204)
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        query = parse_qs(parsed.query)

        if path == "/" or path == "/index.html":
            index_path = os.path.join(os.path.dirname(__file__), "index.html")
            if os.path.exists(index_path):
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.end_headers()
                with open(index_path, "rb") as f:
                    self.wfile.write(f.read())
                return
            else:
                self.send_error(404, "index.html not found")
                return

        elif path == "/api/images":
            req_dir = query.get("dir", [None])[0]
            if not req_dir or not req_dir.strip():
                req_dir = DEFAULT_SAMPLES_DIR
            req_dir = os.path.abspath(req_dir)

            if not os.path.isdir(req_dir):
                self.send_json({"error": f"Directory not found: {req_dir}"}, 404)
                return

            valid_exts = {".png", ".jpg", ".jpeg", ".webp", ".bmp"}
            images = []
            try:
                for entry in sorted(os.scandir(req_dir), key=lambda e: e.name.lower()):
                    if entry.is_file() and os.path.splitext(entry.name)[1].lower() in valid_exts:
                        images.append({
                            "name": entry.name,
                            "path": entry.path,
                            "size_bytes": entry.stat().st_size,
                        })
                self.send_json({
                    "directory": req_dir,
                    "images": images,
                    "default_samples_dir": DEFAULT_SAMPLES_DIR
                })
            except Exception as e:
                self.send_json({"error": str(e)}, 500)
            return

        elif path == "/api/image":
            img_path = query.get("path", [None])[0]
            if not img_path or not os.path.exists(img_path):
                self.send_error(404, "Image not found")
                return
            
            # Optional thumbnail parameter
            thumb_max = query.get("thumb", [None])[0]
            try:
                if thumb_max:
                    max_dim = int(thumb_max)
                    im = Image.open(img_path)
                    im.thumbnail((max_dim, max_dim))
                    buf = io.BytesIO()
                    im.save(buf, format="JPEG", quality=85)
                    data = buf.getvalue()
                    mime = "image/jpeg"
                else:
                    mime, _ = mimetypes.guess_type(img_path)
                    if not mime:
                        mime = "application/octet-stream"
                    with open(img_path, "rb") as f:
                        data = f.read()

                self.send_response(200)
                self.send_header("Content-Type", mime)
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Cache-Control", "public, max-age=3600")
                self.end_headers()
                self.wfile.write(data)
            except Exception as e:
                self.send_error(500, str(e))
            return

        elif path == "/api/job":
            job_id = query.get("id", [None])[0]
            if not job_id:
                self.send_json({"error": "Missing job id"}, 400)
                return
            with JOBS_LOCK:
                job = JOBS.get(job_id)
                if not job:
                    self.send_json({"error": "Job not found"}, 404)
                    return
                # Return snapshot
                self.send_json({
                    "job_id": job_id,
                    "status": job["status"],
                    "result": job.get("result"),
                    "error": job.get("error"),
                })
            return

        elif path == "/api/cache_status":
            with CACHE_LOCK:
                count = len(CACHE)
            with JOBS_LOCK:
                queue_len = JOB_QUEUE.qsize()
            self.send_json({"cache_entries": count, "queue_length": queue_len})
            return

        # Fallback to standard static file serving if needed
        super().do_GET()

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/api/infer":
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length)
            try:
                data = json.loads(body.decode("utf-8"))
            except Exception as e:
                self.send_json({"error": f"Invalid JSON: {e}"}, 400)
                return

            target = data.get("target")
            mode = data.get("mode", "none")
            reference = data.get("reference")
            flatten_strength = int(data.get("flatten_strength", 21))
            manual_hint = data.get("manual_hint", [])

            if not target or not os.path.exists(target):
                self.send_json({"error": f"Target image does not exist: {target}"}, 400)
                return
            if mode == "reference" and (not reference or not os.path.exists(reference)):
                self.send_json({"error": f"Reference image does not exist: {reference}"}, 400)
                return

            cache_key = compute_cache_key(target, mode, reference, flatten_strength, manual_hint)

            # Check cache first
            with CACHE_LOCK:
                if cache_key in CACHE:
                    cached_result = dict(CACHE[cache_key])
                    # Mark cached timing
                    cached_result["timing"] = dict(cached_result.get("timing", {}))
                    cached_result["timing"]["cached"] = True
                    self.send_json({
                        "job_id": "cached_" + hashlib.md5(str(cache_key).encode()).hexdigest()[:8],
                        "status": "completed",
                        "cached": True,
                        "result": cached_result
                    })
                    return

            # Submit new job
            job_id = f"job_{int(time.time()*1000)}_{os.urandom(4).hex()}"
            job_entry = {
                "id": job_id,
                "status": "pending",
                "result": None,
                "error": None,
                "params": {
                    "target": target,
                    "mode": mode,
                    "reference": reference,
                    "flatten_strength": flatten_strength,
                    "manual_hint": manual_hint,
                    "cache_key": cache_key,
                }
            }
            with JOBS_LOCK:
                JOBS[job_id] = job_entry

            JOB_QUEUE.put((job_id, job_entry["params"]))

            self.send_json({
                "job_id": job_id,
                "status": "pending",
                "cached": False
            })
            return

        elif path == "/api/clear_cache":
            with CACHE_LOCK:
                count = len(CACHE)
                CACHE.clear()
            self.send_json({"message": f"Cleared {count} cache entries"})
            return

        self.send_error(404, "Endpoint not found")

    def send_json(self, data: dict, code: int = 200):
        body = json.dumps(data).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        # Concise logging
        sys.stderr.write(f"[{self.log_date_time_string()}] {format % args}\n")


def main():
    parser = argparse.ArgumentParser(description="MCV2 Manga Colorization Demo Server")
    parser.add_argument("--port", type=int, default=8080, help="Port to listen on (default 8080)")
    parser.add_argument("--host", type=str, default="127.0.0.1", help="Host interface (default 127.0.0.1)")
    parser.add_argument("--model", type=str, default=DEFAULT_MODEL_PATH, help="Path to ONNX model")
    args = parser.parse_args()

    global ENGINE
    ENGINE = ColorizeEngine(args.model)

    # Start background inference worker thread
    worker = threading.Thread(target=worker_loop, daemon=True)
    worker.start()
    print("Inference worker thread started.", flush=True)

    # Start HTTP server
    port = args.port
    server = None
    for attempt_port in range(port, port + 10):
        try:
            server = ThreadingHTTPServer((args.host, attempt_port), ColorizeHandler)
            port = attempt_port
            break
        except OSError:
            print(f"Port {attempt_port} occupied, trying next...", flush=True)

    if not server:
        print(f"Failed to bind to any port in range {args.port}..{args.port+9}", flush=True)
        sys.exit(1)

    print("\n" + "="*66, flush=True)
    print(f"[MCV2] Colorization Demo Web App Running at:", flush=True)
    print(f"   http://{args.host}:{port}/", flush=True)
    print("="*66 + "\n", flush=True)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down server...", flush=True)
        server.server_close()

if __name__ == "__main__":
    main()
