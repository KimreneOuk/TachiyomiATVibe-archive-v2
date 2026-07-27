"""
manga_reader.py — Lector de manga con soporte para carpetas y archivos .cbz
"""
import os
import shutil
import tempfile
import zipfile
from pathlib import Path
from typing import List, Tuple, Optional

from config import IMAGE_EXTENSIONS


class MangaReader:
    """
    Lector de manga que soporta carpetas de imágenes y archivos .cbz.
    Mantiene el estado de la sesión de lectura actual y limpia archivos temporales.
    """

    def __init__(self):
        self.pages: List[str] = []
        self.current_page: int = 0
        self.source_name: str = ""
        self.source_type: str = ""  # "folder" o "cbz"
        self._temp_dir: Optional[str] = None

    def cleanup(self) -> None:
        """Elimina el directorio temporal si existe."""
        if self._temp_dir and os.path.exists(self._temp_dir):
            try:
                shutil.rmtree(self._temp_dir, ignore_errors=True)
            except Exception as e:
                print(f"[manga_reader] Error cleaning temporary directory: {e}")
            self._temp_dir = None

    def __del__(self):
        self.cleanup()

    def load_from_folder(self, folder_path: str) -> str:
        """Carga páginas desde una carpeta de imágenes."""
        self.cleanup()
        folder = Path(folder_path)
        if not folder.is_dir():
            return "❌ La carpeta no existe."

        self.pages = sorted([
            str(f) for f in folder.iterdir()
            if f.is_file() and f.suffix.lower() in IMAGE_EXTENSIONS
        ], key=lambda p: os.path.basename(p).lower())

        if not self.pages:
            return f"❌ No se encontraron imágenes en '{folder.name}'."

        self.current_page = 0
        self.source_name = folder.name
        self.source_type = "folder"
        return f"✅ Cargadas {len(self.pages)} páginas de «{folder.name}»"

    def load_from_cbz(self, cbz_path: str) -> str:
        """Carga páginas desde un archivo .cbz."""
        self.cleanup()
        if not cbz_path or not os.path.exists(cbz_path):
            return "❌ Archivo CBZ no encontrado."

        try:
            self._temp_dir = tempfile.mkdtemp(prefix="manga_reader_")
            with zipfile.ZipFile(cbz_path, "r") as zf:
                image_names = sorted([
                    n for n in zf.namelist()
                    if not n.endswith(".xml")
                    and any(n.lower().endswith(e) for e in IMAGE_EXTENSIONS)
                ])
                zf.extractall(self._temp_dir, image_names)

            self.pages = [
                os.path.join(self._temp_dir, n) for n in image_names
            ]

            if not self.pages:
                self.cleanup()
                return "❌ El CBZ no contiene imágenes."

            self.current_page = 0
            self.source_name = Path(cbz_path).stem
            self.source_type = "cbz"
            return f"✅ Cargadas {len(self.pages)} páginas de «{self.source_name}»"
        except Exception as exc:
            self.cleanup()
            return f"❌ Error al abrir CBZ: {exc}"

    def get_current_page(self) -> Optional[str]:
        """Devuelve la ruta de la página actual."""
        if not self.pages or self.current_page >= len(self.pages):
            return None
        return self.pages[self.current_page]

    def next_page(self) -> Tuple[Optional[str], str]:
        """Avanza a la siguiente página."""
        if not self.pages:
            return None, "No hay páginas cargadas."
        if self.current_page < len(self.pages) - 1:
            self.current_page += 1
        return self.get_current_page(), self._page_info()

    def prev_page(self) -> Tuple[Optional[str], str]:
        """Retrocede a la página anterior."""
        if not self.pages:
            return None, "No hay páginas cargadas."
        if self.current_page > 0:
            self.current_page -= 1
        return self.get_current_page(), self._page_info()

    def go_to_page(self, page_num: int) -> Tuple[Optional[str], str]:
        """Va a una página específica (1-indexed para el usuario)."""
        if not self.pages:
            return None, "No hay páginas cargadas."
        idx = max(0, min(page_num - 1, len(self.pages) - 1))
        self.current_page = idx
        return self.get_current_page(), self._page_info()

    def _page_info(self) -> str:
        """Genera la información de la página actual."""
        if not self.pages:
            return "Sin páginas"
        return (
            f"📖 **{self.source_name}** — "
            f"Página **{self.current_page + 1}** de **{len(self.pages)}**"
        )

    def total_pages(self) -> int:
        return len(self.pages)

    def is_loaded(self) -> bool:
        return len(self.pages) > 0


# Instancia global para uso en la app
reader = MangaReader()

