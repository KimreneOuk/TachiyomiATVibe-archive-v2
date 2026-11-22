"""
server.py — Backend FastAPI para Manga Studio Pro V3
Servidor de API REST y SSE para controlar la generación de imágenes,
gestión de biblioteca, lector de manga, esteganografía y respaldos.
"""
import io
import json
import os
import sys
import shutil
import subprocess
import tempfile
import urllib.parse
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Optional, List, Dict, Any, Generator, Tuple, Union

# Garantizar que el directorio del script esté siempre en sys.path
_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.insert(0, _HERE)

from fastapi import FastAPI, HTTPException, UploadFile, File, Form, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse, FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from PIL import Image
import numpy as np

import cbz_converter
import comfy_api
import generator
import image_utils
import library_manager
import manga_reader
import steganography
from config import (
    COMFYUI_SERVER_ADDRESS, GRADIO_PORT,
    OUTPUT_BASE_DIR, LIBRARY_DIR, BACKUP_DIR, TEMP_INPUTS_DIR,
    ensure_directories_exist,
)


import threading
import time
import webbrowser

def open_browser_when_comfy_ready():
    """Espera a que el servidor ComfyUI responda antes de abrir la interfaz en el navegador."""
    url = f"http://127.0.0.1:{GRADIO_PORT}"
    print(f"[Server] Esperando a que el motor ComfyUI ({COMFYUI_SERVER_ADDRESS}) esté listo...")
    
    for _ in range(120):
        if comfy_api.check_server_status():
            print(f"[Server] ✅ ComfyUI detectado e iniciado correctamente. Abriendo navegador en {url}")
            time.sleep(0.5)
            webbrowser.open(url)
            return
        time.sleep(1)
        
    print(f"[Server] ⚠️ Tiempo de espera agotado. Abriendo interfaz en {url}")
    webbrowser.open(url)


@asynccontextmanager
async def lifespan(app_instance: FastAPI):
    ensure_directories_exist()
    threading.Thread(target=open_browser_when_comfy_ready, daemon=True).start()
    yield


app = FastAPI(title="Manga Coloring Tool 2 API", version="2.0.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://127.0.0.1:7860", "http://localhost:7860",
        "http://127.0.0.1:5173", "http://localhost:5173",
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ---------------------------------------------------------------------------
# API: Estado del Servidor
# ---------------------------------------------------------------------------

@app.get("/api/status")
def get_server_status():
    ok = comfy_api.check_server_status()
    return {
        "comfy_connected": ok,
        "comfy_address": COMFYUI_SERVER_ADDRESS,
    }


# ---------------------------------------------------------------------------
# API: Extractor de Paleta de Color
# ---------------------------------------------------------------------------

@app.post("/api/extract-palette")
async def extract_palette(file: Optional[UploadFile] = File(None), image_path: Optional[str] = Form(None)):
    """Extrae los 6 colores dominantes de una imagen de referencia en formato HEX."""
    temp_path = None
    try:
        if file:
            with tempfile.NamedTemporaryFile(delete=False, suffix=".png") as tmp:
                shutil.copyfileobj(file.file, tmp)
                temp_path = tmp.name
            path_to_open = temp_path
        elif image_path and os.path.exists(image_path):
            path_to_open = image_path
        else:
            raise HTTPException(status_code=400, detail="Se requiere imagen o ruta válida.")

        with Image.open(path_to_open) as img:
            img = img.convert("RGB").resize((150, 150))
            # Quantization a 6 colores dominantes
            quantized = img.quantize(colors=6, method=Image.Quantize.MEDIANCUT)
            palette = quantized.getpalette()[:18]
            
            hex_colors = []
            for i in range(0, len(palette), 3):
                r, g, b = palette[i], palette[i+1], palette[i+2]
                hex_colors.append(f"#{r:02x}{g:02x}{b:02x}")

        return {"colors": hex_colors}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))
    finally:
        if temp_path and os.path.exists(temp_path):
            try:
                os.remove(temp_path)
            except Exception:
                pass


# ---------------------------------------------------------------------------
# API: Generación de Imágenes (Streaming SSE)
# ---------------------------------------------------------------------------

