"""
steganography.py — Marca de agua digital robusta a compresión JPEG agresiva
Algoritmo: Quantization Index Modulation (QIM) en dominio DCT (8×8 bloques) vectorizado.

El método es resistente a compresión JPEG Q50+ (Instagram, Twitter, WhatsApp)
porque el paso de cuantización Δ=60 supera los pasos de cuantización típicos
de JPEG en frecuencias medias (~2-40), preservando el bit embebido.
"""
import io
import os
import tempfile
from typing import Dict, Any, List, Tuple
import numpy as np
from PIL import Image
from scipy.fftpack import dct, idct

WATERMARK_MESSAGE: str = "AI_COLORED"
DELTA: float = 60.0
DCT_ROW: int = 3
DCT_COL: int = 4
CONFIDENCE_THRESHOLD: float = 0.55


def _text_to_bits(text: str) -> np.ndarray:
    """Convierte texto ASCII a ndarray de bits (MSB first)."""
    bits = []
    for ch in text:
        b = ord(ch) & 0xFF
        for i in range(7, -1, -1):
            bits.append((b >> i) & 1)
    return np.array(bits, dtype=np.int32)


def _bits_to_text(bits: List[int]) -> str:
    """Convierte lista de bits a texto. Se detiene en el primer byte nulo."""
    text = ""
    for i in range(0, len(bits) - 7, 8):
        byte = 0
        for bit in bits[i:i + 8]:
            byte = (byte << 1) | int(bit)
        if byte == 0:
            break
        try:
            text += chr(byte)
        except ValueError:
            break
    return text


