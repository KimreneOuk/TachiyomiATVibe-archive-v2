"""
image_utils.py — Utilidades de manipulación de imágenes y texto
"""
import io
import os
import re
import tempfile
from datetime import datetime
from pathlib import Path
from typing import Union, Dict, Any, List, Optional
from PIL import Image

from config import IMAGE_EXTENSIONS

SPANISH_MONTHS = [
    "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
    "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
]


def sanitize_name(name: str) -> str:
    """Limpia un nombre de archivo/carpeta para que sea seguro en cualquier SO."""
    name = str(name).strip().replace(" ", "_")
    name = re.sub(r'(?u)[^-\w]', '', name)
    return name if name else "untitled"


def sanitize_filename(name: str) -> str:
    """Conserva el nombre original del archivo eliminando únicamente caracteres prohibidos en SO."""
    stem, _ = os.path.splitext(name)
    clean_stem = re.sub(r'[\\/:*?"<>|\r\n\t]', '_', stem).strip()
    return clean_stem if clean_stem else "untitled"


def get_ascii_bar(current: int, total: int, width: int = 22) -> str:
    """Genera una barra de progreso ASCII estilo bloque."""
    if total == 0:
        return f"[{'░' * width}] 0%"
    percent = float(current) / total
    filled = int(width * percent)
    bar = "█" * filled + "░" * (width - filled)
    return f"[{bar}] {int(percent * 100)}%"


def process_reference_image(ref_dict_or_path: Any) -> Optional[str]:
    """
    Acepta un dict de ImageEditor o una ruta str/Path.
    Si la imagen tiene canal alfa la compone sobre fondo blanco para evitar
    artefactos negros en ComfyUI.
    """
    if not ref_dict_or_path:
        return None

    if isinstance(ref_dict_or_path, dict):
        img_path = (
            ref_dict_or_path.get("composite")
            or ref_dict_or_path.get("path")
        )
    else:
        img_path = str(ref_dict_or_path)

    if not img_path or not os.path.exists(img_path):
        return None

    try:
        with Image.open(img_path) as img:
            has_alpha = (
                img.mode in ("RGBA", "LA")
                or (img.mode == "P" and "transparency" in img.info)
            )
            if has_alpha:
                bg = Image.new("RGB", img.size, (255, 255, 255))
                alpha = img.convert("RGBA").split()[3]
                bg.paste(img.convert("RGB"), mask=alpha)
                tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".png")
                bg.save(tmp.name)
                tmp.close()
                return tmp.name
        return img_path
    except Exception as exc:
        print(f"[image_utils] Error processing reference: {exc}")
        return img_path


def bytes_to_pil(data: bytes) -> Image.Image:
    """Convierte bytes crudos en una imagen PIL."""
    return Image.open(io.BytesIO(data))


def save_image_in_format(
    pil_img: Image.Image,
    target_dir: Path,
    base_name: str,
    original_ext: str = ".png",
    output_format: str = "png"
) -> Path:
    """
    Guarda una imagen PIL en el directorio especificado con el formato de salida seleccionado.
    Opciones: 'same_as_input', 'png', 'jpeg', 'webp_lossless', 'webp'.
    """
    orig_clean = (original_ext or ".png").lower()
    if not orig_clean.startswith("."):
        orig_clean = "." + orig_clean

    fmt_clean = (output_format or "png").lower().strip()

    if fmt_clean in ("same_as_input", "origen", "same"):
        target_ext = orig_clean if orig_clean in [".png", ".jpg", ".jpeg", ".webp", ".bmp"] else ".png"
    elif fmt_clean in ("jpeg", "jpg"):
        target_ext = ".jpg"
    elif fmt_clean in ("webp", "webp_lossless"):
        target_ext = ".webp"
    else:
        target_ext = ".png"

    img_to_save = pil_img
    if target_ext in [".jpg", ".jpeg"]:
        if img_to_save.mode in ("RGBA", "LA", "P"):
            bg = Image.new("RGB", img_to_save.size, (255, 255, 255))
            if img_to_save.mode in ("RGBA", "LA"):
                bg.paste(img_to_save.convert("RGBA"), mask=img_to_save.convert("RGBA").split()[3])
            else:
                bg.paste(img_to_save.convert("RGB"))
            img_to_save = bg
        elif img_to_save.mode != "RGB":
            img_to_save = img_to_save.convert("RGB")

    stem = sanitize_filename(base_name)
    safe_name = f"{stem}{target_ext}"
    save_path = target_dir / safe_name
    if save_path.exists():
        save_path = get_versioned_path(save_path)

    if target_ext in [".jpg", ".jpeg"]:
        img_to_save.save(save_path, quality=95)
    elif fmt_clean == "webp_lossless":
        img_to_save.save(save_path, lossless=True)
    elif target_ext == ".webp":
        img_to_save.save(save_path, quality=92)
    else:
        img_to_save.save(save_path)

    return save_path