@app.post("/api/generate")
async def generate_batch_endpoint(
    files: Optional[List[UploadFile]] = File(None),
    ref_file: Optional[UploadFile] = File(None),
    ref_path: Optional[str] = Form(None),
    prompt: str = Form("colour"),
    use_ref: bool = Form(True),
    folder_name: str = Form("manga_chapter_01"),
    steps: int = Form(4),
    auto_ref: bool = Form(False),
    cfg: float = Form(1.0),
    megapixels: float = Form(1.0),
    watermark: bool = Form(False),
    use_folder_mode: bool = Form(False),
    folder_path_input: str = Form(""),
    preset_name: str = Form(""),
    output_format: str = Form("png"),
    unet_name: str = Form(""),
    encoder_name: str = Form(""),
    panel_mode: bool = Form(False),
    panel_upscale: bool = Form(False),
):
    """Ejecuta la generación en lote transmitiendo eventos Server-Sent Events (SSE)."""
    
    # 1 — Guardar archivos subidos en directorio temporal persistente de sesión
    TEMP_INPUTS_DIR.mkdir(parents=True, exist_ok=True)
    temp_input_files = []
    temp_ref_path = None

    if ref_file:
        safe_ref_name = f"ref_{image_utils.sanitize_filename(ref_file.filename or 'ref.png')}.png"
        ref_dest = TEMP_INPUTS_DIR / safe_ref_name
        with open(ref_dest, "wb") as tmp:
            shutil.copyfileobj(ref_file.file, tmp)
        temp_ref_path = str(ref_dest)
    elif ref_path and os.path.exists(ref_path):
        temp_ref_path = ref_path

    if not use_folder_mode and files:
        for f in files:
            safe_stem = image_utils.sanitize_filename(f.filename or "input.png")
            ext = Path(f.filename or "input.png").suffix or ".png"
            target_path = TEMP_INPUTS_DIR / f"{safe_stem}{ext}"
            if target_path.exists():
                target_path = image_utils.get_versioned_path(target_path)
            with open(target_path, "wb") as tmp:
                shutil.copyfileobj(f.file, tmp)
            
            class UploadedFileRef:
                def __init__(self, path, orig_name):
                    self.name = str(path)
                    self.orig_name = orig_name
            temp_input_files.append(UploadedFileRef(target_path, f.filename))

    def sse_event_stream():
        try:
            gen = generator.batch_process_generator(
                list_of_images=temp_input_files if not use_folder_mode else None,
                ref_image_input=temp_ref_path,
                prompt_text=prompt,
                use_ref=use_ref,
                folder_name_input=folder_name,
                steps=steps,
                auto_ref_switch=auto_ref,
                cfg=cfg,
                megapixels=megapixels,
                embed_watermark=watermark,
                use_folder_mode=use_folder_mode,
                folder_path_input=folder_path_input,
                preset_name=preset_name,
                output_format=output_format,
                unet_name=unet_name or None,
                encoder_name=encoder_name or None,
                panel_mode=panel_mode,
                panel_upscale=panel_upscale,
            )

            for gallery_list, status_msg, last_saved, stats_msg in gen:
                # Convertir gallery_list a rutas relativas/URLs para el cliente web
                gallery_payload = []
                for item in gallery_list:
                    if isinstance(item, dict):
                        out_p = item.get("output", "")
                        orig_p = item.get("original", "")
                        name = item.get("name", "")
                    else:
                        pil_or_path, name = item
                        out_p = pil_or_path if isinstance(pil_or_path, str) else (last_saved or "")
                        orig_p = ""
                    
                    img_url = f"/api/media?path={urllib.parse.quote(out_p)}" if out_p else ""
                    orig_url = f"/api/media?path={urllib.parse.quote(orig_p)}" if orig_p else ""
                    gallery_payload.append({
                        "url": img_url,
                        "name": name,
                        "full_path": out_p,
                        "original_url": orig_url,
                    })

                stats_data = stats_msg if isinstance(stats_msg, dict) else {"text": str(stats_msg)}
                data = {
                    "status": status_msg,
                    "stats": stats_data,
                    "last_saved": last_saved,
                    "gallery": gallery_payload,
                }
                yield f"data: {json.dumps(data, ensure_ascii=False)}\n\n"
        except Exception as exc:
            err_data = {"status": f"❌ Error: {exc}", "stats": {"stage": "Error"}}
            yield f"data: {json.dumps(err_data, ensure_ascii=False)}\n\n"

    return StreamingResponse(sse_event_stream(), media_type="text/event-stream")


@app.post("/api/cancel")
def cancel_generation():
    msg = generator.request_cancel()
    return {"message": msg}


