"""
generator.py — Lógica de generación de imágenes con ComfyUI y streaming en Gradio
"""
import json
import os
import random
import shutil
import tempfile
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Optional, List, Dict, Any, Generator, Tuple, Union

import comfy_api
import image_utils
import library_manager
import steganography
from config import (
    WORKFLOW_FILE,
    MAIN_IMAGE_NODE_ID,
    REF_IMAGE_NODE_ID,
    PROMPT_NODE_ID,
    STEPS_NODE_ID,
    CFG_NODE_ID,
    MEGAPIXELS_NODE_IDS,
    OUTPUT_BASE_DIR,
)

# ---------------------------------------------------------------------------
# Cancelación global — threading.Event (thread-safe)
# ---------------------------------------------------------------------------
_cancel_event = threading.Event()


def request_cancel() -> str:
    """Solicita la cancelación de la generación en curso."""
    _cancel_event.set()
    comfy_api.interrupt_generation()
    return "🛑 Cancelando… la imagen actual puede terminar de procesarse antes de detenerse."


def _reset_cancel() -> None:
    _cancel_event.clear()


# ---------------------------------------------------------------------------
# Generación de imagen única
# ---------------------------------------------------------------------------

def generate_single_image(
    input_image_path: str,
    ref_image_path: Optional[str],
    prompt_text: str,
    use_ref: bool,
    steps: int,
    cfg: float = 1.0,
    megapixels: float = 1.0,
) -> Optional[bytes]:
    """
    Envía un workflow a ComfyUI y devuelve los bytes de la imagen generada.
    Devuelve None si se cancela o no hay salida.
    """
    if _cancel_event.is_set():
        return None

    if not os.path.exists(WORKFLOW_FILE):
        raise FileNotFoundError(f"Workflow no encontrado: '{WORKFLOW_FILE}'")

    with open(WORKFLOW_FILE, "r", encoding="utf-8") as f:
        wf = json.load(f)

    seed = random.randint(1, 10**14)

    # 1 — Imagen principal
    main_info = comfy_api.upload_image(input_image_path)
    wf[MAIN_IMAGE_NODE_ID]["inputs"]["image"] = main_info["name"]

    # 2 — Imagen de referencia
    if use_ref and ref_image_path and os.path.exists(ref_image_path):
        ref_info = comfy_api.upload_image(ref_image_path)
        wf[REF_IMAGE_NODE_ID]["inputs"]["image"] = ref_info["name"]
    else:
        wf[REF_IMAGE_NODE_ID]["inputs"]["image"] = main_info["name"]

    # 3 — Prompt, pasos, CFG y megapíxeles
    if PROMPT_NODE_ID in wf:
        wf[PROMPT_NODE_ID]["inputs"]["text"] = prompt_text
    if STEPS_NODE_ID in wf:
        wf[STEPS_NODE_ID]["inputs"]["steps"] = int(steps)
    if CFG_NODE_ID in wf:
        wf[CFG_NODE_ID]["inputs"]["cfg"] = float(cfg)
    for mp_node in MEGAPIXELS_NODE_IDS:
        if mp_node in wf:
            wf[mp_node]["inputs"]["megapixels"] = float(megapixels)

    # 4 — Semilla dinámica
    for node_data in wf.values():
        ct = node_data.get("class_type", "")
        if ct == "RandomNoise":
            node_data["inputs"]["noise_seed"] = seed
        elif ct == "KSampler":
            node_data["inputs"]["seed"] = seed

    # 5 — Encolar y esperar
    prompt_id = comfy_api.queue_prompt(wf)["prompt_id"]
    completed = comfy_api.wait_for_completion(prompt_id, _cancel_event)

    if not completed or _cancel_event.is_set():
        return None

    # 6 — Recoger resultado
    history = comfy_api.get_history(prompt_id).get(prompt_id, {})
    for node_outputs in history.get("outputs", {}).values():
        for img in node_outputs.get("images", []):
            return comfy_api.get_image(img["filename"], img["subfolder"], img["type"])
    return None


# ---------------------------------------------------------------------------
# Helpers de sesión y lotes
# ---------------------------------------------------------------------------

