"""
library_manager.py — Gestión de biblioteca, historial, favoritos y respaldos
"""
import json
import os
import shutil
import tempfile
import uuid
import zipfile
from datetime import datetime
from pathlib import Path
from typing import List, Dict, Any, Tuple, Optional

from config import (
    HISTORY_FILE, FAVORITES_FILE, LIBRARY_DIR,
    BACKUP_DIR, OUTPUT_BASE_DIR,
    IMAGE_EXTENSIONS,
)


def _load_json(filepath: Path, default: Any = None) -> Any:
    if default is None:
        default = []
    if filepath.exists():
        try:
            with open(filepath, "r", encoding="utf-8") as f:
                return json.load(f)
        except (json.JSONDecodeError, IOError):
            pass
    return default


def _save_json(filepath: Path, data: Any) -> None:
    """Guarda datos JSON de forma atómica para evitar corrupción."""
    filepath.parent.mkdir(parents=True, exist_ok=True)
    temp_fd, temp_path = tempfile.mkstemp(dir=str(filepath.parent), prefix=".tmp_json_")
    try:
        with os.fdopen(temp_fd, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
        os.replace(temp_path, str(filepath))
    except Exception:
        if os.path.exists(temp_path):
            os.unlink(temp_path)
        raise


def load_history() -> List[Dict[str, Any]]:
    return _load_json(HISTORY_FILE, [])


def save_history(data: List[Dict[str, Any]]) -> None:
    _save_json(HISTORY_FILE, data)


def add_history_entry(
    session_name: str,
    input_source: str,
    output_folder: str,
    num_images: int,
    status: str = "completado",
) -> Dict[str, Any]:
    """Añade una entrada al historial y la devuelve."""
    history = load_history()
    entry = {
        "id": str(uuid.uuid4())[:8],
        "session_name": session_name,
        "input_source": input_source,
        "output_folder": output_folder,
        "num_images": num_images,
        "status": status,
        "timestamp": datetime.now().isoformat(),
        "date_display": datetime.now().strftime("%Y-%m-%d %H:%M"),
    }
    history.insert(0, entry)
    save_history(history)
    return entry


def get_history_display() -> str:
    """Genera un texto Markdown con el historial de sesiones."""
    history = load_history()
    if not history:
        return "*No hay sesiones en el historial.*"

    lines = ["| Fecha | Sesión | Imágenes | Estado | Carpeta |",
             "|---|---|---|---|---|"]
    for h in history[:50]:
        fav_icon = "⭐" if is_favorite(h.get("id", "")) else ""
        lines.append(
            f"| {h.get('date_display', '?')} "
            f"| {fav_icon} {h.get('session_name', '?')} "
            f"| {h.get('num_images', '?')} "
            f"| {h.get('status', '?')} "
            f"| `{h.get('output_folder', '?')}` |"
        )
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# Favoritos
# ---------------------------------------------------------------------------

def load_favorites() -> List[str]:
    """Carga la lista de IDs de sesiones favoritas."""
    return _load_json(FAVORITES_FILE, [])


def save_favorites(data: List[str]) -> None:
    _save_json(FAVORITES_FILE, data)


def is_favorite(session_id: str) -> bool:
    return session_id in load_favorites()


def toggle_favorite(session_id: str) -> Tuple[bool, str]:
    """
    Alterna el estado de favorito de una sesión.
    Si es favorita, copia su carpeta de salida a LIBRARY_DIR.
    Devuelve (is_now_favorite, mensaje).
    """
    if not session_id:
        return False, "⚠️ Selecciona una sesión primero."

    favorites = load_favorites()
    history = load_history()

    entry = None
    for h in history:
        if h.get("id") == session_id:
            entry = h
            break

    if not entry:
        return False, f"⚠️ Sesión '{session_id}' no encontrada en el historial."

    if session_id in favorites:
        favorites.remove(session_id)
        save_favorites(favorites)
        
        session_name = entry.get("session_name", "unknown")
        s_id = entry.get("id", "")
        lib_copy_1 = LIBRARY_DIR / session_name
        lib_copy_2 = LIBRARY_DIR / f"{session_name}_{s_id}"
        
        if lib_copy_2.exists():
            shutil.rmtree(lib_copy_2, ignore_errors=True)
        elif lib_copy_1.exists():
            shutil.rmtree(lib_copy_1, ignore_errors=True)
            
        return False, f"💔 «{session_name}» eliminada de favoritos."
    else:
        favorites.append(session_id)
        save_favorites(favorites)
        _copy_to_library(entry)
        return True, f"⭐ «{entry['session_name']}» añadida a favoritos y copiada a biblioteca."


def _copy_to_library(entry: Dict[str, Any]) -> None:
    """Copia la carpeta de salida de una sesión a LIBRARY_DIR."""
    src = Path(entry.get("output_folder", ""))
    if not src.exists():
        return

    LIBRARY_DIR.mkdir(parents=True, exist_ok=True)
    dest = LIBRARY_DIR / entry.get("session_name", "unknown")
    if dest.exists():
        dest = LIBRARY_DIR / f"{entry['session_name']}_{entry['id']}"

    try:
        shutil.copytree(src, dest, dirs_exist_ok=True)
    except Exception as exc:
        print(f"[library_manager] Error copying to library: {exc}")


def get_favorites_display() -> str:
    """Genera un texto Markdown con las sesiones favoritas."""
    favorites = load_favorites()
    history = load_history()

    if not favorites:
        return "*No hay favoritos todavía. Marca una sesión con ⭐ desde el historial.*"

    lines = ["| Fecha | Sesión | Imágenes | Carpeta Biblioteca |",
             "|---|---|---|---|"]
    for h in history:
        if h.get("id") in favorites:
            lib_path = LIBRARY_DIR / h.get("session_name", "?")
            lines.append(
                f"| {h.get('date_display', '?')} "
                f"| ⭐ {h.get('session_name', '?')} "
                f"| {h.get('num_images', '?')} "
                f"| `{lib_path}` |"
            )
    return "\n".join(lines)


def get_library_gallery() -> List[Tuple[str, str]]:
    """
    Devuelve una lista de (ruta_imagen, nombre) de las primeras imágenes
    de cada manga en la biblioteca, para mostrar como galería.
    """
    if not LIBRARY_DIR.exists():
        return []

    gallery = []
    for folder in sorted(LIBRARY_DIR.iterdir()):
        if not folder.is_dir():
            continue
        images = sorted([
            f for f in folder.iterdir()
            if f.is_file() and f.suffix.lower() in IMAGE_EXTENSIONS
        ])
        if images:
            gallery.append((str(images[0]), folder.name))
    return gallery


def get_history_entries_for_dropdown() -> List[str]:
    """Devuelve opciones para un dropdown: 'id | nombre | fecha'."""
    history = load_history()
    choices = []
    for h in history[:50]:
        fav = "⭐ " if is_favorite(h.get("id", "")) else ""
        choices.append(f"{h['id']} | {fav}{h['session_name']} | {h.get('date_display', '?')}")
    return choices


# ---------------------------------------------------------------------------
# Backup / Respaldo
# ---------------------------------------------------------------------------

def _is_safe_extract_path(base_dir: Path, target_path: Path) -> bool:
    """Verifica que la ruta de extracción no salga del directorio base (previene Zip Slip)."""
    try:
        base_res = base_dir.resolve()
        target_res = target_path.resolve()
        return target_res == base_res or base_res in target_res.parents
    except Exception:
        return False


def export_backup() -> str:
    """
    Crea un archivo ZIP con todo el estado de la app:
    - history.json, favorites.json, presets.json
    - Carpeta library/ completa
    Devuelve la ruta al ZIP generado.
    """
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    zip_path = BACKUP_DIR / f"manga_coloring_tool_backup_{timestamp}.zip"

    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for jf in [HISTORY_FILE, FAVORITES_FILE]:
            if jf.exists():
                zf.write(jf, jf.name)

        if LIBRARY_DIR.exists():
            for root, _, files in os.walk(LIBRARY_DIR):
                for file in files:
                    file_path = Path(root) / file
                    arcname = Path("library") / file_path.relative_to(LIBRARY_DIR)
                    zf.write(file_path, str(arcname))

    return str(zip_path)


def import_backup(zip_path: str) -> str:
    """
    Restaura un respaldo desde un archivo ZIP de forma segura.
    Devuelve un mensaje de estado.
    """
    if not zip_path or not os.path.exists(zip_path):
        return "⚠️ Archivo de respaldo no encontrado."

    try:
        base_dir = Path(__file__).parent.resolve()
        count_json = 0
        count_lib = 0

        with zipfile.ZipFile(zip_path, "r") as zf:
            for member in zf.infolist():
                filename = member.filename
                # Normalizar ruta y prevenir Zip Slip
                target_path = base_dir / filename
                if not _is_safe_extract_path(base_dir, target_path):
                    continue  # Ignorar rutas no seguras

                if filename in ["history.json", "favorites.json", "presets.json"]:
                    zf.extract(member, base_dir)
                    count_json += 1
                elif filename.startswith("library/") and not filename.endswith("/"):
                    zf.extract(member, base_dir)
                    count_lib += 1

        return (
            f"✅ Respaldo importado correctamente.\n"
            f"   • {count_json} archivos de configuración restaurados\n"
            f"   • {count_lib} elementos de biblioteca restaurados"
        )
    except Exception as exc:
        return f"❌ Error al importar respaldo: {exc}"


def get_backup_info() -> str:
    """Muestra información sobre los respaldos existentes."""
    if not BACKUP_DIR.exists():
        return "*No hay respaldos previos.*"

    backups = sorted(
        [f for f in BACKUP_DIR.iterdir() if f.suffix == ".zip"],
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )

    if not backups:
        return "*No hay respaldos previos.*"

    lines = ["| Archivo | Tamaño | Fecha |",
             "|---|---|---|"]
    for b in backups[:10]:
        size_mb = b.stat().st_size / (1024 * 1024)
        mtime = datetime.fromtimestamp(b.stat().st_mtime).strftime("%Y-%m-%d %H:%M")
        lines.append(f"| `{b.name}` | {size_mb:.1f} MB | {mtime} |")
    return "\n".join(lines)

