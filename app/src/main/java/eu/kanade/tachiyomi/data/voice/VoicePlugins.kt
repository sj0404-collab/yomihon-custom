package eu.kanade.tachiyomi.data.voice

import android.content.Context
import eu.kanade.tachiyomi.data.tts.OnnxTts
import mihon.domain.ocr.service.OcrPreferences

/**
 * Тип голосового бэкенда. Совпадает со значениями `pref_voice_engine`
 * ("system_tts", "google_web", "eleven_api") и расширяется локальными
 * нейросетевыми движками, которые раньше выбирались отдельными настройками.
 */
enum class VoiceBackend(val id: String) {
    /** Системные и сторонние Android TTS-движки. */
    SYSTEM_TTS("system_tts"),

    /** Веб-озвучка Google Translate: без ключа, но нужен интернет. */
    GOOGLE_WEB("google_web"),

    /** ElevenLabs по API-ключу. */
    ELEVEN_API("eleven_api"),

    /** Офлайн нейросетевые голоса sherpa-onnx (VITS/Piper). */
    ONNX("onnx"),
    ;

    companion object {
        fun fromId(id: String?): VoiceBackend =
            entries.firstOrNull { it.id == id } ?: SYSTEM_TTS
    }
}

/** Что нужно движку, чтобы озвучить реплику. */
enum class VoiceRequirement {
    /** Нужен интернет. */
    NETWORK,

    /** Нужен API-ключ. */
    API_KEY,

    /** Нужен установленный в системе Android TTS-движок. */
    SYSTEM_ENGINE,

    /** Нужно скачать модель голоса. */
    MODEL_DOWNLOAD,

    /** Нужна нативная библиотека, которой может не быть в сборке. */
    NATIVE_LIBRARY,
}

/**
 * Описание одного голосового движка как плагина.
 *
 * Как и [eu.kanade.tachiyomi.data.ai.AiPlugins], это декларативный реестр: он
 * ничего не озвучивает сам. Реальные вызовы остаются в `TtsSpeaker`,
 * `OnnxTts` и `VoiceHelper`, а реестр даёт настройкам единый список движков с
 * понятными требованиями и списком голосов.
 */
data class VoicePluginDescriptor(
    val id: String,
    val backend: VoiceBackend,
    val title: String,
    val summary: String,
    val requirements: Set<VoiceRequirement> = emptySet(),
    /** Движок умеет разводить реплики по полу говорящего. */
    val supportsGender: Boolean = false,
    /** Движок умеет несколько разных голосов одновременно. */
    val supportsMultipleVoices: Boolean = false,
    /** Движок работает без сети. */
    val offline: Boolean = true,
)

/**
 * Реестр голосовых плагинов и один голос внутри них.
 *
 * [voices] намеренно функция, а не поле: список системных голосов и скачанных
 * ONNX-моделей зависит от устройства и меняется во время работы приложения.
 */
object VoicePlugins {

    /** Один конкретный голос внутри движка. */
    data class Voice(
        val id: String,
        val name: String,
        /** female | male | neutral. */
        val gender: String,
        /** Язык в теге BCP-47, если движок его знает. */
        val language: String? = null,
        /** Размер модели в МБ, если голос нужно скачивать. */
        val sizeMb: Int = 0,
        /**
         * Готов ли голос к работе прямо сейчас. Для ONNX это реальная проверка
         * распакованной модели на диске, поэтому список не обещает голосов,
         * которых на устройстве нет.
         */
        val installed: Boolean = true,
    )

    val SYSTEM_TTS = VoicePluginDescriptor(
        id = "system_tts",
        backend = VoiceBackend.SYSTEM_TTS,
        title = "Системный TTS",
        summary = "Голоса установленных Android-движков (Google, RHVoice, Acapela и любые другие).",
        requirements = setOf(VoiceRequirement.SYSTEM_ENGINE),
        supportsGender = true,
        supportsMultipleVoices = true,
    )

    val GOOGLE_WEB = VoicePluginDescriptor(
        id = "google_web",
        backend = VoiceBackend.GOOGLE_WEB,
        title = "Google Web (без ключа)",
        summary = "Веб-озвучка Google Translate: работает без API-ключа, но требует интернет.",
        requirements = setOf(VoiceRequirement.NETWORK),
        offline = false,
    )

