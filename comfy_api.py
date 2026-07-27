"""
comfy_api.py — Capa de comunicación avanzada con ComfyUI
Encapsula todos los requests HTTP/WS al servidor ComfyUI.
"""
import json
import os
import uuid
from typing import Optional, Dict, Any
import requests
from requests.adapters import HTTPAdapter
from urllib3.util import Retry
import websocket

from config import COMFYUI_SERVER_ADDRESS, CLIENT_ID


class ComfyAPIError(Exception):
    """Excepción base para errores de la API de ComfyUI."""
    pass


class ComfyConnectionError(ComfyAPIError):
    """Excepción cuando el servidor ComfyUI no responde."""
    pass


class ComfyClient:
    """Cliente HTTP/WS para interactuar con ComfyUI."""

    def __init__(self, server_address: str = COMFYUI_SERVER_ADDRESS, client_id: str = CLIENT_ID):
        self.server_address = server_address
        self.client_id = client_id
        self.base_url = f"http://{server_address}"
        self.ws_url = f"ws://{server_address}/ws?clientId={client_id}"
        
        self.session = requests.Session()
        retries = Retry(total=3, backoff_factor=0.3, status_forcelist=[500, 502, 503, 504])
        self.session.mount("http://", HTTPAdapter(max_retries=retries))

    def is_healthy(self, timeout: float = 5.0) -> bool:
        """Comprueba si el servidor ComfyUI responde."""
        try:
            resp = self.session.get(f"{self.base_url}/system_stats", timeout=timeout)
            return resp.status_code == 200
        except requests.RequestException:
            return False

    def upload_image(self, filepath: str, timeout: float = 600.0) -> Dict[str, Any]:
        """Sube una imagen al servidor (sin caché) y devuelve el JSON de respuesta."""
        if not os.path.exists(filepath):
            raise FileNotFoundError(f"Archivo no encontrado: {filepath}")

        unique_name = f"img_{uuid.uuid4().hex[:8]}_{os.path.basename(filepath)}"
        try:
            with open(filepath, "rb") as f:
                resp = self.session.post(
                    f"{self.base_url}/upload/image",
                    files={"image": (unique_name, f)},
                    timeout=timeout,
                )
            resp.raise_for_status()
            return resp.json()
        except requests.RequestException as e:
            raise ComfyAPIError(f"Error al subir imagen '{filepath}': {e}") from e

    def queue_prompt(self, prompt_workflow: Dict[str, Any], timeout: float = 600.0) -> Dict[str, Any]:
        """Encola un workflow en ComfyUI y devuelve la respuesta en JSON (incluye prompt_id)."""
        payload = {"prompt": prompt_workflow, "client_id": self.client_id}
        try:
            resp = self.session.post(f"{self.base_url}/prompt", json=payload, timeout=timeout)
            resp.raise_for_status()
            return resp.json()
        except requests.RequestException as e:
            raise ComfyAPIError(f"Error al encolar prompt: {e}") from e

    def get_image_bytes(self, filename: str, subfolder: str, folder_type: str, timeout: float = 600.0) -> bytes:
        """Descarga los bytes de una imagen generada desde el servidor."""
        params = {"filename": filename, "subfolder": subfolder, "type": folder_type}
        try:
            resp = self.session.get(f"{self.base_url}/view", params=params, timeout=timeout)
            resp.raise_for_status()
            return resp.content
        except requests.RequestException as e:
            raise ComfyAPIError(f"Error al descargar imagen '{filename}': {e}") from e

    def get_history(self, prompt_id: str, timeout: float = 600.0) -> Dict[str, Any]:
        """Obtiene el historial de ejecución de un prompt_id concreto."""
        try:
            resp = self.session.get(f"{self.base_url}/history/{prompt_id}", timeout=timeout)
            resp.raise_for_status()
            return resp.json()
        except requests.RequestException as e:
            raise ComfyAPIError(f"Error obteniendo historial para prompt_id '{prompt_id}': {e}") from e

    def interrupt(self, timeout: float = 15.0) -> bool:
        """Envía la señal de interrupción a ComfyUI."""
        try:
            resp = self.session.post(f"{self.base_url}/interrupt", timeout=timeout)
            return resp.status_code == 200
        except requests.RequestException as exc:
            print(f"[comfy_api] Error interrupting generation: {exc}")
            return False

    def wait_for_completion(self, prompt_id: str, cancel_event: Any) -> bool:
        """
        Escucha el WebSocket de ComfyUI hasta que finalice el `prompt_id`
        o se active el `cancel_event`.
        """
        ws = websocket.WebSocket()
        try:
            ws.connect(self.ws_url)
            ws.settimeout(1.0)
            while True:
                if cancel_event.is_set():
                    return False
                try:
                    out = ws.recv()
                    if isinstance(out, str):
                        msg = json.loads(out)
                        if (
                            msg.get("type") == "executing"
                            and msg.get("data", {}).get("node") is None
                            and msg.get("data", {}).get("prompt_id") == prompt_id
                        ):
                            return True
                except websocket.WebSocketTimeoutException:
                    continue
                except Exception:
                    # Si el websocket falla o se desconecta, fallback a polling de historial
                    return True
        except Exception as exc:
            print(f"[comfy_api] Error in WebSocket connection: {exc}")
            return True
        finally:
            try:
                ws.close()
            except Exception:
                pass


# --- Instancia global por defecto ---
default_client = ComfyClient()

# --- Funciones de interfaz retrocompatibles ---

def check_server_status() -> bool:
    """Devuelve True si el servidor ComfyUI está accesible."""
    return default_client.is_healthy()


def upload_image(filepath: str) -> dict:
    """Sube una imagen al servidor y devuelve el JSON de respuesta."""
    return default_client.upload_image(filepath)


def queue_prompt(prompt_workflow: dict) -> dict:
    """Encola un workflow en ComfyUI y devuelve el JSON."""
    return default_client.queue_prompt(prompt_workflow)


def get_image(filename: str, subfolder: str, folder_type: str) -> bytes:
    """Descarga los bytes de una imagen generada desde el servidor."""
    return default_client.get_image_bytes(filename, subfolder, folder_type)


def get_history(prompt_id: str) -> dict:
    """Obtiene el historial de ejecución de un prompt_id."""
    return default_client.get_history(prompt_id)


def interrupt_generation() -> None:
    """Envía la señal de interrupción a ComfyUI."""
    default_client.interrupt()


def wait_for_completion(prompt_id: str, cancel_event) -> bool:
    """Espera la finalización mediante WebSocket."""
    return default_client.wait_for_completion(prompt_id, cancel_event)

