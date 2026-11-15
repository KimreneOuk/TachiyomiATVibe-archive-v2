# On-demand model downloads — planning docs (debug branch)

**This is a scratch/debugging branch. Plans only — no code changes. Expect mess.**
Base: `main` @ `2c096416` (the revision every claim below was verified against).

## Contents

| File | What it is |
|---|---|
| [plan.html](plan.html) | Full plan: goal, code-verified model map (routing per pipeline stage), distribution reality check, proposed backend architecture, UI/UX direction, tradeoffs, open decisions D1–D8 |
| [ui-mockups/](ui-mockups/) | Exploratory UI boards (Midnight Dusk style). Start with `boards/feature-inline-settings.html` and `boards/feature-reader-prompt.html` — these reflect the settled direction |

The boards are plain HTML; open them locally or via any static server (they reference `assets/app.css` relatively).

## What this plan is

Today the first-run wizard downloads **nothing** and the normal build provisions **23 files ≈ 713 MB** into the APK. The goal: **essentials stay available by default; everything else becomes a user-visible download/delete decision**, managed inline beside each feature.

**Proposed essential set (user's call):** comic text & bubble detector (`detector-v4-s_int8.onnx`), panel detector, bubble segmenter, PaddleOCR ONNX trio.

### Facts that shape the design (all verified at 2c096416)

- Startup `OnnxModelStore.ensureModels()` requires the detector + the **MangaOCR quartet** (64.8 MB) unguarded for **every** user — even ML Kit/Paddle/non-Japanese. Making MangaOCR optional requires relaxing this init path; it cannot be done by just omitting files.
- Everything else already degrades gracefully when absent (panel → panel-less, segmenter → heuristic masking, Paddle det → ink-gap heuristic, LaMa/AOT → reader blocks non-FAST starts with the existing toast).
- **Distribution gap:** the converted `panel_int8` and `bubble_int8` essentials are build-time artifacts **published nowhere**; the 169.6 MB LiteRT detector overlay is untracked/unhashed. Rights determinations (AGPL treatment for the panel derivative, Manga109 terms for bubble) are unresolved — nothing is presumed.
- **Paddle routing:** the ONNX path hardcodes a QNN-GPU probe with labelled-CPU fallback; the LiteRT suite (7 files, all-or-nothing) is GPU-first with XNNPACK fallback. There is **no persisted user CPU/GPU selector** — the UI must not invent one. Paddle is never the default on any language.
- **FFDNet:** pref ON + asset missing ⇒ the colorize stage **fails loudly** (no silent bilateral fallback), and no route stamp is written on failure. Deletion flow must warn/reset that pref specifically.
- Dead weight in packaging: `aot.onnx` + `aot-512.onnx` (46 MB, production-unused) and an orphan `inference.json`.

### UI direction (settled)

- **Model controls live inline in the existing Translation settings sections**, beside the model they affect (OCR per-language rows, cleaning/inpainting tiles, Fumeto engine row, FFDNet toggle, upscaling status). No dedicated manager screen.
- **Reader overlay carries selections only** — no download/delete controls there. If a selected feature needs a missing model: contextual prompt (Download & continue / continue without / cancel). ML Kit is Play-services and never appears as a download item.
- Bundled-in-APK assets cannot be deleted to reclaim install size — only download-installed families are reclaimable. This tension is the core of D1/D4.

## Open decisions (details + recommendations in plan.html §8)

| # | Decision | Recommendation in plan |
|---|---|---|
| D1 | APK slimming vs bundled essentials | Keep 29 MB essentials bundled for v1 |
| D2 | Fate of the 169.6 MB LiteRT detector overlay | Publish as optional download, out of APK |
| D3 | Distribution host (+ go/no-go on rights determinations) | Needs owner's call |
| D4 | Deletability of essentials + rights for panel/bubble derivatives | Coupled to D1; rights unresolved |
| D5 | Behavior when a selected OCR model is absent | Coerce in settings, pre-flight sheet in reader |
| D6 | Fumeto (452 MB) trigger | Explicit download; reader sheet on first use |
| D7 | Delete-vs-active-session | Hard block + queue after |
| D8 | Dead-asset & manifest hygiene in same release | Yes |

## Proposed backend architecture (plan.html §5)

`ModelCatalog` (mirrors `models.manifest`) → `ModelInstallRepository` (disk → asset → absent resolution, keeps `.version` stamps) → `WorkManager` download job on the existing `AppUpdateDownloadJob` pattern (SHA-256 verify, tmp + atomic rename, Wi-Fi pref, disk guard, retry) → `ModelDeletionUseCase` (dependency + active-session checks) → `OnnxModelStore` refactor (per-family guards, unavailable-not-throw) → reader gating extending the existing `inpaintModelMissing()` pattern.