def _prepare_session_output_dir(folder_name_input: str, preset_name: str) -> Tuple[Path, str]:
    """Crea y devuelve la carpeta de salida versionada cronológica."""
    if preset_name:
        safe_preset = image_utils.sanitize_name(preset_name)
        folder_name_input = f"{folder_name_input}_{safe_preset}"

    safe_folder = image_utils.sanitize_name(folder_name_input)
    chrono_base = image_utils.create_chronological_path(OUTPUT_BASE_DIR)
    target_dir = image_utils.get_versioned_path(chrono_base / safe_folder)
    target_dir.mkdir(parents=True, exist_ok=True)
    return target_dir, safe_folder


def _write_settings_txt(target_dir: Path, params: Dict[str, Any]) -> None:
    """Escribe el archivo settings.txt con los parámetros usados."""
    try:
        settings_path = target_dir / "settings.txt"
        with open(settings_path, "w", encoding="utf-8") as f:
            for key, val in params.items():
                f.write(f"{key}: {val}\n")
    except Exception as e:
        print(f"[generator] Could not create settings.txt: {e}")





# ---------------------------------------------------------------------------
# Procesamiento en lote (generador Python para streaming en Gradio)
# ---------------------------------------------------------------------------

def batch_process_generator(
    list_of_images: Optional[List[Any]],
    ref_image_input: Any,
    prompt_text: str,
    use_ref: bool,
    folder_name_input: str,
    steps: int,
    auto_ref_switch: bool,
    cfg: float = 1.0,
    megapixels: float = 1.0,
    embed_watermark: bool = False,
    use_folder_mode: bool = False,
    folder_path_input: str = "",
    preset_name: str = "",
    output_format: str = "png",
) -> Generator[Tuple[List[Any], str, Optional[str], str], None, None]:
    """Generador que procesa un lote de imágenes y hace yield del estado tras cada imagen."""
    _reset_cancel()

    # --- Recopilar imágenes según el modo ---
    if use_folder_mode and folder_path_input:
        folder_path = Path(folder_path_input.strip())
        if not folder_path.is_dir():
            yield [], f"❌ La carpeta '{folder_path}' no existe.", None, ""
            return
        image_paths = image_utils.collect_images_from_folder(folder_path)
        if not image_paths:
            yield [], f"❌ No se encontraron imágenes en '{folder_path.name}'.", None, ""
            return
        input_source = str(folder_path)
        if not folder_name_input or folder_name_input == "manga_chapter_01":
            folder_name_input = folder_path.name
    elif list_of_images:
        image_paths = None
        input_source = "archivos_sueltos"
    else:
        yield [], "❌ No hay imágenes de origen.", None, ""
        return

    # --- Preparar carpeta de salida ---
    target_dir, safe_folder = _prepare_session_output_dir(folder_name_input, preset_name)

    # --- Guardar settings.txt ---
    _write_settings_txt(target_dir, {
        "Mode": preset_name or "Standard",
    })

    def _get_orig_name(f: Any) -> str:
        orig = getattr(f, "orig_name", None)
        if orig:
            return orig
        return os.path.basename(getattr(f, "name", str(f)))

    # --- Preparar lista de archivos ---
    if image_paths is not None:
        class FakeFile:
            def __init__(self, path):
                self.name = str(path)
        sorted_images = [FakeFile(p) for p in image_paths]
    else:
        sorted_images = sorted(list_of_images, key=lambda f: _get_orig_name(f).lower())

    gallery = []
    total = len(sorted_images)
    last_saved = None
    current_ref = image_utils.process_reference_image(ref_image_input)
    session_start = time.time()
    done_count = 0

    for i, img_file in enumerate(sorted_images):
        if _cancel_event.is_set():
            yield gallery, "🛑 CANCELADO POR EL USUARIO.", last_saved, _stats_dict(done_count, total, session_start, "", "Cancelado")
            return

        original_name = _get_orig_name(img_file)
        elapsed = _elapsed_str(session_start)
        yield (
            gallery,
            f"Procesando: {original_name}",
            last_saved,
            _stats_dict(done_count, total, session_start, original_name, "Procesando"),
        )

        try:
            img_bytes = generate_single_image(
                img_file.name, current_ref, prompt_text, use_ref, steps, cfg, megapixels
            )

            if _cancel_event.is_set():
                break

            if img_bytes:
                pil_img = image_utils.bytes_to_pil(img_bytes)
                orig_ext = os.path.splitext(original_name)[1]

                save_path = image_utils.save_image_in_format(
                    pil_img, target_dir, original_name, orig_ext, output_format
                )

                if embed_watermark:
                    try:
                        steganography.embed_watermark(str(save_path), str(save_path))
                    except Exception as wm_err:
                        print(f"[generator] Watermark error in {save_path.name}: {wm_err}")

                last_saved = str(save_path)
                done_count += 1

                gallery.append({
                    "output": str(save_path),
                    "original": str(img_file.name),
                    "name": save_path.name
                })

                if auto_ref_switch:
                    current_ref = last_saved

                wm_tag = " 🔏" if embed_watermark else ""
                elapsed = _elapsed_str(session_start)
                yield (
                    gallery,
                    f"✅ Guardado{wm_tag}: {save_path.name}",
                    last_saved,
                    _stats_dict(done_count, total, session_start, original_name, "Guardado"),
                )

        except Exception as exc:
            yield (
                gallery,
                f"❌ Error en {original_name}:\n{exc}",
                last_saved,
                _stats_dict(done_count, total, session_start, original_name, "Error"),
            )

    status = "cancelado" if _cancel_event.is_set() else "completado"
    total_elapsed_secs = int(time.time() - session_start)
    total_duration_str = _format_duration(total_elapsed_secs)
    avg_per_image = (total_elapsed_secs / done_count) if done_count > 0 else 0

    # Guardar métricas finales en settings.txt
    _write_settings_txt(target_dir, {
        "Project": "Manga Coloring Tool 2",
        "Session Name": safe_folder,
        "Colorization Mode": preset_name or "Standard",
        "Output Format": output_format.upper(),
        "Completed Images": f"{done_count}/{total}",
        "Total Time": total_duration_str,
        "Average Time Per Image": f"{avg_per_image:.2f}s",
        "Status": status.capitalize(),
        "Completed At": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
    })

    library_manager.add_history_entry(
        session_name=safe_folder,
        input_source=input_source if use_folder_mode else "archivos_sueltos",
        output_folder=str(target_dir),
        num_images=done_count,
        status=status,
    )

    if not _cancel_event.is_set() and os.name == "nt" and target_dir.exists():
        try:
            os.startfile(target_dir)
        except Exception:
            pass

    final = (
        f"🛑 CANCELADO POR EL USUARIO — {done_count}/{total} imágenes (Tiempo: {total_duration_str})"
        if _cancel_event.is_set()
        else f"✨ COMPLETADO — {done_count}/{total} imágenes en {total_duration_str} → {target_dir}"
    )
    yield gallery, final, last_saved, _stats_dict(done_count, total, session_start, "", "Finalizado" if not _cancel_event.is_set() else "Cancelado")


