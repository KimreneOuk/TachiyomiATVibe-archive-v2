#!/usr/bin/env bash
# Watchdog for the nvfp4 download: samples size every 30s.
# Exit 0 = reached ~complete size; Exit 2 = stalled (no growth for 60s+); Exit 3 = timeout.
set -u

FILE="C:/Users/ADMIN/Documents/Coding Related/tachiyomiATVIBE/manga_coloring_tool_2/ComfyUI_windows_portable/ComfyUI/models/diffusion_models/flux-2-klein-4b-nvfp4.safetensors"
TARGET=2400000000    # ~2.4e9 bytes, just under the full 2.46 GB decimal size
STALL_LIMIT=2        # consecutive samples without growth
LAST=-1; STILL=0; N=0

while [ $N -lt 90 ]; do   # hard stop after ~45 min
  N=$((N+1))
  SZ=$(stat -c %s "$FILE" 2>/dev/null || echo 0)
  echo "poll#$N size=$SZ"
  if [ "$SZ" -ge "$TARGET" ]; then
    echo "WATCHDOG_COMPLETE size=$SZ"
    exit 0
  fi
  if [ "$SZ" -eq "$LAST" ]; then
    STILL=$((STILL+1))
    if [ "$STILL" -ge "$STALL_LIMIT" ]; then
      echo "WATCHDOG_STALLED size=$SZ"
      exit 2
    fi
  else
    STILL=0
  fi
  LAST=$SZ
  sleep 30
done

echo "WATCHDOG_TIMEOUT size=$LAST"
exit 3
