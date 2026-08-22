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
    const val ENGINE_ONNX = "onnx_tts"

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

    /** Пакет движка, которым инициализирован systemTts (для реинита при смене). */
    private var systemEnginePkg: String? = null

    /**
     * Ленивая инициализация системного движка. Поддерживает ВЫБОР ДВИЖКА
     * (по запросу пользователя — как в Zueira's Voice): Google TTS,
     * RHVoice, Acapela и любой другой установленный. Пустая настройка =
     * движок по умолчанию системы. При смене движка — переинициализация.
     */
    private fun ensureSystem(context: Context, onReady: (TextToSpeech?) -> Unit) {
        val wantEngine = prefs().systemTtsEngine().get().ifBlank { null }
        if (systemTts != null && systemEnginePkg != wantEngine) {
            // Пользователь сменил движок — пересоздаём
            runCatching { systemTts?.shutdown() }
            systemTts = null
            systemReady = false
        }
        systemTts?.let {
            if (systemReady) { onReady(it); return }
        }
        if (systemTts == null) {
            systemEnginePkg = wantEngine
            val listener = TextToSpeech.OnInitListener { status ->
                systemReady = status == TextToSpeech.SUCCESS
                onReady(if (systemReady) systemTts else null)
            }
            systemTts = if (wantEngine != null) {
                TextToSpeech(context.applicationContext, listener, wantEngine)
            } else {
                TextToSpeech(context.applicationContext, listener)
            }
        } else {
            onReady(null)
        }
    }

    /** Установленные TTS-движки устройства: (пакет, читаемое имя). */
    fun installedEngines(context: Context): List<Pair<String, String>> {
        // Надёжный способ узнать список движков — временный TextToSpeech
        val probe = runCatching { TextToSpeech(context.applicationContext) {} }.getOrNull()
        val engines = runCatching {
            probe?.engines?.map { it.name to it.label }
        }.getOrNull().orEmpty()
        runCatching { probe?.shutdown() }
        return engines
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
            ENGINE_ONNX -> speakOnnx(context, spoken, effectiveGender)
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
            // Совет локального JSON-помощника (правила пользователя/агента)
            val advisorVoice = LocalVoiceAdvisor.recommend(text, gender).voiceName
            val presetVoice = advisorVoice ?: when (gender) {
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
                    VoiceHelper.pick(
                        engine,
                        kind ?: VoiceKind.FEMALE,
                        presetVoice,
                        systemEnginePkg,
                    )
                kind != null && speakerSlot > 0 ->
                    VoiceHelper.pickForSpeaker(
                        engine,
                        kind,
                        speakerSlot,
                        enginePackage = systemEnginePkg,
                    ) ?: VoiceHelper.pick(engine, kind, null, systemEnginePkg)
                kind != null -> VoiceHelper.pick(engine, kind, null, systemEnginePkg)
                else -> {
                    val saved = p.voiceName().get()
                    // Автоподбор как в overlay-translator: если голос не выбран
                    // или его нет в системе — берём лучший русский женский
                    // (Svetlana и др.), затем любой русский.
                    VoiceHelper.pick(engine, VoiceKind.FEMALE, saved, systemEnginePkg)
                }
            }
            val activeEnginePackage = systemEnginePkg
                ?: runCatching { engine.defaultEngine }.getOrNull()
            val isRhVoice = activeEnginePackage.orEmpty().contains("rhvoice", ignoreCase = true)
            val forcedVoiceName = v?.name?.takeIf { isRhVoice }
            if (v != null) {
                val res = engine.setVoice(v)
                if (res != TextToSpeech.SUCCESS) {
                    // Some OEM clients reject a manually-created RHVoice Voice
                    // before the engine sees it. speak() below also sends the
                    // exact name in KEY_PARAM_VOICE_NAME, bypassing that bug.
                    logcat(LogPriority.WARN) { "Voice ${'$'}{v.name} rejected by TextToSpeech client" }
                    engine.language = Locale("ru", "RU")
                }
            } else {
                engine.language = Locale("ru", "RU")
            }
            val voiceParams = forcedVoiceName?.let { name ->
                android.os.Bundle().apply {
                    // Hidden Android framework key used internally by
                    // TextToSpeech.setVoice(); literal keeps public-SDK builds.
                    putString("voiceName", name)
                }
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
                    engine.speak(trimmed, mode, voiceParams, "yk_$i")
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

    // region ONNX (sherpa-onnx, нейроголоса офлайн)

    /**
     * ONNX-голос: женские реплики — голосом с gender=female (Ирина),
     * мужские — male (Дмитрий/Руслан). Голос по умолчанию — из настройки
     * pref_onnx_voice; при отсутствии установленного голоса — фолбэк на
     * системный TTS (честно, без тишины).
     */
    private fun speakOnnx(context: Context, text: String, gender: String?) {
        val p = prefs()
        currentJob = scope.launch {
            setSpeaking(true)
            try {
                val installed = OnnxTts.CATALOG.filter { OnnxTts.isInstalled(context, it) }
                if (!OnnxTts.isAvailable(context) || installed.isEmpty()) {
                    // Нет библиотеки или голосов — откат на системный движок
                    withContext(Dispatchers.Main) { speakSystem(context, text, gender) }
                    return@launch
                }
                // Локальный JSON-советник (voice_rules.json) важнее эвристик:
                // «{имя} говорит голосом X» — задаётся пользователем или AI-агентом
                val advice = LocalVoiceAdvisor.recommend(text, gender)
                val preferredId = p.onnxVoice().get()
                val voice = advice.onnxVoiceId?.let { id -> installed.firstOrNull { it.id == id } }
                    ?: installed.firstOrNull { gender != null && it.gender == gender }
                    ?: installed.firstOrNull { it.id == preferredId }
                    ?: installed.first()
                val baseSpeed = p.speechRate().get().coerceIn(0.5f, 2f)
                for (sentence in splitSentences(text)) {
                    if (currentJob?.isActive != true) break
                    val trimmed = sentence.trim()
                    // Живая просодия: скорость зависит от знаков препинания
                    // и капса — вопросы медленнее, крик быстрее, «…» задумчиво
                    val speed = OnnxTts.prosodySpeed(trimmed, baseSpeed)
                    val wav = OnnxTts.synthesizeToFile(context, voice, trimmed, speed) ?: continue
                    playFileBlocking(wav)
                    // Пауза между предложениями: 240мс после точки,
                    // 400мс после !/? — дыхание, а не конвейер
                    val pause = when {
                        trimmed.endsWith("!") || trimmed.endsWith("?") -> 400L
                        trimmed.endsWith("…") || trimmed.endsWith("...") -> 500L
                        else -> 240L
                    }
                    kotlinx.coroutines.delay(pause)
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "ONNX TTS failed" }
            } finally {
                setSpeaking(false)
            }
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

    /**
     * Реальный список голосов аккаунта ElevenLabs (GET /v1/voices по ключу).
     * Возвращает пары (voice_id, имя + категория). Пустой список при ошибке
     * или отсутствии ключа — никаких фейковых данных.
     */
    suspend fun fetchElevenVoices(apiKey: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()
        runCatching {
            val conn = URL("https://api.elevenlabs.io/v1/voices").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("xi-api-key", apiKey)
            if (conn.responseCode !in 200..299) {
                logcat(LogPriority.WARN) { "ElevenLabs voices HTTP ${conn.responseCode}" }
                conn.disconnect()
                return@runCatching emptyList()
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val arr = org.json.JSONObject(body).optJSONArray("voices") ?: return@runCatching emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    val id = v.optString("voice_id")
                    if (id.isBlank()) continue
                    val name = v.optString("name").ifBlank { id }
                    val labels = v.optJSONObject("labels")
                    val extra = buildList {
                        labels?.optString("gender")?.takeIf { it.isNotBlank() }?.let(::add)
                        labels?.optString("accent")?.takeIf { it.isNotBlank() }?.let(::add)
                        v.optString("category").takeIf { it.isNotBlank() }?.let(::add)
                    }.joinToString(", ")
                    add(id to if (extra.isBlank()) name else "$name ($extra)")
                }
            }
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "ElevenLabs voices fetch failed" }
            emptyList()
        }
    }

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
