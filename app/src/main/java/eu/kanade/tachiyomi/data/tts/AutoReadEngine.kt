package eu.kanade.tachiyomi.data.tts

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.data.ocr.MangaTranslatorService
import mihon.domain.ocr.interactor.ScanPageOcr
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.normalizeOcrTextForDisplay
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.cancellation.CancellationException

/**
 * Движок авточтения: скан кадра → фильтр по языку → (перевод) → озвучка
 * реплика-за-репликой с подсветкой текущей (линейка как в AlReader) →
 * сигнал «страница дочитана» для автолистания.
 *
 * Ключевые правила:
 * • Читается ТОЛЬКО текст выбранного языка (ru/en/ja/…): остальной текст
 *   на кадре игнорируется. UI-оверлеи приложения в кадр не попадают —
 *   захватывается контент, а не плавающие кнопки.
 * • История сканов: каждая прочитанная реплика запоминается (нормализованный
 *   хэш) — при повторном попадании в кадр (скролл туда-сюда, миллисекундные
 *   пересечения при листании) она не читается второй раз.
 * • Автолистание БЛОКИРУЕТСЯ, пока все реплики текущего кадра не озвучены:
 *   колбэк onPageFinished зовётся строго после последней реплики.
 */
class AutoReadEngine(
    private val context: Context,
    private val scanPageOcr: ScanPageOcr = Injekt.get(),
    private val prefs: OcrPreferences = Injekt.get(),
) {

    data class SpokenRegion(
        val text: String,
        val translated: String?,
        val box: OcrBoundingBox,
        val index: Int,
        val total: Int,
    )

    /** Текущая читаемая реплика — для подсветки-линейки поверх страницы. */
    private val _currentRegion = MutableStateFlow<SpokenRegion?>(null)
    val currentRegion = _currentRegion.asStateFlow()

    private val _isReading = MutableStateFlow(false)
    val isReading = _isReading.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** История прочитанного: нормализованные ключи реплик (на сессию, с лимитом). */
    private val spokenHistory = object : LinkedHashSet<Long>() {
        fun addCapped(e: Long): Boolean {
            val added = add(e)
            if (size > HISTORY_LIMIT) {
                val it = iterator()
                it.next()
                it.remove()
            }
            return added
        }
    }

    fun clearHistory() = spokenHistory.clear()

    /**
     * Прочитать кадр. [onPageFinished] вызывается ПОСЛЕ озвучки всех реплик —
     * там вызывающая сторона листает/скроллит дальше. Если нового текста нет
     * (всё уже в истории) — завершится сразу.
     */
    fun readFrame(
        bitmap: Bitmap,
        chapterId: Long,
        pageIndex: Int,
        onPageFinished: () -> Unit,
    ) {
        job?.cancel()
        job = scope.launch {
            _isReading.value = true
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val image = OcrImage(bitmap.width, bitmap.height, pixels)
                // Кадр в JPEG для AI-определения пола говорящих (если включено)
                val genderJpeg: ByteArray? = if (prefs.aiGenderVoices().get()) {
                    runCatching {
                        val out = java.io.ByteArrayOutputStream()
                        val scaled = if (bitmap.width > 1024) {
                            val h = bitmap.height * 1024 / bitmap.width
                            Bitmap.createScaledBitmap(bitmap, 1024, h, true)
                        } else bitmap
                        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                        if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
                        out.toByteArray()
                    }.getOrNull()
                } else null
                if (!bitmap.isRecycled) bitmap.recycle()

                val result = scanPageOcr.await(chapterId, pageIndex, image)

                val language = prefs.autoReadLanguage().get()
                val translate = prefs.autoReadTranslate().get()
                val order = prefs.scanReadingOrder().get()

                // 1) фильтр по языку + отсев мусора; 2) отсев уже прочитанного
                val fresh = result.regions
                    .asSequence()
                    .map { it.copy(text = normalizeOcrTextForDisplay(it.text).trim()) }
                    .filter { it.text.length >= MIN_TEXT_LENGTH }
                    .filter { matchesLanguage(it.text, language) }
                    .filter { spokenHistory.addCapped(historyKey(it.text)) }
                    .toList()

                // 3) порядок чтения
                val ordered = when (order) {
                    "ltr" -> fresh.sortedWith(compareBy({ rowOf(it.boundingBox.top) }, { it.boundingBox.left }))
                    "vertical" -> fresh.sortedBy { it.boundingBox.top }
                    else -> fresh.sortedWith(compareBy({ rowOf(it.boundingBox.top) }, { -it.boundingBox.right }))
                }

                // 3.5) AI-определение пола говорящих (Gemini Vision по лицам
                // и хвостикам баллонов); при выключенной опции/без ключа — null
                val genders: List<String?> = if (genderJpeg != null && ordered.isNotEmpty()) {
                    SpeakerGenderService.detect(genderJpeg, ordered.map { it.text }, prefs)
                } else {
                    List(ordered.size) { null }
                }

                // 4) реплика за репликой: подсветка -> (перевод) -> озвучка -> ждём конца
                for ((i, region) in ordered.withIndex()) {
                    if (job?.isActive != true) break

                    val speakTextRaw = if (translate && language != "ru") {
                        runCatching { MangaTranslatorService.translate(region.text, "ru") }
                            .getOrDefault(region.text)
                    } else {
                        region.text
                    }

                    _currentRegion.value = SpokenRegion(
                        text = region.text,
                        translated = speakTextRaw.takeIf { it != region.text },
                        box = region.boundingBox,
                        index = i + 1,
                        total = ordered.size,
                    )

                    speakAndAwait(speakTextRaw, genders.getOrNull(i))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "AutoRead frame failed" }
            } finally {
                _currentRegion.value = null
                _isReading.value = false
                if (job?.isCancelled != true) {
                    onPageFinished()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        TtsSpeaker.stop()
        _currentRegion.value = null
        _isReading.value = false
    }

    /** Озвучка с ожиданием реального окончания фразы. */
    private suspend fun speakAndAwait(text: String, gender: String? = null) {
        val done = MutableStateFlow(false)
        var started = false
        TtsSpeaker.speakAs(context, text, gender) { speaking ->
            if (speaking) started = true
            if (!speaking && started) done.value = true
        }
        // страховка: макс. время = длина текста * 180мс + 4с
        val timeoutMs = text.length * 180L + 4_000L
        val start = System.currentTimeMillis()
        while (!done.value && System.currentTimeMillis() - start < timeoutMs) {
            if (job?.isActive != true) {
                TtsSpeaker.stop()
                return
            }
            delay(100)
        }
    }

    /** Строка (ряд) для сортировки: реплики в пределах 12% высоты — один ряд. */
    private fun rowOf(top: Float): Int = (top / 0.12f).toInt()

    private fun historyKey(text: String): Long {
        // Нормализация против дрожания OCR: только буквы/цифры, нижний регистр
        val norm = text.lowercase().filter { it.isLetterOrDigit() }
        var h = 1125899906842597L
        for (c in norm) h = 31 * h + c.code
        return h
    }

    companion object {
        private const val MIN_TEXT_LENGTH = 2
        private const val HISTORY_LIMIT = 3000

        /**
         * Определение языка текста по алфавиту. Реплика проходит фильтр,
         * если ≥60% её букв принадлежат целевому алфавиту.
         */
        fun matchesLanguage(text: String, language: String): Boolean {
            if (language == "any") return true
            val letters = text.filter { it.isLetter() }
            if (letters.isEmpty()) return false
            val matching = letters.count { ch ->
                when (language) {
                    "ru" -> ch in '\u0400'..'\u04FF'
                    "en" -> ch in 'a'..'z' || ch in 'A'..'Z'
                    "ja" -> ch in '\u3040'..'\u30FF' || ch in '\u4E00'..'\u9FFF' || ch in '\u31F0'..'\u31FF'
                    "ko" -> ch in '\uAC00'..'\uD7AF' || ch in '\u1100'..'\u11FF'
                    "zh" -> ch in '\u4E00'..'\u9FFF'
                    else -> true
                }
            }
            return matching.toFloat() / letters.length >= 0.6f
        }
    }
}
