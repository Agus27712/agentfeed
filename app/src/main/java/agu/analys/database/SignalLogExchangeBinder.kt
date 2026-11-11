package agu.analys.database

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Pengikat query jurnal sinyal ke bursa yang **sedang aktif** — **J3**.
 *
 * ## Kenapa kelas ini ada
 *
 * `SignalLogViewModel` dulu membuat flow-nya **sekali saat konstruksi**:
 *
 * ```kotlin
 * val currentExchange: String get() = prefs.marketDataSource.name          // getter, tapi…
 * val allSignalLogs: StateFlow<List<SignalLogEntity>> =
 *     signalLogRepository.getLogsByExchangeFlow(currentExchange)           // dievaluasi SEKALI
 *         .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
 * ```
 *
 * Parameter Room (`dao.getLogsByExchangeFlow(exchange.uppercase())`) terikat saat flow dibuat,
 * jadi perubahan sumber data **tidak** mengulang subscription. Akibatnya: setelah
 * sumber berubah, tab "Log & Evaluasi Sinyal" masih menampilkan log **dan** tabel
 * "RELIABILITAS SKOR KEYAKINAN" dari data lama, tanpa label penjelas.
 *
 * Jejak bahwa ini memang pernah dimaksudkan reaktif: `flatMapLatest` sudah di-import di berkas
 * itu tetapi tidak pernah dipakai.
 *
 * ## Yang ditegakkan di sini
 *
 * - Query **berlangganan ulang** setiap kali bursa aktif berubah (`flatMapLatest`).
 * - Nama bursa yang **sama berturut-turut tidak** mengulang subscription
 *   (`distinctUntilChanged`) — kalau tidak, setiap emisi `StateFlow` membuka query Room baru.
 * - Nama dibakukan (`trim().uppercase()`) supaya perbandingannya tidak bergantung pada
 *   penulisan pemanggil; DAO juga memanggil `uppercase()`, jadi keduanya sepakat.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object SignalLogExchangeBinder {

    /**
     * Ikat [query] ke aliran nama bursa. Setiap kali bursa aktif berubah, subscription lama
     * dibatalkan dan query dijalankan ulang untuk bursa yang baru.
     */
    fun <T> bind(
        exchangeNames: Flow<String>,
        query: (exchange: String) -> Flow<T>
    ): Flow<T> = exchangeNames
        .map { it.trim().uppercase() }
        .distinctUntilChanged()
        .flatMapLatest { exchange -> query(exchange) }
}
