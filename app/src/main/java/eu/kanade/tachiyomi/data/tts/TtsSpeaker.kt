package eu.kanade.tachiyomi.data.tts

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Единый TTS-движок приложения. Три источника голосов:
 *
 * 1. SYSTEM  — системные и локальные голоса Android TTS (Google Speech
 *    Services, RHVoice и любые установленные движки; включая офлайн-голоса).
 * 2. GOOGLE_WEB — озвучка с сайта Google Translate: БЕЗ API-ключа, берётся
 *    напрямую с их публичного endpoint. Работает всегда при интернете.
 * 3. ELEVENLABS — премиальные нейgolосовые через API-ключ (elevenlabs.io).
 *
 * Выбор движка/голоса хранится в OcrPreferences и применяется везде:
 * читалка, карточка перевода, диалог настроек.
 */
object TtsSpeaker {

    /**
     * Предел одной utterance. TextToSpeech.getMaxSpeechInputLength() почти
     * везде равен 4000; берём с запасом, чтобы не зависеть от прошивки.
     */
    private const val HARD_UTTERANCE_LIMIT = 3500

    const val ENGINE_SYSTEM = "system_tts"
    const val ENGINE_GOOGLE_WEB = "google_web"
    const val ENGINE_ELEVENLABS = "eleven_api"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    private var systemTts: TextToSpeech? = null
    private var systemReady = false
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    var isSpeaking: Boolean = false
        private set

    private var onStateChange: ((Boolean) -> Unit)? = null

    private fun prefs(): OcrPreferences = Injekt.get()

    /** Ленивая инициализация системного движка. */
    private fun ensureSystem(context: Context, onReady: (TextToSpeech?) -> Unit) {
        systemTts?.let {
            if (systemReady) { onReady(it); return }
        }
        if (systemTts == null) {
            systemTts = TextToSpeech(context.applicationContext) { status ->
                systemReady = status == TextToSpeech.SUCCESS
                onReady(if (systemReady) systemTts else null)
            }
        } else {
            onReady(null)
        }
    }

    /**
     * Озвучивает текст выбранным в настройках движком.
     * [onState] — колбэк true=началось / false=закончилось|ошибка.
     */
    fun speak(context: Context, text: String, onState: (Boolean) -> Unit = {}) {
        speakAs(context, text, gender = null, onState = onState)
    }

    /**
     * Озвучка с учётом пола говорящего: gender = "female" | "male" | null.
     * Для системного движка используется соответствующий голос из пресетов
     * (Настройки озвучки → Женский голос / Мужской голос). Для веб-движка
     * пол недоступен (у Google Translate один голос на язык).
     */
    @JvmOverloads
    fun speakAs(
        context: Context,
        text: String,
        gender: String?,
        speakerSlot: Int = 0,
        onState: (Boolean) -> Unit = {},
    ) {
        stop()
        onStateChange = onState
        // Служебная разметка ({1}{ж}, ÷) не должна попасть в синтез, даже
        // если вызывающий код забыл её снять.
        val spoken = SpeechMarkup.strip(text)
        if (spoken.isBlank()) {
            setSpeaking(false)
            return
        }
        val effectiveGender = gender ?: SpeechMarkup.genderOf(text)
        val slot = if (speakerSlot != 0) speakerSlot else SpeechMarkup.speakerSlot(text)
        when (prefs().voiceEngine().get()) {
            ENGINE_GOOGLE_WEB -> speakGoogleWeb(context, spoken)
            ENGINE_ELEVENLABS -> speakElevenLabs(context, spoken)
            else -> speakSystem(context, spoken, effectiveGender, slot)
        }
    }

    fun stop() {
        currentJob?.cancel()
        currentJob = null
        runCatching { systemTts?.stop() }
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        setSpeaking(false)
    }

    private fun setSpeaking(value: Boolean) {
        isSpeaking = value
        onStateChange?.invoke(value)
    }

    // region SYSTEM