    val ELEVEN_API = VoicePluginDescriptor(
        id = "eleven_api",
        backend = VoiceBackend.ELEVEN_API,
        title = "ElevenLabs",
        summary = "Нейросетевая озвучка по API-ключу ElevenLabs.",
        requirements = setOf(VoiceRequirement.NETWORK, VoiceRequirement.API_KEY),
        supportsMultipleVoices = true,
        offline = false,
    )

    val ONNX = VoicePluginDescriptor(
        id = "onnx",
        backend = VoiceBackend.ONNX,
        title = "ONNX-голоса (офлайн)",
        summary = "sherpa-onnx и русские Piper-голоса: модели скачиваются один раз, дальше работают без сети.",
        requirements = setOf(VoiceRequirement.MODEL_DOWNLOAD, VoiceRequirement.NATIVE_LIBRARY),
        supportsGender = true,
        supportsMultipleVoices = true,
    )

    val ALL = listOf(SYSTEM_TTS, GOOGLE_WEB, ELEVEN_API, ONNX)

    private val BY_ID = ALL.associateBy { it.id }

    fun byId(id: String?): VoicePluginDescriptor? = id?.let { BY_ID[it] }

    fun byBackend(backend: VoiceBackend): VoicePluginDescriptor? =
        ALL.firstOrNull { it.backend == backend }

    /** Движки, готовые к работе прямо сейчас. */
    fun available(
        networkAvailable: Boolean,
        systemEnginePresent: Boolean,
        hasApiKey: (VoicePluginDescriptor) -> Boolean = { false },
        nativeLibraryPresent: (VoicePluginDescriptor) -> Boolean = { true },
        modelsDownloaded: (VoicePluginDescriptor) -> Boolean = { false },
    ): List<VoicePluginDescriptor> = ALL.filter { plugin ->
        plugin.requirements.all { requirement ->
            when (requirement) {
                VoiceRequirement.NETWORK -> networkAvailable
                VoiceRequirement.API_KEY -> hasApiKey(plugin)
                VoiceRequirement.SYSTEM_ENGINE -> systemEnginePresent
                VoiceRequirement.MODEL_DOWNLOAD -> modelsDownloaded(plugin)
                VoiceRequirement.NATIVE_LIBRARY -> nativeLibraryPresent(plugin)
            }
        }
    }

    /**
     * Голоса движка. Для ONNX берётся реальный каталог [OnnxTts.CATALOG],
     * поэтому список не расходится с тем, что приложение умеет скачивать.
     */
    fun voices(context: Context, plugin: VoicePluginDescriptor, prefs: OcrPreferences): List<Voice> =
        when (plugin.backend) {
            VoiceBackend.ONNX -> OnnxTts.CATALOG.map { voice ->
                Voice(
                    id = voice.id,
                    name = voice.name,
                    gender = voice.gender,
                    language = "ru-RU",
                    sizeMb = voice.sizeMb,
                    installed = OnnxTts.isInstalled(context, voice),
                )
            }

            VoiceBackend.SYSTEM_TTS -> listOfNotNull(
                prefs.voiceFemale().get().takeIf(String::isNotBlank)?.let {
                    Voice(id = it, name = "Женский пресет", gender = "female")
                },
                prefs.voiceMale().get().takeIf(String::isNotBlank)?.let {
                    Voice(id = it, name = "Мужской пресет", gender = "male")
                },
                prefs.voiceName().get().takeIf(String::isNotBlank)?.let {
                    Voice(id = it, name = "Выбранный голос", gender = "neutral")
                },
            ).distinctBy { it.id }

            VoiceBackend.ELEVEN_API -> listOfNotNull(
                prefs.elevenVoiceId().get().takeIf(String::isNotBlank)?.let {
                    Voice(id = it, name = "Голос ElevenLabs", gender = "neutral")
                },
            )

            VoiceBackend.GOOGLE_WEB -> listOf(
                Voice(
                    id = prefs.ttsWebLanguage().get().ifBlank { "ru" },
                    name = "Веб-голос ${prefs.ttsWebLanguage().get().ifBlank { "ru" }}",
                    gender = "neutral",
                ),
            )
        }

    /**
     * Текущий движок из настроек. Неизвестное или пустое значение читается как
     * системный TTS — так же, как это уже делает `pref_voice_engine`.
     */
    fun current(prefs: OcrPreferences): VoicePluginDescriptor =
        byId(prefs.voiceEngine().get()) ?: SYSTEM_TTS
}
