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
        /** Служебные пометки для показа ({1}{ж}) — TTS их не произносит. */
        val marks: String = "",
        val box: OcrBoundingBox,
        val index: Int,
        val total: Int,
    )

    /** Текущая читаемая реплика — для подсветки-линейки поверх страницы. */
    private val _currentRegion = MutableStateFlow<SpokenRegion?>(null)
    val currentRegion = _currentRegion.asStateFlow()

    /**
     * ВСЕ реплики кадра с их статусом: прочитана / читается / предстоит.
     * Оверлей рисует прочитанные полупрозрачно, текущую — ярко, будущие —
     * пунктирно, так видно и историю, и план чтения.
     */
    data class FrameRegion(
        val box: OcrBoundingBox,
        val index: Int,
        val state: State,
    ) {
        enum class State { DONE, CURRENT, UPCOMING }
    }

    private val _frameRegions = MutableStateFlow<List<FrameRegion>>(emptyList())
    val frameRegions = _frameRegions.asStateFlow()

    /**
     * Зона книги внутри вьюпорта (доли 0..1) — если кадр перед OCR был
     * обрезан до неё, оверлей обязан пересчитать box'ы обратно.
     */
    @Volatile
    var highlightZone: android.graphics.RectF? = null

    /** Box из координат обрезанного кадра -> координаты вьюпорта. */
    fun mapToViewport(box: OcrBoundingBox): OcrBoundingBox {
        val z = highlightZone ?: return box
        val zw = z.right - z.left
        val zh = z.bottom - z.top
        return OcrBoundingBox(
            left = z.left + box.left * zw,
            top = z.top + box.top * zh,
            right = z.left + box.right * zw,
            bottom = z.top + box.bottom * zh,
        )
    }

    private val _isReading = MutableStateFlow(false)
    val isReading = _isReading.asStateFlow()

    /** Был ли в последнем кадре новый текст (для темпа автоскролла). */
    @Volatile
    var lastFrameHadText: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Поколение запуска: stop() инвалидирует все колбэки прежних запусков. */
    @Volatile
    private var generation = 0

    /**
     * История прочитанного с НЕЧЁТКИМ сравнением: OCR той же реплики при
     * смещённом кадре даёт слегка другой текст (обрезанные края, дрожание),
     * поэтому точный хэш пропускал дубли. Храним нормализованные строки и
     * сравниваем по включению/похожести 3-граммами (порог 0.75).
     */
    private val spokenTexts = ArrayDeque<String>()

    @Synchronized
    private fun isDuplicate(rawText: String): Boolean {
        val norm = rawText.lowercase().filter { it.isLetterOrDigit() }
        if (norm.length < 4) return true // мусор/односимвольные не читаем повторно
        for (old in spokenTexts) {
            if (old.contains(norm) || norm.contains(old)) return true
            if (trigramSimilarity(old, norm) >= 0.75f) return true
        }
        spokenTexts.addLast(norm)
        while (spokenTexts.size > HISTORY_LIMIT) spokenTexts.removeFirst()
        return false
    }

    private fun trigramSimilarity(a: String, b: String): Float {
        if (a.length < 3 || b.length < 3) return if (a == b) 1f else 0f
        val ta = HashSet<String>(a.length)
        for (i in 0..a.length - 3) ta.add(a.substring(i, i + 3))
        var common = 0
        var total = 0
        for (i in 0..b.length - 3) {
            total++
            if (b.substring(i, i + 3) in ta) common++
        }
        return if (total == 0) 0f else common.toFloat() / total
    }

    @Synchronized
    fun clearHistory() = spokenTexts.clear()

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
        TtsSpeaker.stop()
        val myGen = ++generation
        job = scope.launch {
            _isReading.value = true
            var aiRefine: Job? = null
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
                    .filter { !isDuplicate(it.text) }
                    .toList()

                // 3) порядок чтения
                val ordered = when (order) {
                    "ltr" -> fresh.sortedWith(compareBy({ rowOf(it.boundingBox.top) }, { it.boundingBox.left }))
                    "vertical" -> fresh.sortedBy { it.boundingBox.top }
                    else -> fresh.sortedWith(compareBy({ rowOf(it.boundingBox.top) }, { -it.boundingBox.right }))
                }

                // 3.5) Пол говорящих. Приоритет:
                //  а) ВСТРОЕННЫЙ локальный AI (LocalSpeakerAi) — морфология
                //     русского текста, работает без сети и без ключей;
                //  б) Gemini Vision — только если включена опция И задан ключ
                //     (уточняет по лицам то, что не смогла морфология).
                val localGenders = LocalSpeakerAi.guessGenders(ordered.map { it.text })
                // НЕ БЛОКИРУЕМ ОЗВУЧКУ: чтение стартует сразу с локальными
                // вердиктами морфологии. Онлайн-ассистент (быстрый формат
                // «одна буква на реплику», max_tokens=40, таймаут 6с)
                // работает ПАРАЛЛЕЛЬНО и дописывает пол реплик, до которых
                // очередь озвучки ещё не дошла. Раньше тяжёлые reasoning-
                // модели держали весь кадр — голос молчал, а на слабых
                // устройствах приложение ловило ANR.
                val genders = java.util.concurrent.atomic.AtomicReferenceArray<String?>(ordered.size)
                for (i in ordered.indices) genders.set(i, localGenders[i])

                aiRefine = if (
                    prefs.aiGenderVoices().get() && ordered.isNotEmpty() &&
                    localGenders.any { it == null }
                ) {
                    scope.launch {
                        val ai = eu.kanade.tachiyomi.data.ai.AiAssistant
                            .detectGendersByText(ordered.map { it.text })
                        for (i in ordered.indices) {
                            if (genders.get(i) == null) genders.set(i, ai.getOrNull(i))
                        }
                    }
                } else {
                    null
                }
                // Gemini Vision как ещё один фоновый уточнитель — только с ключом
                if (genderJpeg != null && ordered.isNotEmpty() &&
                    prefs.googleApiKey().get().isNotBlank() && localGenders.any { it == null }
                ) {
                    scope.launch {
                        val vision = SpeakerGenderService.detect(genderJpeg, ordered.map { it.text }, prefs)
                        for (i in ordered.indices) {
                            if (genders.get(i) == null) genders.set(i, vision.getOrNull(i))
                        }
                    }
                }

                lastFrameHadText = ordered.isNotEmpty()

                // 3.7) перевод ВСЕЙ страницы одним запросом (раньше был
                // отдельный HTTP-запрос на каждую реплику — на 15 бабблах
                // это 15 последовательных обращений между озвучками).
                val target = prefs.translateTarget().get().ifBlank { "ru" }
                val translations: List<String> = if (translate && language != target) {
                    runCatching { MangaTranslatorService.translateAll(ordered.map { it.text }, target) }
                        .getOrElse { ordered.map { it.text } }
                } else {
                    ordered.map { it.text }
                }

                // Публикуем карту кадра: всё, что будет прочитано
                _frameRegions.value = ordered.mapIndexed { i, r ->
                    FrameRegion(r.boundingBox, i + 1, FrameRegion.State.UPCOMING)
                }

                // 4) реплика за репликой: подсветка -> озвучка -> ждём конца
                for ((i, region) in ordered.withIndex()) {
                    if (job?.isActive != true) break

                    // Обновляем статусы: до i — прочитано, i — читается, после — предстоит
                    _frameRegions.value = ordered.mapIndexed { j, r ->
                        FrameRegion(
                            r.boundingBox,
                            j + 1,
                            when {
                                j < i -> FrameRegion.State.DONE
                                j == i -> FrameRegion.State.CURRENT
                                else -> FrameRegion.State.UPCOMING
                            },
                        )
                    }

                    val speakTextRaw = translations.getOrNull(i) ?: region.text

                    val gender = genders.get(i) // мог дозаполниться AI пока читали предыдущие

                    // Служебные пометки: номер по порядку чтения и пол.
                    // Они показываются на экране, но НЕ произносятся —
                    // SpeechMarkup.strip() снимает их перед синтезом.
                    val marks = buildString {
                        if (prefs.showSpeechNumbers().get()) append("{").append(i + 1).append("}")
                        when (gender) {
                            "female" -> append("{ж}")
                            "male" -> append("{м}")
                        }
                    }

                    _currentRegion.value = SpokenRegion(
                        text = region.text,
                        translated = speakTextRaw.takeIf { it != region.text },
                        box = region.boundingBox,
                        index = i + 1,
                        total = ordered.size,
                        marks = marks,
                    )

                    // Слот говорящего: два персонажа одного пола в сцене
                    // получают разные голоса. Считаем по индексам, а не через
                    // indexOf: одинаковые реплики иначе дали бы один и тот же
                    // слот.
                    val slot = if (prefs.perSpeakerVoices().get()) {
                        (0 until i).count { genders.get(it) == gender }
                    } else {
                        0
                    }

                    speakAndAwait(SpeechMarkup.strip(speakTextRaw), gender, slot)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "AutoRead frame failed" }
            } finally {
                aiRefine?.cancel()
                _currentRegion.value = null
                _frameRegions.value = emptyList()
                _isReading.value = false
                // Колбэк только для АКТУАЛЬНОГО запуска: после stop() старый
                // цикл не имеет права листать дальше или перезапускать чтение
                if (myGen == generation && job?.isCancelled != true) {
                    onPageFinished()
                }
            }
        }
    }

    fun stop() {
        generation++ // инвалидируем все pending-колбэки
        job?.cancel()
        job = null
        TtsSpeaker.stop()
        _currentRegion.value = null
        _frameRegions.value = emptyList()
        _isReading.value = false
    }

    /** Озвучка с ожиданием реального окончания фразы. */
    private suspend fun speakAndAwait(text: String, gender: String? = null, speakerSlot: Int = 0) {
        val done = MutableStateFlow(false)
        var started = false
        TtsSpeaker.speakAs(context, text, gender, speakerSlot) { speaking ->
            if (speaking) started = true
            if (!speaking && started) done.value = true
        }
        // страховка: макс. время = длина текста * 180мс + 4с
        val timeoutMs = text.length * 220L + 5_000L
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

    companion object {
        private const val MIN_TEXT_LENGTH = 2
        private const val HISTORY_LIMIT = 600

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