    private fun speakSystem(
        context: Context,
        text: String,
        gender: String? = null,
        speakerSlot: Int = 0,
    ) {
        ensureSystem(context) { engine ->
            if (engine == null) {
                setSpeaking(false)
                return@ensureSystem
            }
            val p = prefs()
            engine.setSpeechRate(p.speechRate().get().coerceIn(0.5f, 2f))
            engine.setPitch(p.speechPitch().get().coerceIn(0.5f, 2f))
            // Пол говорящего (логика из overlay-translator):
            // 1) явный пресет пользователя для пола; 2) VoiceHelper.pick —
            // автоподбор по классификации имён (Svetlana/Dmitry/детские);
            // 3) общий голос; 4) язык ru-RU как последний рубеж.
            val presetVoice = when (gender) {
                "female" -> p.voiceFemale().get()
                "male" -> p.voiceMale().get()
                else -> ""
            }
            val kind = when (gender) {
                "male" -> VoiceKind.MALE
                "female" -> VoiceKind.FEMALE
                else -> null
            }
            // Разные персонажи одного пола получают разные голоса: слот > 0
            // сдвигает выбор внутри группы. Явный пресет пользователя всегда
            // важнее автоподбора.
            val v: android.speech.tts.Voice? = when {
                presetVoice.isNotBlank() && speakerSlot == 0 ->
                    engine.voices?.find { it.name == presetVoice }
                        ?: VoiceHelper.pick(engine, kind ?: VoiceKind.FEMALE, null)
                kind != null && speakerSlot > 0 ->
                    VoiceHelper.pickForSpeaker(engine, kind, speakerSlot)
                        ?: VoiceHelper.pick(engine, kind, null)
                kind != null -> VoiceHelper.pick(engine, kind, null)
                else -> {
                    val saved = p.voiceName().get()
                    // Автоподбор как в overlay-translator: если голос не выбран
                    // или его нет в системе — берём лучший русский женский
                    // (Svetlana и др.), затем любой русский.
                    engine.voices?.find { saved.isNotBlank() && it.name == saved }
                        ?: VoiceHelper.pick(engine, VoiceKind.FEMALE, null)
                }
            }
            if (v != null) {
                val res = engine.setVoice(v)
                if (res != TextToSpeech.SUCCESS) {
                    // Голос без данных — откат на язык, звук будет всегда
                    logcat(LogPriority.WARN) { "Voice ${'$'}{v.name} rejected (missing data?), falling back to ru-RU" }
                    engine.language = Locale("ru", "RU")
                }
            } else {
                engine.language = Locale("ru", "RU")
            }
            // Пунктуация → реальные паузы и интонация: текст режется на
            // предложения, каждое говорится отдельной utterance, между ними
            // тишина (250мс после точки, 420мс после !/?, 160мс после запятой).
            // Вопросительные получают лёгкий подъём питча, восклицательные —
            // чуть быстрее и выше.
            val sentences = splitSentences(text)
            if (sentences.isEmpty()) { setSpeaking(false); return@ensureSystem }
            val lastId = "yk_${sentences.size - 1}"
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    if (utteranceId == "yk_0") setSpeaking(true)
                }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == lastId) setSpeaking(false)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = setSpeaking(false)
                override fun onError(utteranceId: String?, errorCode: Int) = setSpeaking(false)
            })
            val baseRate = p.speechRate().get().coerceIn(0.5f, 2f)
            // Тон по полу: если для пола не нашлось ОТДЕЛЬНОГО голоса,
            // различаем персонажей питчем — мужчины ниже, женщины выше.
            // С отдельными голосами модификатор не нужен (=1.0).
            val voiceMatchesGender = v != null && when (gender) {
                "male" -> VoiceHelper.classify(v) == VoiceKind.MALE
                "female" -> VoiceHelper.classify(v) == VoiceKind.FEMALE
                else -> true
            }
            val genderPitchMod = when {
                voiceMatchesGender -> 1.0f
                gender == "male" -> 0.78f
                gender == "female" -> 1.18f
                else -> 1.0f
            }
            val basePitch = (p.speechPitch().get() * genderPitchMod).coerceIn(0.5f, 2f)
            var queued = false
            sentences.forEachIndexed { i, sentence ->
                val trimmed = sentence.trim()
                if (trimmed.isEmpty()) return@forEachIndexed
                when {
                    trimmed.endsWith("?") || trimmed.endsWith("?!") || trimmed.endsWith("⁇") -> {
                        engine.setPitch((basePitch * 1.12f).coerceAtMost(2f))
                        engine.setSpeechRate(baseRate * 0.95f)
                    }
                    trimmed.endsWith("!") || trimmed.endsWith("‼") -> {
                        engine.setPitch((basePitch * 1.07f).coerceAtMost(2f))
                        engine.setSpeechRate((baseRate * 1.05f).coerceAtMost(2f))
                    }
                    else -> {
                        engine.setPitch(basePitch)
                        engine.setSpeechRate(baseRate)
                    }
                }
                val mode = if (queued) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
                val r = try {
                    engine.speak(trimmed, mode, null, "yk_$i")
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "speak() rejected an utterance" }
                    TextToSpeech.ERROR
                }
                if (r == TextToSpeech.SUCCESS) queued = true
                val pauseMs = when {
                    trimmed.endsWith("!") || trimmed.endsWith("?") ||
                        trimmed.endsWith("‼") || trimmed.endsWith("⁇") -> 420L
                    trimmed.endsWith(",") || trimmed.endsWith(";") -> 160L
                    else -> 260L
                }
                runCatching {
                    engine.playSilentUtterance(pauseMs, TextToSpeech.QUEUE_ADD, "yk_p$i")
                }
            }
            if (!queued) setSpeaking(false)
        }
    }

    // endregion

    // region GOOGLE WEB (без API-ключа)

    private fun speakGoogleWeb(context: Context, text: String) {
        val lang = prefs().ttsWebLanguage().get().ifBlank { "ru" }
        currentJob = scope.launch {
            setSpeaking(true)
            try {
                // Endpoint сайта Google Translate ограничен ~200 симв. — бьём на куски
                val chunks = splitForWeb(text, 180)
                for (chunk in chunks) {
                    if (currentJob?.isActive != true) break
                    val url = "https://translate.google.com/translate_tts" +
                        "?ie=UTF-8&client=tw-ob&tl=" + lang +
                        "&q=" + URLEncoder.encode(chunk, "UTF-8")
                    val file = downloadToCache(context, url) ?: continue
                    playFileBlocking(file)
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Google Web TTS failed" }
            } finally {
                setSpeaking(false)
            }
        }
    }

    // endregion

    // region ELEVENLABS (API-ключ)

    private fun speakElevenLabs(context: Context, text: String) {
        val p = prefs()
        val apiKey = p.elevenApiKey().get()
        if (apiKey.isBlank()) {
            // Ключа нет — честный фолбэк на бесплатную веб-озвучку
            speakGoogleWeb(context, text)
            return
        }
        val voiceId = p.elevenVoiceId().get().ifBlank { "21m00Tcm4TlvDq8ikWAM" }
        currentJob = scope.launch {
            setSpeaking(true)
            try {
                val conn = URL("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("xi-api-key", apiKey)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "audio/mpeg")
                val body = """{"text":${jsonQuote(text)},"model_id":"eleven_multilingual_v2"}"""
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode in 200..299) {
                    val file = File(context.cacheDir, "tts_eleven.mp3")
                    conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                    playFileBlocking(file)
                } else {
                    logcat(LogPriority.WARN) { "ElevenLabs HTTP ${conn.responseCode}" }
                    speakGoogleWebInline(context, text)
                }
                conn.disconnect()
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "ElevenLabs TTS failed" }
                speakGoogleWebInline(context, text)
            } finally {
                setSpeaking(false)
            }
        }
    }

    /** Фолбэк внутри уже запущенной корутины. */
    private suspend fun speakGoogleWebInline(context: Context, text: String) {
        val lang = prefs().ttsWebLanguage().get().ifBlank { "ru" }
        for (chunk in splitForWeb(text, 180)) {
            val url = "https://translate.google.com/translate_tts" +
                "?ie=UTF-8&client=tw-ob&tl=" + lang +
                "&q=" + URLEncoder.encode(chunk, "UTF-8")
            val file = downloadToCache(context, url) ?: continue
            playFileBlocking(file)
        }
    }

    // endregion

    // region helpers

    private fun jsonQuote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** Делит текст на предложения по .!?…; куски без знаков — по 200 симв. */
    fun splitSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch == '.' || ch == '!' || ch == '?' || ch == '…' || ch == '‼' || ch == '⁇') {
                if (sb.isNotBlank()) result += sb.toString()
                sb.clear()
            } else if (sb.length >= 200 && ch == ' ') {
                result += sb.toString()
                sb.clear()
            }
        }
        if (sb.isNotBlank()) result += sb.toString()

        // Страховка: TextToSpeech.speak() бросает IllegalArgumentException,
        // если строка длиннее getMaxSpeechInputLength() (обычно 4000).
        // Текст без знаков препинания и без пробелов не резался ничем выше.
        return result.flatMap { it.chunked(HARD_UTTERANCE_LIMIT) }
    }

    private fun splitForWeb(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val parts = mutableListOf<String>()
        var rest = text.trim()
        while (rest.isNotEmpty()) {
            if (rest.length <= max) { parts += rest; break }
            var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '…', ';'), max)
            if (cut < max / 2) cut = rest.lastIndexOf(' ', max)
            if (cut < max / 2) cut = max
            parts += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1).trim()
        }
        return parts.filter { it.isNotBlank() }
    }

    private fun downloadToCache(context: Context, url: String): File? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            // Без браузерного UA endpoint отдаёт 403
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
            )
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return null
            }
            val file = File(context.cacheDir, "tts_web_${System.nanoTime()}.mp3")
            conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            file
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "TTS download failed" }
            null
        }
    }

    private suspend fun playFileBlocking(file: File) {
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer()
                mediaPlayer = mp
                try {
                    mp.setDataSource(file.absolutePath)
                    mp.setOnCompletionListener {
                        runCatching { mp.release() }
                        file.delete()
                        if (cont.isActive) cont.resume(Unit)
                    }
                    mp.setOnErrorListener { _, _, _ ->
                        runCatching { mp.release() }
                        file.delete()
                        if (cont.isActive) cont.resume(Unit)
                        true
                    }
                    mp.prepare()
                    mp.start()
                    cont.invokeOnCancellation {
                        runCatching { mp.stop(); mp.release() }
                        file.delete()
                    }
                } catch (e: Exception) {
                    runCatching { mp.release() }
                    file.delete()
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
    }

    // endregion
}
