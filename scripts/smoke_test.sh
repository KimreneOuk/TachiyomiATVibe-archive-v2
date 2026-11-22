#!/usr/bin/env bash
# Smoke-test runner for Manga Coloring Tool 2 (Ultra Fast / Balanced presets)
# Usage: smoke_test.sh "<preset_name>" <steps> <megapixels> <tag> [unet_name]
set -u

TOOL="C:/Users/ADMIN/Documents/Coding Related/tachiyomiATVIBE/manga_coloring_tool_2"
IMG="$TOOL/images/example1.png"
MODE="$1"; STEPS="$2"; MP="$3"; TAG="$4"; UNET="${5:-}"

rm -f "$TOOL/vram_$TAG.txt"

( while true; do
    nvidia-smi --query-gpu=memory.used --format=csv,noheader,nounits >> "$TOOL/vram_$TAG.txt" 2>/dev/null
    sleep 1
  done ) &
SAMPLER_PID=$!

START=$(date +%s)
curl -s -N --max-time 2400 -X POST http://127.0.0.1:7860/api/generate \
  -F "files=@$IMG;filename=example1.png" \
  -F "prompt=colour, high-quality, high-definition, high-resolution, accuracy, consistency" \
  -F "use_ref=false" \
  -F "steps=$STEPS" \
  -F "megapixels=$MP" \
  -F "preset_name=$MODE" \
  -F "folder_name=smoke_test_$TAG" \
  -F "output_format=png" \
  -F "unet_name=$UNET" \
  > "$TOOL/sse_$TAG.txt" 2>&1
CURL_EXIT=$?
END=$(date +%s)

kill "$SAMPLER_PID" 2>/dev/null

echo "$((END - START))" > "$TOOL/timing_$TAG.txt"
echo "CURL_EXIT=$CURL_EXIT SECS=$((END - START)) TAG=$TAG"
