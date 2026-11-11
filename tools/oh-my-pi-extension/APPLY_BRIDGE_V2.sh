#!/data/data/com.termux/files/usr/bin/bash
# SETUP SCRIPT - memasang ekstensi Agent Bridge V3 ke konfigurasi oh-my-pi di Termux.
# Nama berkas sengaja tetap APPLY_BRIDGE_V2.sh supaya tautan lama tidak putus;
# protokol yang dipasang adalah V3 (apiVersion 2026-11-08).
# usage: AGENTFEED_TOKEN=... bash tools/oh-my-pi-extension/APPLY_BRIDGE_V2.sh

set -e

TARGET_DIR="$HOME/.omp/agent/extensions/agentfeed"
ALT_DIR="$HOME/.omp/agent/extension/agentfeed"
mkdir -p "$TARGET_DIR" "$ALT_DIR"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cp "$SCRIPT_DIR/index.ts" "$TARGET_DIR/index.ts"
cp "$SCRIPT_DIR/index.ts" "$ALT_DIR/index.ts"

# Rute tulis /api/v1/trading/* menuntut header X-Agent-Token. Rute baca
# /api/v1/market/* bersifat publik. Token dibuat aplikasi dan tampil di
# Pengaturan; salin lewat env agar ekstensi bisa membacanya. Tanpa token,
# server menolak SEMUA permintaan trading (fail-closed), bukan menerima tanpa auth.
if [ -n "$AGENTFEED_TOKEN" ]; then
  printf '%s' "$AGENTFEED_TOKEN" > "$TARGET_DIR/agent-bridge-token.txt"
  cp "$TARGET_DIR/agent-bridge-token.txt" "$ALT_DIR/agent-bridge-token.txt"
  echo "Token bridge ditulis ke kedua direktori (agent-bridge-token.txt)"
else
  echo "Tanpa token: order dari OMP akan ditolak 401 sampai token dipasang."
  echo "  Ulangi dari Pengaturan app, atau jalankan:"
  echo "  AGENTFEED_TOKEN=<token> bash $0"
fi

echo "Ekstensi Agent Bridge V3 terpasang di:"
echo "  - $TARGET_DIR/index.ts"
echo "  - $ALT_DIR/index.ts"
echo "Data harga/candle/depth/aturan simbol kini diambil dari"
echo "  http://127.0.0.1:8765/api/v1/market/* (bukan proxy eksternal)."
echo "Untuk menguji koneksi bridge, jalankan:"
echo "  bash $SCRIPT_DIR/test-v2.sh"