@app.get("/api/pick_folder")
def pick_folder():
    """Abre el explorador de archivos nativo del SO para seleccionar una carpeta y devuelve la ruta absoluta."""
    try:
        if sys.platform == "win32":
            ps_cmd = (
                "Add-Type -AssemblyName System.Windows.Forms; "
                "$f = New-Object System.Windows.Forms.FolderBrowserDialog; "
                "$f.Description = 'Select Manga Folder'; "
                "$f.ShowNewFolderButton = $true; "
                "if ($f.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output $f.SelectedPath }"
            )
            res = subprocess.check_output(["powershell", "-NoProfile", "-Command", ps_cmd], text=True, timeout=120)
            selected = res.strip()
            return {"folder_path": selected}
        elif sys.platform == "darwin":
            script = 'osascript -e "posix path of (choose folder with prompt \"Select Manga Folder\")"'
            res = subprocess.check_output(script, shell=True, text=True, timeout=120)
            return {"folder_path": res.strip()}
        else:
            try:
                res = subprocess.check_output(["zenity", "--file-selection", "--directory"], text=True, timeout=120)
                return {"folder_path": res.strip()}
            except Exception:
                res = subprocess.check_output(["kdialog", "--getexistingdirectory"], text=True, timeout=120)
                return {"folder_path": res.strip()}
    except Exception as e:
        return {"folder_path": "", "error": str(e)}


@app.get("/api/scan_folder")
def scan_folder(folder_path: str):
    p = Path(folder_path.strip())
    if not p.is_dir():
        return {"images": [], "total": 0, "error": f"La carpeta '{folder_path}' no existe."}
    images = image_utils.collect_images_from_folder(p)
    result = [
        {
            "name": img.name,
            "full_path": str(img),
            "url": f"/api/media?path={urllib.parse.quote(str(img))}"
        }
        for img in images
    ]
    return {"images": result, "total": len(result)}





# ---------------------------------------------------------------------------
# API: Historial y Favoritos
# ---------------------------------------------------------------------------

@app.get("/api/history")
def get_history():
    return {
        "history": library_manager.load_history(),
        "favorites": library_manager.load_favorites(),
        "library_gallery": library_manager.get_library_gallery(),
    }


@app.post("/api/history/toggle-fav")
def toggle_favorite(session_id: str = Form(...)):
    is_fav, msg = library_manager.toggle_favorite(session_id)
    return {"is_favorite": is_fav, "message": msg}


@app.post("/api/history/export-cbz")
def export_cbz_from_history(session_id: str = Form(...)):
    history = library_manager.load_history()
    entry = next((h for h in history if h.get("id") == session_id), None)
    if not entry:
        raise HTTPException(status_code=404, detail="Sesión no encontrada.")
    output_folder = entry.get("output_folder", "")
    session_name = entry.get("session_name", "")

    if not os.path.isdir(output_folder):
        # Fallback 1: Buscar en biblioteca (library/)
        lib_copy1 = LIBRARY_DIR / session_name
        lib_copy2 = LIBRARY_DIR / f"{session_name}_{session_id}"
        out_copy = OUTPUT_BASE_DIR / session_name
        if lib_copy1.is_dir():
            output_folder = str(lib_copy1)
        elif lib_copy2.is_dir():
            output_folder = str(lib_copy2)
        elif out_copy.is_dir():
            output_folder = str(out_copy)
        else:
            raise HTTPException(status_code=404, detail=f"Carpeta '{session_name}' no encontrada localmente.")

    try:
        cbz_path = cbz_converter.create_cbz(output_folder)
        return {"message": f"CBZ creado exitosamente: {cbz_path}", "cbz_path": cbz_path}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))


# ---------------------------------------------------------------------------
# API: Lector de Manga
# ---------------------------------------------------------------------------

@app.post("/api/reader/load")
async def load_reader(
    mode: str = Form(...),
    folder_path: Optional[str] = Form(None),
    cbz_file: Optional[UploadFile] = File(None)
):
    """Carga carpetas o archivos .cbz para el lector de manga."""
    reader = manga_reader.reader
    if mode == "folder":
        if not folder_path:
            raise HTTPException(status_code=400, detail="Ruta de carpeta requerida.")
        msg = reader.load_from_folder(folder_path)
    elif mode == "cbz":
        if not cbz_file:
            raise HTTPException(status_code=400, detail="Archivo .cbz requerido.")
        temp_cbz = tempfile.NamedTemporaryFile(delete=False, suffix=".cbz")
        shutil.copyfileobj(cbz_file.file, temp_cbz)
        temp_cbz.close()
        msg = reader.load_from_cbz(temp_cbz.name)
    else:
        raise HTTPException(status_code=400, detail="Modo de fuente no válido.")

    pages_media_urls = [f"/api/media?path={p}" for p in reader.pages]
    return {
        "message": msg,
        "total_pages": reader.total_pages(),
        "current_page": reader.current_page + 1,
        "source_name": reader.source_name,
        "pages": pages_media_urls,
    }


