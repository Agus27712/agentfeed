/**
 * nadi agentfeed — Agent Bridge V3, extension untuk OMP.
 *
 * Path (OMP):
 *   ~/.omp/agent/extensions/agentfeed/index.ts
 *
 * Syarat app:
 *   Agent Bridge ON → http://127.0.0.1:8765 + ws://127.0.0.1:8765/ws/signals
 *
 * Sejak 2026-11-08 SELURUH data pasar (harga, klines, order book, aturan simbol,
 * biaya) diambil dari server LOKAL `/api/v1/market/*`, bukan lagi proxy eksternal.
 * Tujuannya: keputusan agent memakai angka yang identik dengan chart aplikasi.
 * Bila endpoint mengembalikan `available:false`, tool melaporkan `error` dan
 * TIDAK mengarang angka.
 *
 * Format: export default function (pi) — wajib di omp (bukan export const tools).
 */
import type { ExtensionAPI } from "@oh-my-pi/pi-coding-agent";
import { appendFile, mkdir, readFile } from "node:fs/promises";

type Signal = {
  ts: number;
  exchange: string;
  symbol: string;
  quote: string;
  mode: string;
  action: string;
  confidence: number;
  price: number;
  tp1?: number;
  tp2?: number;
  sl?: number;
  reason: string;
  type?: string;
};

const BRIDGE_HTTP = "http://127.0.0.1:8765";
const BRIDGE_WS = "ws://127.0.0.1:8765/ws/signals";
const BRIDGE_WS_PORTFOLIO = "ws://127.0.0.1:8765/ws/portfolio";

/**
 * Token Agent Bridge (2026-11-06). Semua rute `/api/v1/trading/*` menuntut header
 * `X-Agent-Token` (fail-closed di sisi server). Sumber: env `AGENTFEED_TOKEN`
 * atau berkas `agent-bridge-token.txt` yang ditulis aplikasi saat pemasangan
 * ekstensi (OmpBridgeInstaller). Header token TIDAK dikirim ke rute baca publik.
 */
const TOKEN_FILE = "agent-bridge-token.txt";
const TOKEN_DIRS = ["extensions", "extension"]; // kedua varian nama direktori OMP
let tokenCache: string | null = null;

async function bridgeToken(): Promise<string> {
  if (tokenCache !== null) return tokenCache;
  const envToken = process.env.AGENTFEED_TOKEN;
  if (envToken && envToken.trim()) {
    tokenCache = envToken.trim();
    return tokenCache;
  }
  try {
    const fs = await import("node:fs/promises");
    const home = process.env.HOME || "/data/data/com.termux/files/home";
    for (const dir of TOKEN_DIRS) {
      const path = `${home}/.omp/agent/${dir}/agentfeed/${TOKEN_FILE}`;
      const raw = await fs.readFile(path, "utf8").catch(() => "");
      if (raw.trim()) {
        tokenCache = raw.trim();
        return tokenCache;
      }
    }
  } catch {
    // fs tak tersedia → tanpa token; server akan menolak order dengan 401.
  }
  tokenCache = "";
  return tokenCache;
}

let wsClient: WebSocket | null = null;
const lastSignals: Signal[] = [];

/**
 * Portofolio (2026-11-06): snapshot dikirim app lewat `/ws/portfolio` saat isinya
 * berubah (atau heartbeat 5 menit) — bukan tiap tick harga. Disimpan di sini supaya
 * tool lain bisa membacanya tanpa membuka koneksi baru.
 */
type PortfolioAsset = {
  symbol: string;
  free: number;
  locked: number;
  avgBuyPrice: number;
  currentPrice: number;
  pnlPercent: number;
  pnlValue: number;
};

type Portfolio = {
  ts: number;
  exchange: string;
  totalIdrValue: number;
  totalUsdtValue: number;
  assets: PortfolioAsset[];
  type?: string;
};

const lastPortfolios: Portfolio[] = [];
let portfolioWs: WebSocket | null = null;

function pushPortfolio(p: Portfolio) {
  lastPortfolios.push(p);
  if (lastPortfolios.length > 50) lastPortfolios.shift();
}

function connectPortfolioWs(logger?: {
  info?: (m: string, ...a: unknown[]) => void;
  debug?: (m: string, ...a: unknown[]) => void;
}) {
  if (portfolioWs && portfolioWs.readyState === WebSocket.OPEN) return portfolioWs;
  try {
    portfolioWs = new WebSocket(BRIDGE_WS_PORTFOLIO);
    portfolioWs.onmessage = (ev) => {
      try {
        const p = JSON.parse(String(ev.data)) as Portfolio;
        pushPortfolio(p);
        logger?.debug?.(
          `[agentfeed] portofolio ${p.exchange}: ${p.assets.length} aset, P&L ${p.totalUsdtValue.toFixed(2)} USDT`
        );
      } catch {
        /* frame non-JSON diabaikan */
      }
    };
    portfolioWs.onerror = () => {
      portfolioWs = null;
    };
    portfolioWs.onclose = () => {
      portfolioWs = null;
      setTimeout(() => connectPortfolioWs(logger), 3000);
    };
  } catch {
    portfolioWs = null;
  }
  return portfolioWs;
}

function pushSignal(sig: Signal) {
  lastSignals.push(sig);
  if (lastSignals.length > 100) lastSignals.shift();
}

function connectWs(logger?: { info?: (m: string, ...a: unknown[]) => void; debug?: (m: string, ...a: unknown[]) => void }) {
  if (wsClient && wsClient.readyState === WebSocket.OPEN) return wsClient;
  try {
    wsClient = new WebSocket(BRIDGE_WS);
    wsClient.onmessage = (ev) => {
      try {
        const sig = JSON.parse(String(ev.data)) as Signal;
        pushSignal(sig);
        logger?.debug?.(
          `[agentfeed] ${sig.symbol} ${sig.action} ${sig.confidence}% @ ${sig.price}`
        );
      } catch {
        /* frame non-JSON (ping app-level) diabaikan */
      }
    };
    wsClient.onerror = () => {
      wsClient = null;
    };
    wsClient.onclose = () => {
      wsClient = null;
      setTimeout(() => connectWs(logger), 3000);
    };
  } catch {
    wsClient = null;
  }
  return wsClient;
}

async function fetchLatest(): Promise<
  | { source: string; signal: Signal; summary: string; actionable: boolean; ageSec: number | null }
  | { error: string }