# ---------------------------------------------------------------------------
# Helpers de formato de tiempo y estadísticas estructuradas
# ---------------------------------------------------------------------------

def _format_duration(seconds: int) -> str:
    """Formatea una duración en segundos a MM:SS o HH:MM:SS según corresponda."""
    if seconds < 0:
        seconds = 0
    h, remainder = divmod(seconds, 3600)
    m, s = divmod(remainder, 60)
    if h > 0:
        return f"{h:02d}:{m:02d}:{s:02d}"
    return f"{m:02d}:{s:02d}"


def _elapsed_str(start: float) -> str:
    secs = int(time.time() - start)
    return _format_duration(secs)


def _stats_dict(done: int, total: int, start: float, current_file: str = "", stage: str = "") -> Dict[str, Any]:
    elapsed_secs = int(time.time() - start)
    avg = (elapsed_secs / done) if done > 0 else 0
    remaining_secs = int(avg * (total - done))
    
    eta_str = _format_duration(remaining_secs) if done > 0 else "--:--"
    percent = int((done / total) * 100) if total > 0 else 0

    return {
        "done": done,
        "total": total,
        "percent": percent,
        "elapsed": _elapsed_str(start),
        "eta": eta_str,
        "current_file": current_file,
        "stage": stage,
        "text": f"Completadas: **{done}/{total}** | Tiempo: {_elapsed_str(start)} | ETA: {eta_str}",
    }


