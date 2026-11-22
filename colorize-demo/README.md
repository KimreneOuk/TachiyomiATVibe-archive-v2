# MCV2 Manga Colorization Demo

Local web application for neural manga colorization using the MCV2 ONNX model (576x832 CPU).

## How to Start

Run the server with Python:

```bash
cd colorize-demo
python app.py
```

Optional arguments:
- `--port 8080` (default port: 8080)
- `--host 127.0.0.1` (default host: 127.0.0.1)
- `--model <path_to_onnx>` (defaults to the pre-configured MCV2 ONNX model path)

Then open your browser to:
`http://127.0.0.1:8080/`

## Features

- **Folder Browser**: Browse local folders containing `.png`, `.jpg`, `.webp` manga pages. Pre-seeded with sample pages and a color reference in `colorize-demo/samples/`.
- **Side-by-Side Comparison**: Compare No-Hint autonomous colorization against Reference-guided colorization with 1 click.
- **Reference Image Guidance**: Uses flattened color hints (median + Gaussian blur) with a slider to control ink ghost elimination.
- **Manual Hint Painting**: Paint color splotches directly onto the manga page preview canvas with custom colors and brush sizes. Strokes are normalized and letterboxed into the model frame.
- **Instant Result Caching**: Re-running the exact same target + mode + reference + parameters returns results instantaneously from cache.
- **Asynchronous Worker Queue**: Inferences run in a background worker thread so the web UI stays fully responsive.
