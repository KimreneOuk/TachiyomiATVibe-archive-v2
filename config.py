"""
config.py — Configuración centralizada de Manga Studio Pro V3
Estructura tipada y constante para todo el sistema.
"""
from dataclasses import dataclass, field
from pathlib import Path
import uuid
from typing import Final, Dict, Set, List, Any


@dataclass(frozen=True)
class WorkflowNodes:
    """Identificadores de nodos dentro de workflow_api_img2img.json."""
    MAIN_IMAGE: str = "76"
    REF_IMAGE: str = "81"
    PROMPT: str = "92:74"
    STEPS: str = "92:62"
    CFG: str = "92:63"
    MEGAPIXELS: List[str] = field(default_factory=lambda: ["92:80", "92:85"])


@dataclass(frozen=True)
class AppConfig:
    """Configuración inmutable de la aplicación."""
    COMFY_SERVER: str = "127.0.0.1:8188"
    GRADIO_PORT: int = 7860
    CLIENT_ID: str = field(default_factory=lambda: str(uuid.uuid4()))
    WORKFLOW_FILE: str = "workflow_api_img2img.json"
    
    BASE_DIR: Path = field(default_factory=lambda: Path(__file__).parent.resolve())
    
    @property
    def output_dir(self) -> Path:
        return (self.BASE_DIR / "Colored Manga").resolve()
        
    @property
    def library_dir(self) -> Path:
        return (self.BASE_DIR / "library").resolve()
        
    @property
    def history_file(self) -> Path:
        return (self.BASE_DIR / "history.json").resolve()
        
    @property
    def favorites_file(self) -> Path:
        return (self.BASE_DIR / "favorites.json").resolve()
        
    @property
    def backup_dir(self) -> Path:
        return (self.BASE_DIR / "backups").resolve()
        
    @property
    def temp_inputs_dir(self) -> Path:
        return (self.BASE_DIR / "temp_inputs").resolve()


# --- Instancia global ---
CONFIG: Final[AppConfig] = AppConfig()
NODES: Final[WorkflowNodes] = WorkflowNodes()

# --- Exportaciones retrocompatibles ---
COMFYUI_SERVER_ADDRESS: Final[str] = CONFIG.COMFY_SERVER
GRADIO_PORT: Final[int] = CONFIG.GRADIO_PORT
CLIENT_ID: Final[str] = CONFIG.CLIENT_ID
WORKFLOW_FILE: Final[str] = CONFIG.WORKFLOW_FILE

OUTPUT_BASE_DIR: Final[Path] = CONFIG.output_dir
LIBRARY_DIR: Final[Path] = CONFIG.library_dir
HISTORY_FILE: Final[Path] = CONFIG.history_file
FAVORITES_FILE: Final[Path] = CONFIG.favorites_file
BACKUP_DIR: Final[Path] = CONFIG.backup_dir
TEMP_INPUTS_DIR: Final[Path] = CONFIG.temp_inputs_dir

IMAGE_EXTENSIONS: Final[Set[str]] = {".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tiff", ".tif"}

MAIN_IMAGE_NODE_ID: Final[str] = NODES.MAIN_IMAGE
REF_IMAGE_NODE_ID: Final[str] = NODES.REF_IMAGE
PROMPT_NODE_ID: Final[str] = NODES.PROMPT
STEPS_NODE_ID: Final[str] = NODES.STEPS
CFG_NODE_ID: Final[str] = NODES.CFG
MEGAPIXELS_NODE_IDS: Final[List[str]] = NODES.MEGAPIXELS


def ensure_directories_exist() -> None:
    """Asegura que todas las carpetas base del sistema existan."""
    CONFIG.output_dir.mkdir(parents=True, exist_ok=True)
    CONFIG.library_dir.mkdir(parents=True, exist_ok=True)
    CONFIG.backup_dir.mkdir(parents=True, exist_ok=True)
    CONFIG.temp_inputs_dir.mkdir(parents=True, exist_ok=True)