def _extract_8x8_blocks(channel: np.ndarray) -> Tuple[np.ndarray, Tuple[int, int], Tuple[int, int]]:
    """Extrae bloques de 8x8 de un canal 2D en una vista 3D (N, 8, 8)."""
    h, w = channel.shape
    h8, w8 = (h // 8) * 8, (w // 8) * 8
    cropped = channel[:h8, :w8]
    # Reshape a (num_blocks_h, 8, num_blocks_w, 8) -> transpose a (num_blocks_h, num_blocks_w, 8, 8) -> reshape a (-1, 8, 8)
    blocks = cropped.reshape(h8 // 8, 8, w8 // 8, 8).swapaxes(1, 2).reshape(-1, 8, 8)
    return blocks, (h8, w8), (h8 // 8, w8 // 8)


def _reconstruct_channel(blocks: np.ndarray, grid_shape: Tuple[int, int], original_shape: Tuple[int, int], original_channel: np.ndarray) -> np.ndarray:
    """Reconstruye un canal 2D desde bloques (N, 8, 8)."""
    gh, gw = grid_shape
    h8, w8 = gh * 8, gw * 8
    reshaped = blocks.reshape(gh, gw, 8, 8).swapaxes(1, 2).reshape(h8, w8)
    
    result = original_channel.copy()
    result[:h8, :w8] = reshaped
    return result


def embed_watermark(
    input_path: str,
    output_path: str,
    message: str = WATERMARK_MESSAGE,
) -> str:
    """Incrusta `message` en la imagen mediante QIM-DCT vectorizado."""
    img = Image.open(input_path).convert("RGB")
    ycbcr = img.convert("YCbCr")
    y_ch, cb, cr = ycbcr.split()

    y_arr = np.array(y_ch, dtype=np.float64)
    blocks, (h8, w8), grid_shape = _extract_8x8_blocks(y_arr)
    n_blocks = len(blocks)

    if n_blocks == 0:
        img.save(output_path, format="PNG")
        return output_path

    bits = _text_to_bits(message)
    n_bits = len(bits)
    target_bits = np.tile(bits, (n_blocks // n_bits + 1))[:n_blocks]

    # DCT 2D vectorizada sobre todos los bloques
    C = dct(dct(blocks, axis=2, norm="ortho"), axis=1, norm="ortho")
    coefs = C[:, DCT_ROW, DCT_COL]

    # QIM Quantization
    q = np.round(coefs / (DELTA / 2.0)).astype(np.int64)
    q_mod = np.abs(q) % 2
    mismatch = (q_mod != target_bits)
    q[mismatch] += 1

    C[:, DCT_ROW, DCT_COL] = q.astype(np.float64) * (DELTA / 2.0)

    # IDCT 2D vectorizada
    reconstructed_blocks = idct(idct(C, axis=2, norm="ortho"), axis=1, norm="ortho")
    reconstructed_blocks = np.clip(reconstructed_blocks, 0.0, 255.0)

    y_new_arr = _reconstruct_channel(reconstructed_blocks, grid_shape, y_arr.shape, y_arr)
    y_new = Image.fromarray(np.uint8(np.round(y_new_arr)), mode="L")
    
    result_ycbcr = Image.merge("YCbCr", (y_new, cb, cr))
    result_rgb = result_ycbcr.convert("RGB")
    result_rgb.save(output_path, format="PNG")
    return output_path


def detect_watermark(
    image_path: str,
    expected_message: str = WATERMARK_MESSAGE,
) -> Dict[str, Any]:
    """Detecta si la imagen contiene la marca de agua con evaluación vectorizada."""
    img = Image.open(image_path).convert("RGB")
    ycbcr = img.convert("YCbCr")
    y_ch, _, _ = ycbcr.split()
    y_arr = np.array(y_ch, dtype=np.float64)

    blocks, _, _ = _extract_8x8_blocks(y_arr)
    n_blocks = len(blocks)

    expected_bits = _text_to_bits(expected_message)
    n_bits = len(expected_bits)

    if n_blocks == 0:
        return {
            "found": False, "detected": False, "decoded": "", "confidence": 0.0,
            "blocks_scanned": 0, "details": "❌ Imagen demasiado pequeña para analizar."
        }

    # DCT 2D vectorizada
    C = dct(dct(blocks, axis=2, norm="ortho"), axis=1, norm="ortho")
    coefs = C[:, DCT_ROW, DCT_COL]

    q = np.round(coefs / (DELTA / 2.0)).astype(np.int64)
    detected_bits = (np.abs(q) % 2).astype(np.int32)

    # Acumulación de votos por bit
    votes = np.zeros((n_bits, 2), dtype=np.int32)
    for i in range(n_blocks):
        bit_pos = i % n_bits
        votes[bit_pos, detected_bits[i]] += 1

    decoded_bits = []
    confidences = []
    for v0, v1 in votes:
        total = v0 + v1
        if total == 0:
            decoded_bits.append(0)
            confidences.append(0.5)
        elif v1 > v0:
            decoded_bits.append(1)
            confidences.append(v1 / total)
        else:
            decoded_bits.append(0)
            confidences.append(v0 / total)

    decoded_message = _bits_to_text(decoded_bits)
    avg_confidence = float(np.mean(confidences)) if confidences else 0.0
    found = (decoded_message == expected_message)

    if found:
        details = (
            f"✅ Marca de agua **AI_COLORED** detectada.\n"
            f"Mensaje decodificado: `{decoded_message}`\n"
            f"Confianza: **{avg_confidence * 100:.1f}%**  |  Bloques analizados: {n_blocks}"
        )
    elif decoded_message and avg_confidence >= CONFIDENCE_THRESHOLD:
        details = (
            f"⚠️ Se detectó un mensaje pero no coincide exactamente.\n"
            f"Mensaje: `{decoded_message}`\n"
            f"Confianza: {avg_confidence * 100:.1f}%  |  Bloques: {n_blocks}\n"
            f"Puede ser imagen comprimida agresivamente (Q<50) o modificada."
        )
    else:
        details = (
            f"❌ No se detectó marca de agua AI_COLORED.\n"
            f"Mensaje parcial: `{decoded_message or '(vacío)'}` | "
            f"Confianza: {avg_confidence * 100:.1f}% | Bloques: {n_blocks}"
        )

    return {
        "found": found,
        "detected": found,
        "decoded": decoded_message,
        "confidence": avg_confidence,
        "blocks_scanned": n_blocks,
        "details": details,
    }


def stress_test_watermark(
    image_path: str,
    expected_message: str = WATERMARK_MESSAGE,
) -> str:
    """Somete la imagen a distintos niveles de compresión JPEG (100, 80, 50, 30, 15)."""
    try:
        img = Image.open(image_path).convert("RGB")
    except Exception as exc:
        return f"❌ Error leyendo imagen para Stress Test: {exc}"

    qualities = [100, 80, 50, 30, 15]
    results = [
        "### 📊 Prueba de Estrés (Compresión Extrema JPEG)",
        "Se aplicó compresión progresiva para simular redes sociales:",
        ""
    ]

    for q in qualities:
        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=q)
        buf.seek(0)

        with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as tmp:
            tmp.write(buf.read())
            tmp_path = tmp.name

        try:
            det = detect_watermark(tmp_path, expected_message)
            conf_pct = det["confidence"] * 100
            
            if det["found"]:
                res_str = f"- **Calidad {q}%** : ✅ Resiste (Confianza {conf_pct:.1f}%)"
            elif det["decoded"] and det["confidence"] >= CONFIDENCE_THRESHOLD:
                res_str = f"- **Calidad {q}%** : ⚠️ Resiste a medias (Decodificó `{det['decoded']}`, {conf_pct:.1f}%)"
            else:
                res_str = f"- **Calidad {q}%** : ❌ Falla / Destruida (Confianza {conf_pct:.1f}%)"
            
            results.append(res_str)
        finally:
            if os.path.exists(tmp_path):
                try:
                    os.unlink(tmp_path)
                except Exception:
                    pass

    return "\n".join(results)


