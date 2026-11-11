# AGENTS.md - aturan area `tools/`

Dibaca saat menyentuh `tools/`. Isi folder saat ini sengaja kecil: arsip riset
`data/`, `docs/`, `workplan2.md`, skrip replay CSV, dan dataset lama **sudah
dihapus** dan jangan dibuat ulang.

```
tools/
├── check_md_text.py          # pemeriksa kebersihan semua .md
├── oh-my-pi-extension/       # index.ts (ekstensi OMP), APPLY_BRIDGE_V2.sh, test-v2.sh
└── bridge-smoke/             # uji JVM AgentHttpServer dengan sumber produksi
```

## `check_md_text.py`

Memindai **seluruh worktree** (bukan `git ls-files`) supaya dokumen baru yang
belum di-stage tetap terperiksa. Memeriksa BOM, CRLF, UTF-8 rusak, aksara CJK,
mojibake, dan karakter area privat. Melewati `.git`, `.gradle`, `.kotlin`,
`build`, `node_modules`, `.idea`, `captures`. Keluar kode 1 bila ada temuan,
sehingga bisa dipakai di CI maupun pre-commit.

```sh
python3 tools/check_md_text.py                 # semua .md
python3 tools/check_md_text.py README.md       # berkas tertentu
```

## `oh-my-pi-extension/`

- `index.ts` adalah **konsumen** Agent Bridge. Semua harga, klines, depth,
  aturan simbol, dan biaya diambil dari `http://127.0.0.1:8765/api/v1/market/*`.
  **Dilarang** menambah host proxy eksternal atau angka tebakan.
- Token untuk rute tulis: env `AGENTFEED_TOKEN` atau
  `agent-bridge-token.txt`. Tanpa token, server menolak 401 (fail-closed).
- `APPLY_BRIDGE_V2.sh` menyalin `index.ts` ke kedua varian direktori OMP.
  `test-v2.sh` memeriksa health, capabilities, ticker, sinyal, dan push.
  Nama berkas dipertahankan demi kompatibilitas pemasangan meski protokolnya V3.
- Type-check tanpa dependensi OMP asli:

  ```sh
  tsc --noEmit --target ES2022 --lib ESNext,DOM --moduleResolution Bundler \
      --skipLibCheck --types '' index.ts   # bersama berkas stub modul OMP
  ```

  Stub diperlukan untuk `@oh-my-pi/pi-coding-agent`, `node:fs/promises`,
  `Bun`, dan `process`. Perubahan `index.ts` wajib lolos type-check ini.

## `bridge-smoke/`

- `build.gradle.kts` menyalin **apa adanya** berkas produksi murni
  (`AgentHttpServer.kt`, `AgentOutputModels.kt`, `AgentMarketModels.kt`,
  `AgentTradingModels.kt`) dari `app/src/main/java`. Versi dependensi dibaca
  dari `gradle/libs.versions.toml` aplikasi, jangan ditulis ulang di sini.
- Kelas yang bergantung Android/Room/coordinator diberi **stub** di
  `src/main/kotlin`: `Context`, `AppPreferences`, `AppLogManager`,
  `ScalpingMtfSnapshot`, `AgentOutputBridge`, `AgentMarketDataProvider`,
  `AgentTradingCommandHandler`. Stub hanya untuk kontrak HTTP; angkanya
  ditandai `SMOKE_FIXTURE` dan bukan data bursa.
- `smoke/Main.kt` menguji manifest, delapan endpoint data pasar, fail-closed
  401, endpoint lama, WebSocket, SSE, dan stop/restart.

```sh
cd tools/bridge-smoke && gradle run --no-daemon --console=plain
```

Bila `~/.gradle/init.gradle` pada mesin rusak, jalankan dengan
`GRADLE_USER_HOME=/tmp/gradle-home-smoke`. Itu masalah lingkungan, bukan repo.

## Larangan

- Membuat config replay kedua, dataset CSV, atau laporan `docs/exp/` baru.
- Menghapus stub smoke tanpa menggantinya - smoke harus tetap meng-compile
  **sumber produksi**, bukan salinan yang bisa melenceng.
- Meng-commit token, keystore, atau `.env`.
