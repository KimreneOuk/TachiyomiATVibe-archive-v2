"""
launcher.py — Entry point para ejecutable .exe de Manga Studio Pro V3
"""
import multiprocessing

if __name__ == "__main__":
    multiprocessing.freeze_support()

import os
import sys
import socket
import subprocess
import time
import traceback
import webbrowser
import threading
from pathlib import Path

# Determinar directorio raíz
if getattr(sys, 'frozen', False):
    APP_ROOT = Path(sys.executable).parent.resolve()
    BUNDLE_DIR = Path(getattr(sys, '_MEIPASS', APP_ROOT))
else:
    APP_ROOT = Path(__file__).parent.resolve()
    BUNDLE_DIR = APP_ROOT

if str(APP_ROOT) not in sys.path:
    sys.path.insert(0, str(APP_ROOT))
if str(BUNDLE_DIR) not in sys.path:
    sys.path.insert(0, str(BUNDLE_DIR))

import config
import server
import uvicorn


def is_port_in_use(port: int, host: str = "127.0.0.1") -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1.0)
        return s.connect_ex((host, port)) == 0


def ensure_comfy_running():
    comfy_port = 8188
    if is_port_in_use(comfy_port):
        print(f"[Launcher] ComfyUI is already running on port {comfy_port}.")
        return

    print(f"[Launcher] Starting ComfyUI engine in background...")
    comfy_dir = APP_ROOT / "ComfyUI_windows_portable"
    python_exe = comfy_dir / "python_embeded" / "python.exe"
    comfy_main = comfy_dir / "ComfyUI" / "main.py"

    if python_exe.exists() and comfy_main.exists():
        try:
            subprocess.Popen(
                [str(python_exe), str(comfy_main), "--port", str(comfy_port), "--listen", "127.0.0.1"],
                cwd=str(comfy_dir / "ComfyUI"),
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
            )
            print("[Launcher] ComfyUI started successfully.")
        except Exception as e:
            print(f"[Launcher] Could not start ComfyUI automatically: {e}")
    else:
        print("[Launcher] ComfyUI portable not found locally. Please ensure ComfyUI is running on 127.0.0.1:8188.")


def open_browser():
    import comfy_api
    url = f"http://127.0.0.1:{config.GRADIO_PORT}"
    print(f"[Launcher] Waiting for ComfyUI engine to be ready before opening browser...")
    for _ in range(120):
        if comfy_api.check_server_status():
            print(f"[Launcher] ✅ ComfyUI is ready! Opening browser at {url}")
            time.sleep(0.5)
            webbrowser.open(url)
            return
        time.sleep(1)
    webbrowser.open(url)


def main():
    print("=========================================================")
    print("   MANGA COLORING TOOL 2 — EXECUTABLE LAUNCHER          ")
    print("=========================================================")
    
    try:
        config.ensure_directories_exist()
        ensure_comfy_running()

        threading.Thread(target=open_browser, daemon=True).start()

        print(f"[Launcher] FastAPI server listening at http://127.0.0.1:{config.GRADIO_PORT}")
        uvicorn.run(server.app, host="127.0.0.1", port=config.GRADIO_PORT, log_level="info")
    except Exception as exc:
        print("\n❌ Unexpected error during execution of Manga Coloring Tool 2:")
        traceback.print_exc()
        input("\nPress ENTER to exit...")


if __name__ == "__main__":
    main()
