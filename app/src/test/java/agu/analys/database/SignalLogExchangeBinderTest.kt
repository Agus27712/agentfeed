package agu.analys.database

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Uji pengikat query jurnal ke bursa aktif — **J3**.
 *
 * Inti temuan: parameter Room terikat saat flow dibuat, jadi bursa harus **mengalir**. Kalau
 * dibaca sekali, mengganti bursa tidak pernah mengulang subscription dan tabel log +
 * reliabilitas tetap berisi bursa lama.
 */
class SignalLogExchangeBinderTest {

    /**
     * Jalankan binder di atas [initial] sambil [block] memutar-mutar bursanya.
     * Mengembilkan pasangan **(nama bursa yang diminta query, nilai yang dikeluarkan)**.
     */
    private fun collectWhile(
        initial: String,
        block: suspend (source: MutableStateFlow<String>) -> Unit
    ): Pair<List<String>, List<String>> = runBlocking {
        val source = MutableStateFlow(initial)
        val queries = mutableListOf<String>()
        val emitted = mutableListOf<String>()
        val job = launch {
            SignalLogExchangeBinder.bind(source) { exchange ->
                queries.add(exchange)
                flowOf("HASIL:$exchange")
            }.toList(emitted)
        }
        delay(50)
        block(source)
        delay(50)
        job.cancel()
        queries.toList() to emitted.toList()
    }

    @Test
    fun `bind - query dijalankan untuk bursa awal`() {
        val (queries, emitted) = collectWhile("TOKOCRYPTO") {}
        assertEquals(listOf("TOKOCRYPTO"), queries)
        assertEquals(listOf("HASIL:TOKOCRYPTO"), emitted)
    }

    @Test
    fun `bind - nama bursa yang sama berturut-turut tidak mengulang subscription`() {
        val (queries, _) = collectWhile("TOKOCRYPTO") { source -> source.value = "TOKOCRYPTO" }
        assertEquals(listOf("TOKOCRYPTO"), queries)
    }

    @Test
    fun `bind - nama bursa dibakukan sebelum dipakai query`() {
        val (queries, emitted) = collectWhile("tokocrypto") {}
        assertEquals(listOf("TOKOCRYPTO"), queries)
        assertEquals(listOf("HASIL:TOKOCRYPTO"), emitted)
    }

    @Test
    fun `bind - spasi di nama bursa dibuang`() {
        val (queries, _) = collectWhile("  TOKOCRYPTO  ") {}
        assertEquals(listOf("TOKOCRYPTO"), queries)
    }

}
