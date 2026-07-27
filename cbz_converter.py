"""
cbz_converter.py — Conversor de carpetas de imágenes a formato .cbz
"""
import os
import xml.sax.saxutils as xml_escape
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Union, List, Optional

from config import IMAGE_EXTENSIONS


def create_cbz(source_folder: Union[str, Path], output_path: Optional[Union[str, Path]] = None) -> str:
    """
    Empaqueta las imágenes de una carpeta en un archivo .cbz.
    Si output_path no se especifica, se crea junto a la carpeta fuente.
    Devuelve la ruta al .cbz generado.
    """
    source = Path(source_folder)
    if not source.is_dir():
        raise ValueError(f"La carpeta '{source}' no existe o no es un directorio.")

    images = sorted([
        f for f in source.iterdir()
        if f.is_file() and f.suffix.lower() in IMAGE_EXTENSIONS
    ], key=lambda p: p.name.lower())

    if not images:
        raise ValueError(f"No se encontraron imágenes en '{source}'.")

    if output_path is None:
        output_path = source.parent / f"{source.name}.cbz"
    else:
        output_path = Path(output_path)

    if output_path.exists():
        v = 2
        while True:
            new_path = output_path.parent / f"{output_path.stem}_V{v}.cbz"
            if not new_path.exists():
                output_path = new_path
                break
            v += 1

    with zipfile.ZipFile(output_path, "w", zipfile.ZIP_STORED) as zf:
        comic_info = _generate_comic_info(source.name, len(images))
        zf.writestr("ComicInfo.xml", comic_info)

        for i, img in enumerate(images):
            ext = img.suffix
            arcname = f"{i+1:04d}{ext}"
            zf.write(img, arcname)

    return str(output_path)


def _generate_comic_info(title: str, page_count: int) -> str:
    """Genera un ComicInfo.xml válido para el CBZ con escapado seguro."""
    safe_title = xml_escape.escape(title)
    now = datetime.now()
    return f"""<?xml version="1.0" encoding="utf-8"?>
<ComicInfo xmlns:xsd="http://www.w3.org/2001/XMLSchema"
           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <Title>{safe_title}</Title>
  <PageCount>{page_count}</PageCount>
  <Year>{now.year}</Year>
  <Month>{now.month}</Month>
  <Day>{now.day}</Day>
  <Notes>Coloreado con Manga Studio Pro — FLUX·2 Klein 4B</Notes>
  <Manga>Yes</Manga>
</ComicInfo>"""


def list_cbz_contents(cbz_path: Union[str, Path]) -> List[str]:
    """Lista los archivos dentro de un .cbz."""
    with zipfile.ZipFile(cbz_path, "r") as zf:
        return [n for n in zf.namelist() if not n.endswith(".xml")]


def extract_cbz_page(cbz_path: Union[str, Path], page_index: int, temp_dir: str) -> Optional[str]:
    """Extrae una página específica de un CBZ a un directorio temporal."""
    with zipfile.ZipFile(cbz_path, "r") as zf:
        pages = sorted([
            n for n in zf.namelist()
            if not n.endswith(".xml") and any(n.lower().endswith(e) for e in IMAGE_EXTENSIONS)
        ])
        if 0 <= page_index < len(pages):
            zf.extract(pages[page_index], temp_dir)
            return os.path.join(temp_dir, pages[page_index])
    return None


def get_cbz_page_count(cbz_path: Union[str, Path]) -> int:
    """Devuelve el número de páginas en un CBZ."""
    with zipfile.ZipFile(cbz_path, "r") as zf:
        return len([
            n for n in zf.namelist()
            if not n.endswith(".xml") and any(n.lower().endswith(e) for e in IMAGE_EXTENSIONS)
        ])