# ---------------------------------------------------------------------------
# API: Esteganografía y Verificación
# ---------------------------------------------------------------------------

@app.post("/api/watermark/verify")
async def verify_watermark(file: Optional[UploadFile] = File(None), image_path: Optional[str] = Form(None)):
    temp_path = None
    try:
        if file:
            with tempfile.NamedTemporaryFile(delete=False, suffix=".png") as tmp:
                shutil.copyfileobj(file.file, tmp)
                temp_path = tmp.name
            path_to_check = temp_path
        elif image_path and os.path.exists(image_path):
            path_to_check = image_path
        else:
            raise HTTPException(status_code=400, detail="Imagen requerida.")

        res = steganography.detect_watermark(path_to_check)
        return res
    finally:
        if temp_path and os.path.exists(temp_path):
            try: os.unlink(temp_path)
            except Exception: pass


@app.post("/api/watermark/stress")
async def stress_watermark(file: Optional[UploadFile] = File(None), image_path: Optional[str] = Form(None)):
    temp_path = None
    try:
        if file:
            with tempfile.NamedTemporaryFile(delete=False, suffix=".png") as tmp:
                shutil.copyfileobj(file.file, tmp)
                temp_path = tmp.name
            path_to_check = temp_path
        elif image_path and os.path.exists(image_path):
            path_to_check = image_path
        else:
            raise HTTPException(status_code=400, detail="Imagen requerida.")

        md_result = steganography.stress_test_watermark(path_to_check)
        return {"markdown": md_result}
    finally:
        if temp_path and os.path.exists(temp_path):
            try: os.unlink(temp_path)
            except Exception: pass


# ---------------------------------------------------------------------------
# API: Copia de Seguridad
# ---------------------------------------------------------------------------

@app.get("/api/backup/export")
def export_backup():
    try:
        zip_path = library_manager.export_backup()
        return FileResponse(zip_path, filename=os.path.basename(zip_path), media_type="application/zip")
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))


@app.post("/api/backup/import")
async def import_backup(file: UploadFile = File(...)):
    with tempfile.NamedTemporaryFile(delete=False, suffix=".zip") as tmp:
        shutil.copyfileobj(file.file, tmp)
        temp_zip = tmp.name
    try:
        msg = library_manager.import_backup(temp_zip)
        return {"message": msg}
    finally:
        if os.path.exists(temp_zip):
            try: os.unlink(temp_zip)
            except Exception: pass


@app.get("/api/backup/list")
def list_backups():
    return {"info_markdown": library_manager.get_backup_info()}


# ---------------------------------------------------------------------------
# API: Servido Seguro de Archivos Multimedia Locales
# ---------------------------------------------------------------------------

from config import IMAGE_EXTENSIONS

@app.get("/api/media")
def serve_media(path: str):
    if not path:
        raise HTTPException(status_code=400, detail="Ruta no especificada.")
    unquoted_path = urllib.parse.unquote(path)
    clean_path = os.path.normpath(unquoted_path)
    if not os.path.isfile(clean_path):
        raise HTTPException(status_code=404, detail="Archivo no encontrado.")
    
    ext = Path(clean_path).suffix.lower()
    allowed_exts = IMAGE_EXTENSIONS.union({".cbz", ".zip"})
    if ext not in allowed_exts:
        raise HTTPException(status_code=403, detail="Acceso denegado: solo se permite servir archivos multimedia.")

    return FileResponse(clean_path)


# ---------------------------------------------------------------------------
# Frontend Estático SPA (dist)
# ---------------------------------------------------------------------------

if getattr(sys, 'frozen', False):
    _ROOT_DIR = Path(getattr(sys, '_MEIPASS', os.path.dirname(sys.executable)))
else:
    _ROOT_DIR = Path(__file__).parent.resolve()

dist_dir = _ROOT_DIR / "frontend" / "dist"
if dist_dir.exists():
    app.mount("/", StaticFiles(directory=str(dist_dir), html=True), name="static")
else:
    @app.get("/")
    def index_fallback():
        return JSONResponse({
            "message": "Manga Coloring Tool 2 backend is running.",
            "notice": "Build the frontend with 'cd frontend && npm run build' to view the React interface."
        })


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("server:app", host="127.0.0.1", port=GRADIO_PORT, reload=False)
