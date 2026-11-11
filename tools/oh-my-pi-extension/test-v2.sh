#!/data/data/com.termux/files/usr/bin/bash
# Termux consumer - uji Agent Bridge V3 (HTTP + data pasar + WebSocket/SSE).
# Nama berkas sengaja tetap test-v2.sh supaya tautan lama tidak putus.
# Usage: bash tools/oh-my-pi-extension/test-v2.sh

BASE="http://127.0.0.1:8765"
TOKEN="${AGENTFEED_TOKEN:-}"
if [ -z "$TOKEN" ] && [ -f "$HOME/.omp/agent/extensions/agentfeed/agent-bridge-token.txt" ]; then
  TOKEN="$(cat "$HOME/.omp/agent/extensions/agentfeed/agent-bridge-token.txt")"
fi

show() {
  if command -v jq >/dev/null 2>&1; then jq .; else cat; fi
}

echo "=== nadi agentfeed - Agent Bridge V3 Test ==="

# Setup storage Termux hanya bila memang dibutuhkan jurnal; tidak wajib untuk uji HTTP.
if [ ! -d "$HOME/storage" ]; then
  echo "Menyiapkan Termux storage access..."
  termux-setup-storage
fi

echo ""
echo "[1] Health"
curl -s "$BASE/health" | show

echo ""
echo "[2] Capabilities (discovery)"
curl -s "$BASE/api/v1/capabilities" | show

echo ""
echo "[3] Waktu server"
curl -s "$BASE/api/v1/market/time" | show

echo ""
echo "[4] Ticker lokal (BTC_USDT)"
curl -s "$BASE/api/v1/market/ticker?symbol=BTC_USDT" | show

echo ""
echo "[5] Konteks trading lengkap (ticker+klines+depth+rules+fees)"
curl -s "$BASE/api/v1/market/context?symbol=BTC_USDT&interval=1m&limit=50" | show

echo ""
echo "[6] Sinyal terbaru (endpoint lama tetap ada)"
curl -s "$BASE/signals/latest" | show

echo ""
echo "[7] Status trading (butuh token; tanpa token harus 401)"
if [ -n "$TOKEN" ]; then
  curl -s -H "X-Agent-Token: $TOKEN" "$BASE/api/v1/trading/status" | show
  echo "Open order:"
  curl -s -H "X-Agent-Token: $TOKEN" "$BASE/api/v1/trading/orders" | show
else
  echo "Token tidak ada - lewati uji tulis. Cek fail-closed:"
  curl -s -o /dev/null -w "HTTP %{http_code} (harapannya 401)\n" "$BASE/api/v1/trading/status"
fi

echo ""
echo "[8] Push WebSocket / SSE (10 detik)..."
if command -v websocat >/dev/null 2>&1; then
  echo "Mendengarkan ws://127.0.0.1:8765/ws/signals selama 10 detik..."
  timeout 10 websocat ws://127.0.0.1:8765/ws/signals || true
else
  echo "websocat belum terpasang, menguji SSE via curl -N..."
  timeout 10 curl -N "$BASE/events" || true
fi

echo ""
echo "[9] Ekstensi OMP"
echo "Jalankan di CLI Termux, misalnya:"
echo "  omp --prompt 'get_market_context BTC_USDT, lalu get_bridge_trading_status'"
echo ""
echo "Catatan: bila sebuah snapshot mengembalikan available=false, angka nol"
echo "didalamnya BUKAN harga. Baca field error dan jangan mengarang data."