def get_versioned_path(base_path: Union[str, Path]) -> Path:
    """
    Si base_path ya existe, genera una versión incrementada.
    Ej: manga_ch01 → manga_ch01_V2 → manga_ch01_V3
    Para archivos: image.png → V2_image.png → V3_image.png
    """
    base_path = Path(base_path)
    if not base_path.exists():
        return base_path

    parent = base_path.parent
    is_file = base_path.suffix != ""

    if is_file:
        stem = base_path.stem
        ext = base_path.suffix
        match = re.match(r'^V(\d+)_(.+)$', stem)
        if match:
            current_v = int(match.group(1))
            original_name = match.group(2)
        else:
            current_v = 1
            original_name = stem

        version = current_v + 1
        while True:
            new_name = f"V{version}_{original_name}{ext}"
            new_path = parent / new_name
            if not new_path.exists():
                return new_path
            version += 1
    else:
        name = base_path.name
        match = re.match(r'^(.+)_V(\d+)$', name)
        if match:
            original_name = match.group(1)
            current_v = int(match.group(2))
        else:
            original_name = name
            current_v = 1

        version = current_v + 1
        while True:
            new_name = f"{original_name}_V{version}"
            new_path = parent / new_name
            if not new_path.exists():
                return new_path
            version += 1


def collect_images_from_folder(folder_path: Union[str, Path]) -> List[Path]:
    """Recolecta todos los archivos de imagen de una carpeta (o sus subcarpetas si en la raíz no hay imágenes)."""
    folder = Path(folder_path)
    if not folder.is_dir():
        return []

    images = [
        f for f in folder.iterdir()
        if f.is_file() and f.suffix.lower() in IMAGE_EXTENSIONS
    ]

    if not images:
        images = [
            f for f in folder.rglob("*")
            if f.is_file() and f.suffix.lower() in IMAGE_EXTENSIONS
        ]

    return sorted(images, key=lambda p: p.name.lower())


def list_subfolders(folder_path: Union[str, Path]) -> List[Path]:
    """Lista las subcarpetas de una carpeta."""
    folder = Path(folder_path)
    if not folder.is_dir():
        return []
    return sorted(
        [f for f in folder.iterdir() if f.is_dir()],
        key=lambda p: p.name.lower()
    )


def create_chronological_path(base_dir: Union[str, Path], now: Optional[datetime] = None) -> Path:
    """Genera una ruta cronológica: base_dir / AAAA / M_Mes / D."""
    if now is None:
        now = datetime.now()

    year = str(now.year)
    month_idx = max(1, min(12, now.month)) - 1
    month_name = SPANISH_MONTHS[month_idx]
    month_folder = f"{now.month}_{month_name}"
    day = str(now.day)

    chrono_path = Path(base_dir) / year / month_folder / day
    chrono_path.mkdir(parents=True, exist_ok=True)
    return chrono_path

