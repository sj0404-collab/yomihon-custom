package mihon.data.ocr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Журналы авточтения и сканирования для экрана истории.
 *
 * Пользователь просил видеть: успех/неудачу по страницам, какие словари
 * срабатывали, и полный лог авточтения. Хранилище намеренно in-memory и
 * с ограничением: это диагностика текущего сеанса, а не база данных.
 */
object OcrHistoryStore {

    private const val MAX_ENTRIES = 200

    data class ScanEntry(
        val time: Long,
        val ok: Boolean,
        val detail: String,
        val wordDictHits: Int,
        val punctFixes: Int,
        val splitFixes: Int,
    )

    data class AutoReadEntry(
        val time: Long,
        val ok: Boolean,
        val event: String,
        val detail: String,
    )

    private val _scans = MutableStateFlow<List<ScanEntry>>(emptyList())
    val scans: StateFlow<List<ScanEntry>> = _scans

    private val _reads = MutableStateFlow<List<AutoReadEntry>>(emptyList())
    val reads: StateFlow<List<AutoReadEntry>> = _reads

    @Synchronized
    fun addScan(
        ok: Boolean,
        detail: String,
        wordDictHits: Int,
        punctFixes: Int,
        splitFixes: Int,
    ) {
        val entry = ScanEntry(
            System.currentTimeMillis(), ok, detail, wordDictHits, punctFixes, splitFixes,
        )
        _scans.value = (listOf(entry) + _scans.value).take(MAX_ENTRIES)
    }

    @Synchronized
    fun addAutoRead(ok: Boolean, event: String, detail: String) {
        val entry = AutoReadEntry(System.currentTimeMillis(), ok, event, detail)
        _reads.value = (listOf(entry) + _reads.value).take(MAX_ENTRIES)
    }
}
