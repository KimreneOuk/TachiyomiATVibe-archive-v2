import os
import sys
import time
import json
import subprocess
import urllib.request
import urllib.error

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

def run_tests():
    server_port = 8555
    server_url = f"http://127.0.0.1:{server_port}"
    app_py = os.path.join(os.path.dirname(__file__), "app.py")

    print(f"Starting server on port {server_port}...", flush=True)
    env = os.environ.copy()
    env["PYTHONUNBUFFERED"] = "1"
    env["PYTHONIOENCODING"] = "utf-8"

    proc = subprocess.Popen([sys.executable, "-u", app_py, "--port", str(server_port)],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env)

    try:
        # Wait for server to start
        ready = False
        for i in range(30):
            if proc.poll() is not None:
                print("Server process exited unexpectedly!", flush=True)
                sys.exit(1)
            try:
                with urllib.request.urlopen(f"{server_url}/api/images", timeout=2) as resp:
                    if resp.status == 200:
                        ready = True
                        break
            except Exception:
                time.sleep(0.5)

        if not ready:
            print("Server failed to start within 15s!", flush=True)
            sys.exit(1)

        print("Server is responding! Starting endpoint tests...\n", flush=True)

        # 1. Test GET /
        with urllib.request.urlopen(f"{server_url}/") as resp:
            assert resp.status == 200, f"GET / status: {resp.status}"
            html = resp.read().decode("utf-8")
            assert "MCV2 Manga Colorization Studio" in html, "HTML title check failed"
            print("[PASS] GET / returned 200 and rendered index.html", flush=True)

        # 2. Test GET /api/images
        with urllib.request.urlopen(f"{server_url}/api/images") as resp:
            assert resp.status == 200
            data = json.loads(resp.read().decode("utf-8"))
            images = data["images"]
            print(f"[PASS] GET /api/images found {len(images)} images in samples directory:", flush=True)
            for img in images:
                print(f"       - {img['name']} ({img['size_bytes']} bytes)", flush=True)
            assert len(images) >= 4, "Expected at least 4 sample images"

        target_img = next(i for i in images if "sample_1" in i["name"])["path"]
        ref_img = next(i for i in images if "104" in i["name"])["path"]

        # Helper to make POST requests
        def post_json(path, payload):
            req = urllib.request.Request(
                f"{server_url}{path}",
                data=json.dumps(payload).encode("utf-8"),
                headers={"Content-Type": "application/json"}
            )
            with urllib.request.urlopen(req) as r:
                return json.loads(r.read().decode("utf-8"))

        def poll_job(job_id):
            while True:
                with urllib.request.urlopen(f"{server_url}/api/job?id={job_id}") as r:
                    d = json.loads(r.read().decode("utf-8"))
                    if d["status"] == "completed":
                        return d["result"]
                    elif d["status"] == "failed":
                        raise RuntimeError(f"Job failed: {d.get('error')}")
                    time.sleep(0.3)

        # 3. Test No-Hint Inference
        print("\n--- Testing No-Hint Baseline Inference ---", flush=True)
        t0 = time.time()
        res = post_json("/api/infer", {"target": target_img, "mode": "none"})
        assert res["status"] in ("pending", "completed")
        job_id = res["job_id"]
        result_no_hint = poll_job(job_id) if not res.get("cached") else res["result"]
        t_no_hint_total = time.time() - t0
        timing_no_hint = result_no_hint["timing"]
        mean_no_hint = result_no_hint["stats"]["mean_rgb"]
        print(f"[PASS] No-Hint finished in {t_no_hint_total:.2f}s (Inference: {timing_no_hint['inference_s']}s)", flush=True)
        print(f"       Mean RGB: {mean_no_hint}", flush=True)
        assert result_no_hint["image"].startswith("data:image/png;base64,"), "Output image missing"

        # 4. Test Cache Hit on No-Hint
        print("\n--- Testing Cache Hit ---", flush=True)
        t0 = time.time()
        res_cache = post_json("/api/infer", {"target": target_img, "mode": "none"})
        t_cache = time.time() - t0
        assert res_cache.get("cached") is True, f"Expected cache hit, got {res_cache}"
        print(f"[PASS] Cache hit confirmed! Response time: {t_cache*1000:.1f}ms (instant return)", flush=True)

        # 5. Test With-Reference Inference
        print("\n--- Testing With-Reference Inference ---", flush=True)
        t0 = time.time()
        res = post_json("/api/infer", {
            "target": target_img,
            "mode": "reference",
            "reference": ref_img,
            "flatten_strength": 21
        })
        job_id = res["job_id"]
        result_ref = poll_job(job_id) if not res.get("cached") else res["result"]
        t_ref_total = time.time() - t0
        timing_ref = result_ref["timing"]
        mean_ref = result_ref["stats"]["mean_rgb"]
        print(f"[PASS] With-Reference finished in {t_ref_total:.2f}s (Inference: {timing_ref['inference_s']}s)", flush=True)
        print(f"       Mean RGB: {mean_ref}", flush=True)
        assert result_ref["image"].startswith("data:image/png;base64,"), "Output image missing"
        assert result_ref["hint_image"].startswith("data:image/png;base64,"), "Hint visualization missing"

        # Verify palette shift
        rgb_diff = [abs(r - n) for r, n in zip(mean_ref, mean_no_hint)]
        print(f"       Palette difference (|ref - no_hint|): {[round(d, 4) for d in rgb_diff]}", flush=True)
        assert any(d > 0.03 for d in rgb_diff), "Expected visible palette shift from reference guidance"

        # 6. Test Manual Hints Inference
        print("\n--- Testing Manual Hints Inference ---", flush=True)
        strokes = [
            {"x": 0.25, "y": 0.25, "radius": 0.08, "rgb": [255, 50, 50]},
            {"x": 0.75, "y": 0.75, "radius": 0.08, "rgb": [50, 50, 255]}
        ]
        t0 = time.time()
        res = post_json("/api/infer", {
            "target": target_img,
            "mode": "manual",
            "manual_hint": strokes
        })
        job_id = res["job_id"]
        result_manual = poll_job(job_id) if not res.get("cached") else res["result"]
        t_manual_total = time.time() - t0
        timing_manual = result_manual["timing"]
        mean_manual = result_manual["stats"]["mean_rgb"]
        print(f"[PASS] Manual hints finished in {t_manual_total:.2f}s (Inference: {timing_manual['inference_s']}s)", flush=True)
        print(f"       Mean RGB: {mean_manual}", flush=True)
        assert result_manual["hint_image"].startswith("data:image/png;base64,"), "Manual hint visualization missing"

        print("\n=======================================================", flush=True)
        print("ALL END-TO-END VALIDATION TESTS PASSED SUCCESSFULLY!", flush=True)
        print("=======================================================", flush=True)

    finally:
        proc.terminate()
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()
        print("Server process stopped.", flush=True)

if __name__ == "__main__":
    run_tests()