> {
  if (lastSignals.length > 0) {
    const s = lastSignals[lastSignals.length - 1];
    const ageSec = Math.round((Date.now() - s.ts) / 1000);
    return {
      source: "websocket_push",
      signal: s,
      summary: `${s.symbol} ${s.action} ${s.confidence}% @ ${s.price} — ${s.reason} (usia sinyal: ${ageSec < 0 ? "?" : ageSec}s)`,
      actionable: s.confidence >= 70 && s.action !== "HOLD",
      ageSec,
    };
  }
  try {
    const res = await fetch(`${BRIDGE_HTTP}/signals/latest`, {
      signal: AbortSignal.timeout(3000),
    });
    if (!res.ok) return { error: "No signal yet (bridge ON tapi belum ada EMIT non-HOLD)" };
    const data = (await res.json()) as Signal;
    pushSignal(data);
    const ageSec = Math.round((Date.now() - data.ts) / 1000);
    return {
      source: "http_polling",
      signal: data,
      summary: `${data.symbol} ${data.action} ${data.confidence}% @ ${data.price} — ${data.reason} (usia sinyal: ${ageSec < 0 ? "?" : ageSec}s)`,
      actionable: data.confidence >= 70 && data.action !== "HOLD",
      ageSec,
    };
  } catch (e) {
    return {
      error: `Bridge mati / tidak reachable di ${BRIDGE_HTTP}. App nadi agentfeed jalan + sakelar Agent Bridge ON? ${e}`,
    };
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Harga + biaya + pemantauan posisi
//
// Semua tarif di bawah adalah cermin `agu.analys.config.FeeSchedule` (sumber
// kebenaran app), jadi target harga di sini identik dengan mesin simulasi.
// ─────────────────────────────────────────────────────────────────────────────

/** Kuotasi yang didukung aplikasi: Tokocrypto hanya USDT dan IDR. */
const QUOTE_ASSETS = ["USDT", "IDR"];

type SymbolParts = { base: string; quote: string; pair: string; display: string };
type FeeRates = { buy: number; sell: number; quote: string; kind: "USDT" | "IDR" };

function splitSymbol(symbol: string): SymbolParts {
  const clean = symbol.trim().toUpperCase().replace(/[^A-Z0-9]/g, "");
  for (const quote of QUOTE_ASSETS) {
    if (clean.length > quote.length && clean.endsWith(quote)) {
      const base = clean.slice(0, -quote.length);
      return { base, quote, pair: clean, display: `${base}_${quote}` };
    }
  }
  throw new Error(`Simbol tidak dikenali: ${symbol} (format BASEQUOTE, hanya USDT/IDR, mis. BTCUSDT)`);
}

/**
 * Tarif all-in per sisi (pecahan), cermin `FeeSchedule` di aplikasi.
 * Angka resmi juga diterbitkan server lewat `GET /api/v1/market/context` (field
 * `fees`) dan tool `get_market_context`. Mirror sinkron ini dipakai hanya untuk
 * kalkulasi jurnal/watch lokal yang harus tetap deterministik. Tidak ada lagi
 * cabang bursa kedua: aplikasi ini Tokocrypto-only.
 */
function feesFor(quote: string): FeeRates {
  const q = quote.toUpperCase();
  if (q === "USDT") return { buy: 0.004044, sell: 0.004044, quote: q, kind: "USDT" };
  // Tokocrypto IDR (taker): beli bebas PPh, jual kena PPh 0,21% + ICEx.
  return { buy: 0.002222, sell: 0.004322, quote: q, kind: "IDR" };
}

// ─────────────────────────────────────────────────────────────────────────────
// Jurnal trade paper (2026-11-07)
//
// Setiap trade paper — ditutup watcher maupun SELL manual — dicatat sebagai satu
// baris JSONL. Tiap baris adalah SNAPSHOT PENUH trade, bukan delta: pembaca cukup
// mengambil baris terakhir per `tradeId`. Jadi tulisan ganda, crash di tengah, atau
// baris yang tidak monoton tetap menghasilkan satu state yang konsisten.
//
// Kenapa MAE/MFE + σ saat entry ikut dicatat: win-rate saja menyesatkan. Stop 3%
// "menang 70%" hanya berarti targetnya jarang tersentuh dalam horizon uji; tanpa
// MAE (seberapa dalam posisi sempat nyungsep) dan σ, angka itu tidak bisa dipakai
// memutuskan stop yang benar. Lihat tool `paper_trade_journal`.
// ───────────────────────────────────────────────────────────────────────────────

const JOURNAL_DIR = `${process.env.HOME || "/data/data/com.termux/files/home"}/storage/shared/agentfeed/journal`;
const JOURNAL_FILE = `${JOURNAL_DIR}/paper_trades.jsonl`;
/** Cermin `DEFAULT_SLIPPAGE_PCT` app, per sisi. */
const JOURNAL_SLIPPAGE = 0.0008;

type JournalVol = { sd1mPct: number; atr1mPct: number; sigma60mPct: number };
type JournalSignal = { action: string; confidence: number; setup: string | null; rvol: number | null };

type PaperTrade = {
  tradeId: string;
  symbol: string;
  quote: string;
  exchange: string;
  openedAt: number;
  closedAt: number | null;
  entryPrice: number;
  exitPrice: number | null;
  quantity: number;
  costBasis: number;
  fees: FeeRates;
  slippagePct: number;
  buyOrderId?: string;
  sellOrderId?: string;
  targetNetPct?: number;
  stopLossPct?: number;
  exitReason?: string;
  pnlQuote?: number | null;
  pnlPct?: number | null;
  /** Ekstrem harga sebagai % dari entry (bukan net). */
  maePct?: number;
  mfePct?: number;
  /**
   * Ekstrem yang sama tapi sudah dikonversi ke % net terhadap basis biaya.
   * Ini yang dibandingkan dengan `pnlPct` — harga% dan net% berbeda ~0.97%
   * (biaya round-trip), jadi mencampurnya membuat "left on table" palsu.
   */
  maeNetPct?: number;
  mfeNetPct?: number;
  ticks?: number;
  elapsedSec?: number;
  vol?: JournalVol | null;
  signal?: JournalSignal | null;
  /** Alasan entry (dari BUY). */
  note?: string;
  /** Alasan exit (dari SELL) — jangan menimpa catatan entry. */
  exitNote?: string;
};

function newTradeId(symbol: string): string {
  const base = symbol.replace(/[^A-Z0-9]/gi, "").slice(0, 12).toUpperCase();
  return `${Date.now()}-${base}-${Math.random().toString(36).slice(2, 6)}`;
}

async function journalAppend(trade: PaperTrade): Promise<void> {
  await mkdir(JOURNAL_DIR, { recursive: true });
  await appendFile(JOURNAL_FILE, `${JSON.stringify(trade)}\n`, "utf8");
}

/** Semua trade, baris terakhir per `tradeId` menang. Baris rusak diabaikan. */
async function journalLoad(): Promise<PaperTrade[]> {
  const raw = await readFile(JOURNAL_FILE, "utf8").catch(() => "");
  const byId = new Map<string, PaperTrade>();
  for (const line of raw.split("\n")) {
    if (!line.trim()) continue;
    try {
      const t = JSON.parse(line) as PaperTrade;
      if (t?.tradeId) byId.set(t.tradeId, t);
    } catch {
      /* tulis terpotong: lewati */
    }
  }
  return [...byId.values()];
}

/** Trade yang masih terbuka untuk pair+exchange tertentu (dipakai SELL manual). */
async function journalFindOpen(symbol: string, exchange: string): Promise<PaperTrade | undefined> {
  const parts = splitSymbol(symbol);
  const ex = exchange.toUpperCase();
  const all = await journalLoad();
  return all
    .filter((t) => t.closedAt == null && t.symbol === parts.display && t.exchange === ex)
    .sort((a, b) => b.openedAt - a.openedAt)[0];
}

type JournalStats = {
  n: number;
  wins: number;
  winRatePct: number | null;
  sumPnl: number;
  avgPnlPct: number | null;
  medianPnlPct: number | null;
  avgMaePct: number | null;
  avgMfePct: number | null;
  /** Versi net dari MAE/MFE — sebanding dengan `pnlPct`. */
  avgMaeNetPct: number | null;
  avgMfeNetPct: number | null;
  /** MFE net rata-rata yang tidak pernah tertangkap exit: potensi yang ditinggalkan. */
  avgLeftOnTablePct: number | null;
  avgHoldSec: number | null;
  byExitReason: Record<string, { n: number; avgPnlPct: number | null; sumPnl: number }>;
  bySymbol: Record<string, { n: number; winRatePct: number | null; avgPnlPct: number | null; sumPnl: number }>;
};

const meanOf = (xs: number[]): number | null =>
  xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : null;

function medianOf(xs: number[]): number | null {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const mid = s.length >> 1;
  return s.length % 2 ? s[mid] : (s[mid - 1] + s[mid]) / 2;
}

/** Konversi % harga ke % net terhadap basis biaya trade — satuan yang bisa dibandingkan. */
function netPctAt(trade: PaperTrade, pricePct: number): number | null {
  if (!(trade.costBasis > 0) || !(trade.entryPrice > 0)) return null;
  const slip = trade.slippagePct / 100;
  const gross = trade.quantity * trade.entryPrice * (1 + pricePct / 100);
  return ((gross * (1 - slip) * (1 - trade.fees.sell) - trade.costBasis) / trade.costBasis) * 100;
}

/**
 * Agregat jurnal. `avgLeftOnTablePct` = MFE(net) − realized(net): seberapa jauh
 * harga sempat melewati harga exit, dalam satuan yang sama dengan PnL. Kalau angka
 * ini besar dan konsisten, target terlalu jauh atau exit terlalu lambat.
 */

function journalSummary(trades: PaperTrade[]): JournalStats {
  const closed = trades.filter((t) => t.closedAt != null && t.pnlPct != null);
  const pnls = closed.map((t) => t.pnlPct as number);
  const wins = pnls.filter((p) => p > 0).length;
  const byExitReason: JournalStats["byExitReason"] = {};
  const bySymbol: JournalStats["bySymbol"] = {};
  for (const t of closed) {
    const reason = t.exitReason ?? "UNKNOWN";
    const r = (byExitReason[reason] ??= { n: 0, avgPnlPct: null, sumPnl: 0 });
    r.n += 1;
    r.sumPnl += t.pnlQuote ?? 0;
    const sym = (bySymbol[t.symbol] ??= { n: 0, winRatePct: null, avgPnlPct: null, sumPnl: 0 });
    sym.n += 1;
    sym.sumPnl += t.pnlQuote ?? 0;
  }
  for (const [reason, r] of Object.entries(byExitReason)) {
    const xs = closed.filter((t) => (t.exitReason ?? "UNKNOWN") === reason).map((t) => t.pnlPct as number);
    r.avgPnlPct = meanOf(xs);
  }
  for (const [sym, r] of Object.entries(bySymbol)) {
    const xs = closed.filter((t) => t.symbol === sym).map((t) => t.pnlPct as number);
    r.avgPnlPct = meanOf(xs);
    r.winRatePct = xs.length ? (xs.filter((p) => p > 0).length / xs.length) * 100 : null;
  }
  const left = closed
    .map((t) => {
      const mfeNet = t.mfeNetPct ?? (t.mfePct != null ? netPctAt(t, t.mfePct) : null);
      return mfeNet == null || t.pnlPct == null ? null : mfeNet - t.pnlPct;
    })
    .filter((v): v is number => v != null);
  return {
    n: closed.length,
    wins,
    winRatePct: pnls.length ? (wins / pnls.length) * 100 : null,
    sumPnl: closed.reduce((a, t) => a + (t.pnlQuote ?? 0), 0),
    avgPnlPct: meanOf(pnls),
    medianPnlPct: medianOf(pnls),
    avgMaePct: meanOf(closed.map((t) => t.maePct).filter((v): v is number => v != null)),
    avgMfePct: meanOf(closed.map((t) => t.mfePct).filter((v): v is number => v != null)),
    avgMaeNetPct: meanOf(
      closed
        .map((t) => t.maeNetPct ?? (t.maePct != null ? netPctAt(t, t.maePct) : null))
        .filter((v): v is number => v != null)
    ),
    avgMfeNetPct: meanOf(
      closed
        .map((t) => t.mfeNetPct ?? (t.mfePct != null ? netPctAt(t, t.mfePct) : null))
        .filter((v): v is number => v != null)
    ),
    avgLeftOnTablePct: meanOf(left),
    avgHoldSec: meanOf(closed.map((t) => t.elapsedSec).filter((v): v is number => v != null)),
    byExitReason,
    bySymbol,
  };
}

type AgentCandle = {
  openTime: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
  closed?: boolean;
};

/**
 * σ/ATR 1m dari 60 bar terakhir, diambil dari `GET /api/v1/market/klines`
 * di server bridge. Bila `available:false` atau candle kurang dari 20, hasilnya
 * `null` — jurnal tidak boleh mengarang angka volatilitas.
 */
async function volSnapshot(symbol: string, timeoutMs = 8000): Promise<JournalVol | null> {
  const { pair } = splitSymbol(symbol);
  try {
    const res = await fetch(
      `${BRIDGE_HTTP}/api/v1/market/klines?symbol=${encodeURIComponent(pair)}&interval=1m&limit=60`,
      { signal: AbortSignal.timeout(timeoutMs) }
    );
    const body = (await res.json()) as {
      available?: boolean;
      candles?: AgentCandle[];
      error?: string;
    };
    if (!body.available || !Array.isArray(body.candles) || body.candles.length < 20) return null;
    const rows = body.candles;
    const closes = rows.map((c) => Number(c.close)).filter((n) => Number.isFinite(n) && n > 0);
    if (closes.length < 20) return null;
    const rets: number[] = [];
    for (let i = 1; i < closes.length; i++) rets.push(closes[i] / closes[i - 1] - 1);
    const mean = rets.reduce((a, b) => a + b, 0) / rets.length;
    const varr = rets.reduce((a, b) => a + (b - mean) ** 2, 0) / Math.max(1, rets.length - 1);
    const sd1m = Math.sqrt(varr) * 100;
    const trs: number[] = [];
    for (let i = 1; i < rows.length; i++) {
      const hi = Number(rows[i].high);
      const lo = Number(rows[i].low);
      const prev = closes[Math.max(0, i - 1)];
      trs.push(Math.max(hi - lo, Math.abs(hi - prev), Math.abs(lo - prev)));
    }
    const atr = trs.reduce((a, b) => a + b, 0) / trs.length;
    const last = closes[closes.length - 1];
    return { sd1mPct: sd1m, atr1mPct: (atr / last) * 100, sigma60mPct: sd1m * Math.sqrt(60) };
  } catch {
    return null;
  }
}

/** Sinyal engine terakhir untuk pair ini, hanya kalau masih segar (<15 menit). */
function signalContext(symbol: string): JournalSignal | null {
  const { display } = splitSymbol(symbol);
  for (let i = lastSignals.length - 1; i >= 0; i--) {
    const s = lastSignals[i];
    if (s.symbol !== display) continue;
    if (Date.now() - s.ts > 15 * 60 * 1000) return null;
    const tech = (s as Signal & { technical?: Record<string, unknown> }).technical ?? {};
    const rvol = /RVOL\s*([0-9.]+)x/.exec(s.reason ?? "");
    return {
      action: s.action,
      confidence: s.confidence,
      setup: typeof tech.scalpingSetup === "string" ? tech.scalpingSetup : null,
      rvol: rvol ? Number(rvol[1]) : null,
    };
  }
  return null;
}

/** Konteks jurnal yang ditempelkan ke order (diisi pemanggil, bukan user). */
type JournalCtx = {
  targetNetPct?: number;
  stopLossPct?: number;
  maePct?: number;
  mfePct?: number;
  ticks?: number;
  elapsedSec?: number;
  exitReason?: string;
  tradeId?: string;
  entryPrice?: number;
  /** Catatan bebas: dasar entry, setup, alasan keluar. */
  note?: string;
};

/**
 * Catat order paper yang sukses ke jurnal.
 *
 * BUY → buat trade baru (`closedAt: null`). SELL → tutup trade terbuka yang cocok,
 * diidentifikasi lewat `tradeId` kalau pemanggil tahu, kalau tidak lewat pair+exchange.
 * Tidak pernah melempar: jurnal tidak boleh menggagalkan order yang sudah fill.
 */
async function recordPaperTrade(
  args: OrderArgs,
  res: OrderResult
): Promise<{ tradeId?: string; error?: string; note?: string }> {
  try {
    const parts = splitSymbol(args.symbol);
    const fees = feesFor(parts.quote);
    const slip = JOURNAL_SLIPPAGE;
    if (args.side === "BUY") {
      const qty = Number(res.quantity ?? res.executedQty ?? 0);
      const entry = args.journal?.entryPrice ?? Number(res.price ?? 0);
      if (!(qty > 0) || !(entry > 0)) {
        return { note: "qty/harga BUY tidak valid; trade tidak dijurnal" };
      }
      const tradeId = newTradeId(parts.display);
      await journalAppend({
        tradeId,
        symbol: parts.display,
        quote: parts.quote,
        exchange: args.exchange.toUpperCase(),
        openedAt: Number(res.timestamp ?? Date.now()),
        closedAt: null,
        entryPrice: entry,
        exitPrice: null,
        quantity: qty,
        costBasis: qty * entry * (1 + slip) * (1 + fees.buy),
        fees,
        slippagePct: slip * 100,
        buyOrderId: res.orderId,
        targetNetPct: args.journal?.targetNetPct,
        stopLossPct: args.journal?.stopLossPct,
        note: args.journal?.note,
        vol: await volSnapshot(args.symbol),
        signal: signalContext(args.symbol),
      });
      return { tradeId };
    }
    const open = args.journal?.tradeId
      ? (await journalLoad()).find((t) => t.tradeId === args.journal?.tradeId)
      : await journalFindOpen(args.symbol, args.exchange);
    const qty = Number(res.quantity ?? res.executedQty ?? 0);
    const exit = Number(res.price ?? 0);
    if (!open) {
      return { note: `tidak ada trade ${parts.display} terbuka di jurnal; exit tidak bisa dihitung PnL-nya` };
    }
    if (!(qty > 0) || !(exit > 0)) {
      return { tradeId: open.tradeId, note: "qty/harga SELL tidak valid; trade dibiarkan terbuka" };
    }
    // SELL parsial: laba dihitung untuk bagian yang terjual saja, dan sisa posisi
    // TETAP terbuka sebagai trade baru — kalau ditutup, sisa koin jadi tak terlihat
    // di jurnal padahal masih dipegang.
    const sold = Math.min(qty, open.quantity);
    if (!(sold > 0)) return { tradeId: open.tradeId, note: "qty SELL nol; trade dibiarkan terbuka" };
    const share = sold / open.quantity;
    const basis = open.costBasis * share;
    const netQuote = sold * exit * (1 - slip) * (1 - fees.sell);
    const pnlQuote = netQuote - basis;
    const maePct = args.journal?.maePct ?? open.maePct;
    const mfePct = args.journal?.mfePct ?? open.mfePct;
    const closedTrade: PaperTrade = { ...open, quantity: sold, costBasis: basis };
    await journalAppend({
      ...closedTrade,
      closedAt: Number(res.timestamp ?? Date.now()),
      exitPrice: exit,
      sellOrderId: res.orderId,
      exitReason: args.journal?.exitReason ?? "MANUAL",
      pnlQuote,
      pnlPct: basis > 0 ? (pnlQuote / basis) * 100 : null,
      maePct,
      mfePct,
      maeNetPct: maePct != null ? (netPctAt(closedTrade, maePct) ?? undefined) : undefined,
      mfeNetPct: mfePct != null ? (netPctAt(closedTrade, mfePct) ?? undefined) : undefined,
      ticks: args.journal?.ticks ?? open.ticks,
      elapsedSec: args.journal?.elapsedSec ?? open.elapsedSec,
      exitNote: args.journal?.note,
    });
    const residual = open.quantity - sold;
    if (residual > 0) {
      const rest = newTradeId(parts.display);
      await journalAppend({
        ...open,
        tradeId: rest,
        quantity: residual,
        costBasis: open.costBasis - basis,
        note: `${open.note ?? ""} (sisa dari ${open.tradeId}, terjual ${sold})`.trim(),
      });
      return {
        tradeId: open.tradeId,
        note: `SELL parsial ${sold}/${open.quantity}; sisa ${residual} dilanjutkan sebagai ${rest}`,
      };
    }
    return { tradeId: open.tradeId };
  } catch (e) {
    return { error: `jurnal gagal ditulis: ${e}` };
  }
}

/**
 * Harga pasar mentah terakhir untuk pair apa pun, SELALU dari server bridge
 * lokal (`GET /api/v1/market/ticker`). Tidak ada lagi proxy eksternal: harga
 * yang dipakai agent identik dengan yang dipakai chart aplikasi.
 *
 * `available:false` atau `error` dari server diteruskan sebagai `null`; pemanggil
 * (watch/order) sudah punya jalur "harga tidak tersedia" dan tidak mengarang angka.
 */
async function fetchPrice(symbol: string, timeoutMs = 8000): Promise<{ price: number; source: string } | null> {
  const { pair } = splitSymbol(symbol);
  try {
    const res = await fetch(
      `${BRIDGE_HTTP}/api/v1/market/ticker?symbol=${encodeURIComponent(pair)}`,
      { signal: AbortSignal.timeout(timeoutMs) }
    );
    const body = (await res.json()) as {
      available?: boolean;
      price?: number;
      source?: string;
      error?: string;
    };
    const price = Number(body.price);
    if (!body.available || !Number.isFinite(price) || price <= 0) return null;
    return { price, source: body.source ?? "local-bridge" };
  } catch {
    return null;
  }
}

type OrderResult = {
  success: boolean;
  message?: string;
  orderId?: string;
  price?: number;
  quantity?: number;
  status?: string;
  executedQty?: number;
  isSimulation?: boolean;
  symbol?: string;
  /** Epoch ms dari bridge saat order dieksekusi. */
  timestamp?: number;
  /** Hasil penulisan jurnal untuk order paper; kosong untuk akun riil. */
  journal?: { tradeId?: string; error?: string; note?: string };
};

type OrderArgs = {
  side: "BUY" | "SELL";
  symbol: string;
  quantity?: number;
  quoteAmount?: number;
  price?: number;
  autoLimitTp1?: number;
  autoLimitTp2?: number;
  isSimulation: boolean;
  exchange: string;
  /** Metadata jurnal; TIDAK dikirim ke bridge (body disusun eksplisit di placeOrder). */
  journal?: JournalCtx;
};
/**
 * Kirim order ke bridge.
 *
 * `side` wajib ada — bridge men-deserialize `AgentTradeRequest` dan menolak
 * payload tanpa field itu. `price` sengaja dikirim eksplisit: kalau 0, bridge
 * memakai cache harga app yang bisa tertinggal jauh dari ticker, sehingga fill
 * terjadi di harga yang tidak pernah kita lihat.
 */
async function placeOrder(args: OrderArgs, timeoutMs = 25000): Promise<OrderResult> {
  const path = args.side === "BUY" ? "buy" : "sell";
  const body = {
    side: args.side,
    symbol: args.symbol,
    ...(args.quantity != null ? { quantity: args.quantity } : {}),
    ...(args.quoteAmount != null ? { quoteAmount: args.quoteAmount } : {}),
    price: args.price ?? 0,
    ...(args.autoLimitTp1 != null ? { autoLimitTp1: args.autoLimitTp1 } : {}),
    ...(args.autoLimitTp2 != null ? { autoLimitTp2: args.autoLimitTp2 } : {}),
    isSimulation: args.isSimulation,
    exchange: args.exchange,
  };
  try {
    const token = await bridgeToken();
    const res = await fetch(`${BRIDGE_HTTP}/api/v1/trading/${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(token ? { "X-Agent-Token": token } : {}) },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (res.status === 401) {
      return { success: false, message: `Ditolak 401: token bridge salah/belum diinstal (${TOKEN_FILE} atau env AGENTFEED_TOKEN). Jalankan ulang pemasangan ekstensi dari Pengaturan app.` };
    }
    const data = (await res.json()) as OrderResult;
    // Paper-only, best-effort: jurnal tidak boleh menggagalkan order yang sudah fill.
    if (data.success && args.isSimulation) data.journal = await recordPaperTrade(args, data);
    return data;
  } catch (e) {
    return { success: false, message: `Gagal kirim order ${args.side}: ${e}` };
  }
}

type WatchOpts = {
  symbol: string;
  quantity: number;
  /** Harga pasar mentah saat entry (belum termasuk slippage). */
  entryPrice: number;
  /** Target profit net setelah semua biaya, dalam persen dari biaya pokok. */
  targetNetPct: number;
  /** Cut loss otomatis; kosong/0 = mati. */
  stopLossPct?: number;
  /** Asumsi slippage per sisi (%), selaras `DEFAULT_SLIPPAGE_PCT` app. */
  slippagePct?: number;
  pollMs?: number;
  maxWaitSec?: number;
  isSimulation: boolean;
  exchange: string;
  onTick?: (tick: { elapsedSec: number; price: number; pnlPct: number; targetPrice: number; stopPrice: number | null }) => void;
  /** Batalkan pemantauan (posisi dibiarkan terbuka, hasil `status: "open"`). */
  signal?: AbortSignal;
  /** Trade terbuka di jurnal yang mau ditutup; kosong = cari otomatis per pair. */
  tradeId?: string;
};

type WatchResult = {
  status: "closed" | "open" | "error";
  symbol: string;
  quantity: number;
  entryPrice: number;
  costBasis: number;
  fees: FeeRates;
  targetPrice: number;
  stopPrice: number | null;
  ticks: number;
  elapsedSec: number;
  lastPrice: number | null;
  lastPnlPct: number | null;
  priceSource: string | null;
  /** Drawdown maksimum dari harga entry (negatif), dalam %. */
  maePct: number;
  /** Kenaikan maksimum dari harga entry (positif), dalam %. */
  mfePct: number;
  sell?: OrderResult;
  realized?: { netQuote: number; pnl: number; pnlPct: number; exitPrice: number };
  /** tradeId jurnal yang tercatat saat penutupan; kosong kalau tidak ada yang cocok. */
  tradeId?: string | null;
  journalError?: string;
  message: string;
};

/** Ambil qty yang benar-benar tersedia dari pesan penolakan dust. */
function availableFromMessage(message: string | undefined): number | null {
  if (!message) return null;
  const m = /Tersedia:\s*([0-9.eE+-]+)/.exec(message);
  if (!m) return null;
  const v = Number(m[1]);
  return Number.isFinite(v) && v > 0 ? v : null;
}

/**
 * Pantau posisi sampai target net (atau stop loss) tercapai, lalu SELL market.
 *
 * `maxWaitSec` membatasi satu panggilan; kalau waktu habis posisi dibiarkan
 * terbuka dan hasilnya `status: "open"` lengkap dengan qty + harga entry supaya
 * panggilan berikutnya bisa melanjutkan tanpa menghitung ulang biaya.
 */
async function watchPosition(opts: WatchOpts): Promise<WatchResult> {
  const parts = splitSymbol(opts.symbol);
  const fees = feesFor(parts.quote);
  const slip = Math.max(0, (opts.slippagePct ?? 0.08) / 100);
  const targetPct = opts.targetNetPct;
  const stopPct = opts.stopLossPct ?? 0;
  const pollMs = Math.max(2000, opts.pollMs ?? 15000);
  const maxWaitMs = Math.max(5000, (opts.maxWaitSec ?? 180) * 1000);

  // Lanjutan pemantauan: kalau trade ini sudah ada di jurnal, pakai angka jurnal
  // (qty, entry, basis, MAE/MFE) supaya PnL yang dilaporkan identik dengan yang
  // tercatat, bukan hasil hitung ulang dari parameter panggilan.
  const openTrade = opts.isSimulation
    ? opts.tradeId
      ? (await journalLoad()).find((t) => t.tradeId === opts.tradeId)
      : await journalFindOpen(opts.symbol, opts.exchange)
    : undefined;
  const entryPrice = openTrade?.entryPrice ?? opts.entryPrice;
  let qty = openTrade?.quantity ?? opts.quantity;
  const costBasis =
    openTrade?.costBasis ?? qty * entryPrice * (1 + slip) * (1 + fees.buy);
  const netPerUnitAt = (px: number) => px * (1 - slip) * (1 - fees.sell);
  const priceForNet = (net: number) => net / (qty * (1 - slip) * (1 - fees.sell));
  const targetPrice = priceForNet(costBasis * (1 + targetPct / 100));
  // Stop diukur dari HARGA, bukan dari basis net: posisi yang baru dibuka sudah
  // −(fee beli + fee jual + 2×slippage) di basis net, jadi stop berbasis basis
  // akan langsung terpicu di detik nol (stop > harga entry).
  const stopPrice = stopPct > 0 ? entryPrice * (1 - stopPct / 100) : null;

  const t0 = Date.now();
  let ticks = 0;
  let lastPrice: number | null = null;
  let lastPnlPct: number | null = null;
  let lastSource: string | null = null;
  let missStreak = 0;
  // MAE/MFE atas HARGA entry: bahan riset exit timing (target terlalu jauh? stop
  // terlalu lebar?), dan tidak bisa direkonstruksi setelah posisi ditutup. Nilai
  // awal diambil dari jurnal supaya panggilan lanjutan tidak mereset ekstrem.
  let maxPrice = entryPrice * (1 + (openTrade?.mfePct ?? 0) / 100);
  let minPrice = entryPrice * (1 + (openTrade?.maePct ?? 0) / 100);
  const excursion = () => ({
    maePct: (minPrice / entryPrice - 1) * 100,
    mfePct: (maxPrice / entryPrice - 1) * 100,
  });
  const { promise: aborted, resolve: onAbort } = Promise.withResolvers<void>();
  if (opts.signal?.aborted) onAbort();
  // Listener DOM menerima Event, sedangkan resolve Promise<void> bertanda tangan
  // `(void) => void`; bungkus supaya type-check ketat tetap lolos.
  else opts.signal?.addEventListener("abort", () => onAbort(), { once: true });
  // Bun.sleep tidak bisa dibatalkan; race dengan abort supaya tool berhenti saat user minta.
  const pause = () => Promise.race([Bun.sleep(pollMs).then(() => undefined), aborted]);
  const elapsedSec = () => Math.round((Date.now() - t0) / 1000);
  const snapshot = () => ({
    symbol: parts.display, quantity: qty, entryPrice, costBasis, fees,
    targetPrice, stopPrice, ticks, elapsedSec: elapsedSec(),
    lastPrice, lastPnlPct, priceSource: lastSource, ...excursion(),
  });

  while (Date.now() - t0 < maxWaitMs && !opts.signal?.aborted) {
    const q = await fetchPrice(opts.symbol);
    if (!q) {
      if (++missStreak >= 4) {
        return {
          ...snapshot(),
          status: "error",
          message: "Harga tidak tersedia dari server bridge lokal (/api/v1/market/ticker); posisi TIDAK ditutup.",
        };
      }
      await pause();
      continue;
    }
    missStreak = 0;
    ticks += 1;
    lastPrice = q.price;
    lastSource = q.source;
    if (q.price > maxPrice) maxPrice = q.price;
    if (q.price < minPrice) minPrice = q.price;
    const pnlPct = ((qty * netPerUnitAt(q.price) - costBasis) / costBasis) * 100;
    lastPnlPct = pnlPct;
    opts.onTick?.({ elapsedSec: elapsedSec(), price: q.price, pnlPct, targetPrice, stopPrice });

    const takeProfit = pnlPct >= targetPct;
    const cutLoss = stopPrice != null && q.price <= stopPrice;
    if (takeProfit || cutLoss) {
      const reason = takeProfit ? "TARGET" : "STOPLOSS";
      let sell: OrderResult | null = null;
      for (let attempt = 0; attempt < 3; attempt++) {
        // Kirim harga ticker yang baru dibaca: `price: 0` akan membuat bridge
        // memakai cache harga app yang bisa tertinggal ratusan poin.
        const fresh = attempt === 0 ? q.price : ((await fetchPrice(opts.symbol))?.price ?? q.price);
        sell = await placeOrder({
          side: "SELL", symbol: parts.display, quantity: qty, price: fresh,
          isSimulation: opts.isSimulation, exchange: opts.exchange,
          journal: {
            exitReason: reason, maePct: excursion().maePct, mfePct: excursion().mfePct,
            ticks, elapsedSec: elapsedSec(), tradeId: openTrade?.tradeId ?? opts.tradeId,
          },
        });
        if (sell.success) break;
        const available = availableFromMessage(sell.message);
        if (available == null) break;
        qty = Number((available * 0.999999).toPrecision(12));
        if (!(qty > 0)) break;
      }
      if (!sell || !sell.success) {
        return {
          ...snapshot(),
          status: "error",
          sell: sell ?? undefined,
          message: `${reason} kena tapi SELL gagal: ${sell?.message ?? "tanpa respons"}`,
        };
      }
      const exitRaw = Number(sell.price ?? q.price);
      const netQuote = qty * netPerUnitAt(exitRaw);
      const pnl = netQuote - costBasis;
      const pnlPctRealized = costBasis > 0 ? (pnl / costBasis) * 100 : 0;
      return {
        ...snapshot(),
        status: "closed",
        sell,
        realized: { netQuote, pnl, pnlPct: pnlPctRealized, exitPrice: exitRaw },
        tradeId: sell.journal?.tradeId ?? openTrade?.tradeId ?? null,
        journalError: sell.journal?.error,
        message: `${reason} tercapai (${pnlPct.toFixed(3)}% net @ ${q.price}) → SELL ${sell.status ?? ""} @ ${exitRaw} → PnL ${pnl.toFixed(5)} ${fees.quote} (${pnlPctRealized.toFixed(3)}%)`,
      };
    }

    await pause();
  }

  return {
    ...snapshot(),
    status: "open",
    message: opts.signal?.aborted
      ? `Pemantauan dibatalkan; posisi masih terbuka. Butuh harga ${targetPrice} untuk profit ${targetPct}% net.`
      : `Waktu tunggu habis; posisi masih terbuka. Butuh harga ${targetPrice} untuk profit ${targetPct}% net.`,
  };
}

function textResult(payload: unknown) {
  return {
    content: [{ type: "text" as const, text: JSON.stringify(payload, null, 2) }],
    details: payload,
  };
}

/** GET ke bridge dengan timeout; tidak pernah melempar, selalu laporkan status. */
async function bridgeGet(
  path: string,
  opts: { timeoutMs?: number; withToken?: boolean } = {}
): Promise<{ ok: boolean; status: number; body: unknown }> {
  try {
    const headers: Record<string, string> = {};
    if (opts.withToken) {
      const token = await bridgeToken();
      if (token) headers["X-Agent-Token"] = token;
    }
    const res = await fetch(`${BRIDGE_HTTP}${path}`, {
      headers,
      signal: AbortSignal.timeout(opts.timeoutMs ?? 9000),
    });
    return {
      ok: res.ok,
      status: res.status,
      body: await res.json().catch(() => ({ error: "Respons bridge bukan JSON" })),
    };
  } catch (e) {
    return { ok: false, status: 0, body: { error: `Bridge tidak reachable: ${e}` } };
  }
}

function marketEndpoint(r: { ok: boolean; status: number; body: unknown }) {
  if (r.status === 401) {
    return { success: false, error: `Ditolak 401: token bridge salah/belum diinstal (${TOKEN_FILE} atau env AGENTFEED_TOKEN).` };
  }
  return r.body;
}

export default function agentfeedBridge(pi: ExtensionAPI) {
  const z = pi.zod;

  connectWs(pi.logger);

  pi.on("session_start", async (_event, ctx) => {
    connectWs(pi.logger);
    ctx.ui.notify("nadi agentfeed Bridge V3 aktif (data pasar lokal :8765)", "info");
  });

  pi.registerTool({
    name: "get_latest_signal",
    label: "Latest signal",
    description:
      "Ambil sinyal terakhir dari agentfeed: cache WebSocket dulu, fallback HTTP /signals/latest",
    parameters: z.object({}),
    async execute(_toolCallId, _params) {
      return textResult(await fetchLatest());
    },
  });

  pi.registerTool({
    name: "subscribe_signals",
    label: "Subscribe signals",
    description:
      "Tunggu push WebSocket beberapa detik, kembalikan sinyal yang masuk di jendela itu (plus cache)",
    parameters: z.object({
      seconds: z
        .number()
        .min(3)
        .max(60)
        .default(15)
        .describe("Durasi tunggu detik (3–60)"),
    }),
    async execute(_toolCallId, params) {
      const seconds = params.seconds ?? 15;
      connectWs(pi.logger);
      const before = lastSignals.length;
      await new Promise((r) => setTimeout(r, seconds * 1000));
      const fresh = lastSignals.slice(before);
      return textResult({
        waited_seconds: seconds,
        ws_open: wsClient?.readyState === WebSocket.OPEN,
        new_count: fresh.length,
        cache_total: lastSignals.length,
        signals: fresh.length > 0 ? fresh : lastSignals.slice(-5),
        note:
          fresh.length === 0
            ? "Tidak ada push baru. Engine app harus EMIT (non-HOLD) saat jendela ini."
            : undefined,
      });
    },
  });

  pi.registerTool({
    name: "subscribe_portfolio",
    label: "Subscribe portfolio",
    description:
      "Terima push snapshot portofolio dari app (perubahan isi atau heartbeat 5 menit) selama jendela detik tertentu",
    parameters: z.object({
      seconds: z
        .number()
        .min(3)
        .max(120)
        .default(20)
        .describe("Durasi tunggu detik (3–120)"),
    }),
    async execute(_toolCallId, params) {
      const seconds = params.seconds ?? 20;
      connectPortfolioWs(pi.logger);
      const before = lastPortfolios.length;
      await new Promise((r) => setTimeout(r, seconds * 1000));
      const fresh = lastPortfolios.slice(before);
      return textResult({
        waited_seconds: seconds,
        ws_open: portfolioWs?.readyState === WebSocket.OPEN,
        new_count: fresh.length,
        cache_total: lastPortfolios.length,
        // Selalu sertakan snapshot terbaru (replay bus = 1) walau tidak ada perubahan baru.
        latest: fresh.length > 0 ? fresh[fresh.length - 1] : lastPortfolios[lastPortfolios.length - 1],
        updates: fresh,
        note:
          fresh.length === 0
            ? "Tidak ada push baru dalam jendela ini. App mengirim saat isi portofolio berubah atau heartbeat 5 menit; cek /portfolio untuk foto terkini."
            : undefined,
      });
    },
  });

  pi.registerTool({
    name: "get_signal_history",
    label: "Signal history",
    description: "Riwayat sinyal dari HTTP /signals/history",
    parameters: z.object({
      limit: z.number().min(1).max(100).default(20).describe("Jumlah maksimal"),
    }),
    async execute(_toolCallId, params) {
      const limit = params.limit ?? 20;
      try {
        const res = await fetch(`${BRIDGE_HTTP}/signals/history?limit=${limit}`, {
          signal: AbortSignal.timeout(5000),
        });
        const data = await res.json();
        return textResult({
          count: Array.isArray(data) ? data.length : 0,
          signals: data,
        });
      } catch (e) {
        return textResult({ error: `Gagal history: ${e}` });
      }
    },
  });

  pi.registerTool({
    name: "get_portfolio",
    label: "Portfolio",
    description: "Portofolio dari HTTP /portfolio bridge agentfeed",
    parameters: z.object({}),
    async execute() {
      try {
        const res = await fetch(`${BRIDGE_HTTP}/portfolio`, {
          signal: AbortSignal.timeout(5000),
        });
        if (!res.ok) return textResult({ error: "Belum ada data portofolio" });
        return textResult(await res.json());
      } catch (e) {
        return textResult({ error: `Gagal portfolio: ${e}` });
      }
    },
  });

  pi.registerTool({
    name: "bridge_health",
    label: "Bridge health",
    description: "Cek GET /health Agent Bridge V3 (service, apiVersion, running) di localhost:8765",
    parameters: z.object({}),
    async execute() {
      try {
        const res = await fetch(`${BRIDGE_HTTP}/health`, {
          signal: AbortSignal.timeout(3000),
        });
        const body = await res.text();
        return textResult({
          ok: res.ok,
          status: res.status,
          body,
          ws_open: wsClient?.readyState === WebSocket.OPEN,
          cache_signals: lastSignals.length,
        });
      } catch (e) {
        return textResult({ ok: false, error: String(e) });
      }
    },
  });

  pi.registerTool({
    name: "write_research_log",
    label: "Write research log",
    description: "Tulis log riset Markdown (bukan eksekusi order)",
    parameters: z.object({
      symbol: z.string(),
      analysis: z.string(),
      decision: z.enum(["BUY", "SELL", "HOLD", "WAIT"]),
    }),
    async execute(_toolCallId, params) {
      const { symbol, analysis, decision } = params;
      try {
        const fs = await import("node:fs/promises");
        const home = process.env.HOME || "/data/data/com.termux/files/home";
        const dir = `${home}/storage/shared/agentfeed/research`;
        await fs.mkdir(dir, { recursive: true });
        const path = `${dir}/${symbol}_${Date.now()}.md`;
        await fs.writeFile(
          path,
          `# ${symbol} — ${new Date().toISOString()}\n\nDecision: **${decision}**\n\n${analysis}\n`
        );
        return textResult({ saved: path });
      } catch (e) {
        return textResult({
          error: `Gagal tulis log (izin storage Termux?). ${e}`,
        });
      }
    },
  });

  // --- TRADING TOOLS (Agent-Assisted Trading) ---

  pi.registerTool({
    name: "get_bridge_trading_status",
    label: "Bridge trading status",
    description: "Cek kesiapan trading bridge: Mode Real, sesi PIN, saldo, versi API, dan URL data pasar lokal",
    parameters: z.object({}),
    async execute() {
      try {
        const token = await bridgeToken();
        const res = await fetch(`${BRIDGE_HTTP}/api/v1/trading/status`, {
          headers: token ? { "X-Agent-Token": token } : undefined,
          signal: AbortSignal.timeout(4000),
        });
        if (res.status === 401) {
          return textResult({ ok: false, error: `Ditolak 401: token bridge salah/belum diinstal (${TOKEN_FILE} atau env AGENTFEED_TOKEN).` });
        }
        const data = await res.json();
        return textResult(data);
      } catch (e) {
        return textResult({ ok: false, error: `Gagal membaca status trading bridge: ${e}` });
      }
    },
  });

  pi.registerTool({
    name: "place_buy_order",
    label: "Place buy order",
    description:
      "Kirim order BELI ke nadi agentfeed (Tokocrypto/Paper) atas instruksi pengguna terkonfirmasi. Format: quoteAmount (nominal USDT/IDR) atau quantity.",
    parameters: z.object({
      symbol: z.string().describe("Simbol pair, contoh: BTCUSDT, ETHUSDT, BTCIDR"),
      quoteAmount: z.number().optional().describe("Nominal beli dalam quote currency (contoh: 15 USDT atau 100000 IDR)"),
      quantity: z.number().optional().describe("Kuantitas koin jika tidak menentukan quoteAmount"),
      price: z.number().optional().describe("Harga acuan; kosong = ticker Tokocrypto terbaru (bukan cache app)"),
      autoLimitTp1: z.number().optional().describe("Harga Take Profit 1 opsional"),
      autoLimitTp2: z.number().optional().describe("Harga Take Profit 2 opsional"),
      isSimulation: z.boolean().optional().default(false).describe("True untuk simulasi paper trading, False untuk akun riil"),
      exchange: z.string().optional().default("TOKOCRYPTO").describe("Bursa target (default: TOKOCRYPTO)"),
      targetNetPct: z.number().optional().describe("Target profit net (%) — dicatat di jurnal untuk riset"),
      stopLossPct: z.number().optional().describe("Cut loss (%) — dicatat di jurnal untuk riset"),
      note: z.string().optional().describe("Alasan/dasar entry; disimpan di jurnal"),
    }),
    async execute(_toolCallId, params) {
      const price = params.price && params.price > 0 ? params.price : (await fetchPrice(params.symbol))?.price;
      const data = await placeOrder({
        side: "BUY",
        symbol: params.symbol,
        quoteAmount: params.quoteAmount,
        quantity: params.quantity,
        price,
        autoLimitTp1: params.autoLimitTp1,
        autoLimitTp2: params.autoLimitTp2,
        isSimulation: params.isSimulation ?? false,
        exchange: params.exchange ?? "TOKOCRYPTO",
        journal: {
          targetNetPct: params.targetNetPct,
          stopLossPct: params.stopLossPct,
          note: params.note,
        },
      });
      return textResult(data);
    },
  });

  pi.registerTool({
    name: "place_sell_order",
    label: "Place sell order",
    description:
      "Kirim order JUAL ke nadi agentfeed (Tokocrypto/Paper) atas instruksi pengguna terkonfirmasi. Tokocrypto SELL menggunakan MARKET.",
    parameters: z.object({
      symbol: z.string().describe("Simbol pair, contoh: BTCUSDT, ETHUSDT, BTCIDR"),
      quantity: z.number().describe("Jumlah aset koin yang ingin dijual"),
      price: z.number().optional().describe("Harga acuan; kosong = ticker Tokocrypto terbaru (bukan cache app)"),
      isSimulation: z.boolean().optional().default(false).describe("True untuk simulasi paper trading, False untuk akun riil"),
      exchange: z.string().optional().default("TOKOCRYPTO").describe("Bursa target (default: TOKOCRYPTO)"),
      tradeId: z.string().optional().describe("tradeId jurnal yang ditutup; kosong = cari otomatis trade terbuka pair ini"),
      exitReason: z.enum(["TARGET", "STOPLOSS", "MANUAL", "TIMEOUT", "ERROR"]).optional()
        .describe("Alasan keluar; disimpan di jurnal"),
      note: z.string().optional().describe("Catatan tambahan untuk jurnal"),
    }),
    async execute(_toolCallId, params) {
      const price = params.price && params.price > 0 ? params.price : (await fetchPrice(params.symbol))?.price;
      const data = await placeOrder({
        side: "SELL",
        symbol: params.symbol,
        quantity: params.quantity,
        price,
        isSimulation: params.isSimulation ?? false,
        exchange: params.exchange ?? "TOKOCRYPTO",
        journal: {
          tradeId: params.tradeId,
          exitReason: params.exitReason ?? "MANUAL",
          note: params.note,
        },
      });
      return textResult(data);
    },
  });

  pi.registerTool({
    name: "cancel_order",
    label: "Cancel order",
    description: "Batalkan open order di nadi agentfeed (Tokocrypto/Paper)",
    parameters: z.object({
      symbol: z.string().describe("Simbol pair, contoh: BTCUSDT"),
      orderId: z.string().describe("Order ID yang ingin dibatalkan"),
      isSimulation: z.boolean().optional().default(false).describe("True jika order paper trading"),
      exchange: z.string().optional().default("TOKOCRYPTO").describe("Bursa target (default: TOKOCRYPTO)"),
    }),
    async execute(_toolCallId, params) {
      try {
        const token = await bridgeToken();
        const res = await fetch(`${BRIDGE_HTTP}/api/v1/trading/cancel`, {
          method: "POST",
          headers: { "Content-Type": "application/json", ...(token ? { "X-Agent-Token": token } : {}) },
          body: JSON.stringify(params),
          signal: AbortSignal.timeout(15000),
        });
        if (res.status === 401) {
          return textResult({ success: false, message: `Ditolak 401: token bridge salah/belum diinstal (${TOKEN_FILE} atau env AGENTFEED_TOKEN).` });
        }
        const data = await res.json();
        return textResult(data);
      } catch (e) {
        return textResult({
          success: false,
          message: `Gagal membatalkan order di bridge: ${e}`,
        });
      }
    },
  });

  pi.registerTool({
    name: "get_order_status",
    label: "Get order status",
    description: "Cek detail dan status order berdasarkan order ID dari Tokocrypto/Paper",
    parameters: z.object({
      orderId: z.string().describe("Order ID yang ingin dicek"),
      symbol: z.string().optional().describe("Simbol pair (disarankan untuk Tokocrypto)"),
      isSimulation: z.boolean().optional().default(false).describe("True jika order paper trading"),
      exchange: z.string().optional().default("TOKOCRYPTO").describe("Bursa target (default: TOKOCRYPTO)"),
    }),
    async execute(_toolCallId, params) {
      try {
        const q = new URLSearchParams({
          symbol: params.symbol || "",
          isSimulation: String(params.isSimulation ?? false),
          exchange: params.exchange || "TOKOCRYPTO",
        });
        const getOrderIdToken = await bridgeToken();
        const res = await fetch(`${BRIDGE_HTTP}/api/v1/trading/orders/${encodeURIComponent(params.orderId)}?${q}`, {
          headers: getOrderIdToken ? { "X-Agent-Token": getOrderIdToken } : undefined,
          signal: AbortSignal.timeout(10000),
        });
        if (res.status === 401) {
          return textResult({ success: false, message: `Ditolak 401: token bridge salah/belum diinstal (${TOKEN_FILE} atau env AGENTFEED_TOKEN).` });
        }
        const data = await res.json();
        return textResult(data);
      } catch (e) {
        return textResult({
          success: false,
          message: `Gagal membaca status order dari bridge: ${e}`,
        });
      }
    },
  });

  pi.registerTool({
    name: "paper_trade_journal",
    label: "Paper trade journal",
    description:
      "Baca jurnal trade paper (JSONL) dan kembalikan trade + agregat riset: win rate, rata-rata/median PnL, " +
      "MAE/MFE, dan left-on-table (MFE − PnL) untuk menilai apakah target/stop terlalu jauh. " +
      "Filter opsional: symbol, exitReason, sinceTs, hanya yang sudah ditutup (default).",
    parameters: z.object({
      symbol: z.string().optional().describe("Filter pair, contoh: BTCUSDT (kosong = semua)"),
      exitReason: z.string().optional().describe("Filter alasan keluar, contoh: TARGET / STOPLOSS / MANUAL"),
      sinceTs: z.number().optional().describe("Hanya trade yang dibuka setelah epoch ms ini"),
      includeOpen: z.boolean().default(false).describe("Ikutkan trade yang belum ditutup"),
      limit: z.number().min(1).max(1000).default(100).describe("Maksimum trade yang dikembalikan (terbaru dulu)"),
    }),
    async execute(_toolCallId, params) {
      try {
        const all = await journalLoad();
        const display = params.symbol ? splitSymbol(params.symbol).display : null;
        const filtered = all.filter((t) => {
          if (display && t.symbol !== display) return false;
          if (params.exitReason && t.exitReason !== params.exitReason) return false;
          if (params.sinceTs != null && t.openedAt < params.sinceTs) return false;
          if (!params.includeOpen && t.closedAt == null) return false;
          return true;
        });
        const shown = filtered.sort((a, b) => b.openedAt - a.openedAt).slice(0, params.limit);
        return textResult({
          path: JOURNAL_FILE,
          totalRecords: all.length,
          matched: filtered.length,
          summary: journalSummary(filtered),
          trades: shown,
        });
      } catch (e) {
        return textResult({ error: `Gagal baca jurnal: ${e}` });
      }
    },
  });

  const tickUpdate = (symbol: string, targetNetPct: number) => (t: { price: number; pnlPct: number; targetPrice: number }) => ({
    content: [
      {
        type: "text" as const,
        text: `${splitSymbol(symbol).display} ${t.price} → net ${t.pnlPct.toFixed(3)}% (target ${targetNetPct}% @ ${t.targetPrice.toFixed(8)})`,
      },
    ],
  });

  pi.registerTool({
    name: "watch_paper_position",
    label: "Watch paper position",
    description:
      "Pantau posisi paper sampai profit net target (atau stop loss) tercapai, lalu tutup dengan SELL market. " +
      "Biaya pakai FeeSchedule app + slippage 0.08%/sisi. Terbatas waktu: kalau `status:\"open\"` kembalikan " +
      "quantity/entryPrice yang sama ke panggilan berikutnya untuk melanjutkan.",
    parameters: z.object({
      symbol: z.string().describe("Simbol pair, contoh: BTCUSDT, ETHUSDT, BTCIDR"),
      quantity: z.number().positive().optional().describe("Kuantitas koin (dari hasil place_buy_order); kosong = pakai trade terbuka di jurnal"),
      entryPrice: z
        .number()
        .positive()
        .optional()
        .describe("Harga pasar mentah saat entry; kosong = jurnal, lalu harga pasar sekarang"),
      targetNetPct: z.number().default(0.33).describe("Target profit net (%) setelah fee + slippage"),
      stopLossPct: z.number().optional().describe("Cut loss otomatis dalam % harga entry; kosong = tanpa stop"),
      pollMs: z.number().min(2000).default(15000).describe("Jarak antar cek harga (ms)"),
      maxWaitSec: z.number().min(5).max(600).default(170).describe("Batas waktu satu panggilan (detik)"),
      isSimulation: z.boolean().default(true).describe("True untuk paper trading"),
      exchange: z.string().default("TOKOCRYPTO").describe("Bursa target (default: TOKOCRYPTO)"),
      tradeId: z.string().optional().describe("tradeId jurnal yang dipantau; kosong = trade terbuka terbaru pair ini"),
    }),
    async execute(_toolCallId, params, signal, onUpdate) {
      const journalTrade = params.isSimulation
        ? params.tradeId
          ? (await journalLoad()).find((t) => t.tradeId === params.tradeId)
          : await journalFindOpen(params.symbol, params.exchange)
        : undefined;
      const entryPrice =
        params.entryPrice ?? journalTrade?.entryPrice ?? (await fetchPrice(params.symbol))?.price;
      const quantity = params.quantity ?? journalTrade?.quantity;
      if (!entryPrice || !quantity) {
        return textResult({
          status: "error",
          message: `Entry tidak lengkap untuk ${params.symbol}: ${
            entryPrice ? "" : "harga tidak tersedia "
          }${quantity ? "" : "quantity tidak diisi dan tidak ada trade terbuka di jurnal "}` +
            "; posisi TIDAK dipantau.",
        });
      }
      const result = await watchPosition({
        symbol: params.symbol,
        quantity,
        entryPrice,
        targetNetPct: params.targetNetPct,
        stopLossPct: params.stopLossPct,
        pollMs: params.pollMs,
        maxWaitSec: params.maxWaitSec,
        isSimulation: params.isSimulation,
        exchange: params.exchange,
        tradeId: journalTrade?.tradeId,
        signal,
        onTick: (t) => onUpdate?.(tickUpdate(params.symbol, params.targetNetPct)(t)),
      });
      return textResult(result);
    },
  });

  pi.registerTool({
    name: "buy_and_watch_paper",
    label: "Buy and watch (paper)",
    description:
      "Beli paper lalu langsung pantau sampai target net tercapai / stop loss / waktu habis. " +
      "Satu panggilan = satu siklus; kalau hasilnya `status:\"open\"` lanjutkan dengan watch_paper_position.",
    parameters: z.object({
      symbol: z.string().describe("Simbol pair, contoh: BTCUSDT, ETHUSDT, BTCIDR"),
      quoteAmount: z.number().positive().describe("Nominal beli dalam quote currency (mis. 6 USDT / 100000 IDR)"),
      targetNetPct: z.number().default(0.33).describe("Target profit net (%) setelah fee + slippage"),
      stopLossPct: z.number().optional().describe("Cut loss otomatis dalam % harga entry; kosong = tanpa stop"),
      pollMs: z.number().min(2000).default(15000).describe("Jarak antar cek harga (ms)"),
      maxWaitSec: z.number().min(5).max(600).default(170).describe("Batas waktu satu panggilan (detik)"),
      isSimulation: z.boolean().default(true).describe("True untuk paper trading"),
      exchange: z.string().default("TOKOCRYPTO").describe("Bursa target (default: TOKOCRYPTO)"),
      note: z.string().optional().describe("Alasan/dasar entry; disimpan di jurnal"),
    }),
    async execute(_toolCallId, params, signal, onUpdate) {
      const buy = await placeOrder({
        side: "BUY",
        symbol: params.symbol,
        quoteAmount: params.quoteAmount,
        price: (await fetchPrice(params.symbol))?.price,
        isSimulation: params.isSimulation,
        exchange: params.exchange,
        journal: {
          targetNetPct: params.targetNetPct,
          stopLossPct: params.stopLossPct,
          note: params.note,
        },
      });
      if (!buy.success) return textResult({ stage: "buy", buy });
      const quantity = Number(buy.quantity ?? buy.executedQty ?? 0);
      const entryPrice = Number(buy.price ?? 0) || (await fetchPrice(params.symbol))?.price;
      if (!(quantity > 0) || !entryPrice) {
        return textResult({
          stage: "buy",
          buy,
          message: "Order BUY sukses tapi quantity/harga entry tidak lengkap; posisi terbuka tanpa pemantauan.",
        });
      }
      const watch = await watchPosition({
        symbol: params.symbol,
        quantity,
        entryPrice,
        targetNetPct: params.targetNetPct,
        stopLossPct: params.stopLossPct,
        pollMs: params.pollMs,
        maxWaitSec: params.maxWaitSec,
        isSimulation: params.isSimulation,
        exchange: params.exchange,
        tradeId: buy.journal?.tradeId,
        signal,
        onTick: (t) => onUpdate?.(tickUpdate(params.symbol, params.targetNetPct)(t)),
      });
      return textResult({ stage: "watch", buy, watch });
    },
  });


  // --- DISCOVERY & DATA PASAR LOKAL (2026-11-08) -----------------------------
  // Semua tool di bawah memanggil 127.0.0.1:8765. Tidak ada host eksternal.
  // `available:false` diteruskan apa adanya agar agent tidak memakai angka palsu.

  pi.registerTool({
    name: "get_bridge_capabilities",
    label: "Bridge capabilities",
    description: "Manifest kemampuan server: service, apiVersion, exchange, kuotasi, interval, dan daftar endpoint. Panggil ini pertama kali untuk discovery.",
    parameters: z.object({}),
    async execute() {
      return textResult((await bridgeGet("/api/v1/capabilities")).body);
    },
  });

  pi.registerTool({
    name: "get_market_time",
    label: "Market server time",
    description: "Waktu server Tokocrypto vs waktu perangkat (offsetMs) untuk sinkronisasi sebelum mengirim order.",
    parameters: z.object({}),
    async execute() {
      return textResult((await bridgeGet("/api/v1/market/time")).body);
    },
  });

  pi.registerTool({
    name: "get_market_ticker",
    label: "Market ticker",
    description: "Harga, high/low/volume/perubahan 24 jam dari REST resmi Tokocrypto lewat server lokal. Wajib baca `available` sebelum memakai `price`.",
    parameters: z.object({
      symbol: z.string().describe("Pair, contoh: BTCUSDT atau BTCIDR"),
    }),
    async execute(_toolCallId, params) {
      const r = await bridgeGet(`/api/v1/market/ticker?symbol=${encodeURIComponent(params.symbol)}`);
      return textResult(marketEndpoint(r));
    },
  });

  pi.registerTool({
    name: "get_market_klines",
    label: "Market klines",
    description: "Candle OHLCV dari server lokal. `candles[].closed=false` berarti bar masih berjalan.",
    parameters: z.object({
      symbol: z.string().describe("Pair, contoh: BTCUSDT"),
      interval: z.enum(["1m", "5m", "15m", "1h", "4h", "1d"]).default("1m").describe("Timeframe"),
      limit: z.number().min(10).max(1000).default(120).describe("Jumlah candle (10–1000)"),
    }),
    async execute(_toolCallId, params) {
      const q = new URLSearchParams({
        symbol: params.symbol,
        interval: params.interval ?? "1m",
        limit: String(params.limit ?? 120),
      });
      const r = await bridgeGet(`/api/v1/market/klines?${q}`, { timeoutMs: 12000 });
      return textResult(marketEndpoint(r));
    },
  });

  pi.registerTool({
    name: "get_market_depth",
    label: "Market depth",
    description: "Order book (bids/asks), spread, dan imbalance dari server lokal untuk menilai likuiditas sebelum eksekusi.",
    parameters: z.object({
      symbol: z.string().describe("Pair, contoh: BTCUSDT"),
      limit: z.number().min(5).max(100).default(20).describe("Kedalaman per sisi (5–100)"),
    }),
    async execute(_toolCallId, params) {
      const q = new URLSearchParams({ symbol: params.symbol, limit: String(params.limit ?? 20) });
      const r = await bridgeGet(`/api/v1/market/depth?${q}`);
      return textResult(marketEndpoint(r));
    },
  });

  pi.registerTool({
    name: "get_market_symbols",
    label: "Market symbols & rules",
    description: "Daftar simbol spot aktif + aturan eksekusi (tickSize, stepSize, minQty, minNotional, presisi). WAJIB dipakai sebelum menyusun order.",
    parameters: z.object({
      quote: z.enum(["USDT", "IDR", "ALL"]).default("ALL").describe("Filter kuotasi"),
      limit: z.number().min(1).max(500).default(200).describe("Maksimum simbol"),
    }),
    async execute(_toolCallId, params) {
      const q = new URLSearchParams({ quote: params.quote ?? "ALL", limit: String(params.limit ?? 200) });
      const r = await bridgeGet(`/api/v1/market/symbols?${q}`, { timeoutMs: 12000 });
      return textResult(marketEndpoint(r));
    },
  });

  pi.registerTool({
    name: "get_market_context",
    label: "Full trading context",
    description:
      "SATU panggilan untuk seluruh konteks sebelum memutuskan trading: ticker, klines, depth, aturan simbol, biaya (maker/taker/slippage/round-trip), sinyal terakhir, portofolio, dan status kesiapan order. Sumbernya identik dengan chart aplikasi. Baca `available` pada tiap bagian dan perhatikan `notes`.",
    parameters: z.object({
      symbol: z.string().describe("Pair, contoh: BTCUSDT"),
      interval: z.enum(["1m", "5m", "15m", "1h", "4h", "1d"]).default("1m").describe("Timeframe candle"),
      limit: z.number().min(10).max(1000).default(120).describe("Jumlah candle"),
    }),
    async execute(_toolCallId, params) {
      const q = new URLSearchParams({
        symbol: params.symbol,
        interval: params.interval ?? "1m",
        limit: String(params.limit ?? 120),
      });
      const r = await bridgeGet(`/api/v1/market/context?${q}`, { timeoutMs: 15000 });
      return textResult(marketEndpoint(r));
    },
  });

  pi.registerTool({
    name: "get_engine_analysis",
    label: "Engine multi-strategy analysis",
    description:
      "Analisis mendalam dari 3 engine (scalping, intraday, swing) + indikator teknikal komprehensif (RSI, MACD, EMA20/50/200, ATR, RVOL, VWAP, Bollinger Bands) dan konfluensi untuk pair tertentu.",
    parameters: z.object({
      symbol: z.string().describe("Pair, contoh: BTCUSDT atau BTCIDR"),
    }),
    async execute(_toolCallId, params) {
      const r = await bridgeGet(
        `/api/v1/engine/analysis?symbol=${encodeURIComponent(params.symbol)}`,
        { timeoutMs: 15000 }
      );
      return textResult(marketEndpoint(r));
    },
  });

  pi.registerTool({
    name: "list_open_orders",
    label: "List open orders",
    description:
      "Daftar open order yang masih aktif (simulasi atau cache riil) untuk rekonsiliasi setelah restart/timeout, sebelum memutuskan membatalkan atau menunggu. Membutuhkan token bridge.",
    parameters: z.object({
      symbol: z.string().optional().describe("Filter pair opsional"),
      isSimulation: z.boolean().default(false).describe("True = open order simulasi; false = cache order riil"),
      exchange: z.string().default("TOKOCRYPTO").describe("Bursa target"),
    }),
    async execute(_toolCallId, params) {
      const q = new URLSearchParams({
        isSimulation: String(params.isSimulation ?? false),
        exchange: params.exchange ?? "TOKOCRYPTO",
      });
      if (params.symbol) q.set("symbol", params.symbol);
      const r = await bridgeGet(`/api/v1/trading/orders?${q}`, { withToken: true, timeoutMs: 10000 });
      return textResult(marketEndpoint(r));
    },
  });
}
