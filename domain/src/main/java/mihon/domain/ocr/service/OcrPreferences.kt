package mihon.domain.ocr.service

import mihon.domain.ocr.model.OcrModel
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

enum class ScanRegion {
    FULL_PAGE,   // Сканировать всю страницу (100%)
    TOP_HALF,    // Сканировать верхнюю часть (Top 50%)
    BOTTOM_HALF, // Сканировать нижнюю часть (Bottom 50%)
}

class OcrPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun ocrModel() = preferenceStore.getEnum("pref_ocr_model", OcrModel.ZEN_FREE)

    fun scanRegion() = preferenceStore.getEnum("pref_scan_region", ScanRegion.FULL_PAGE)

    fun autoOcrOnDownload() = preferenceStore.getBoolean("auto_ocr_on_download", false)

    fun owocrAddress() = preferenceStore.getString("pref_owocr_address", "ws://10.0.2.2:7331")

    fun useFallbackModels() = preferenceStore.getBoolean("pref_use_fallback_models", true)

    // OpenRouter Settings
    fun openrouterApiKey() = preferenceStore.getString("pref_openrouter_api_key", "")
    fun openrouterModel() = preferenceStore.getString("pref_openrouter_model", "google/gemini-2.5-flash")

    // Google AI / Gemini Settings
    fun googleApiKey() = preferenceStore.getString("pref_google_api_key", "")
    fun googleModel() = preferenceStore.getString("pref_google_model", "gemini-2.5-flash")

    // Zen Free Mode Settings (Works without API key)
    fun zenFreeEnabled() = preferenceStore.getBoolean("pref_zen_free_enabled", true)

    // Token Tracker & Usage Counter
    fun tokenUsageCount() = preferenceStore.getLong("pref_token_usage_count", 0L)
    fun incrementTokens(tokens: Long) {
        val current = tokenUsageCount().get()
        tokenUsageCount().set(current + tokens)
    }

    // Voice & Text-to-Speech Settings
    // Движки: system_tts (системные/локальные голоса), google_web (веб без
    // API-ключа, с сайта Google Translate), eleven_api (ElevenLabs по ключу)
    fun voiceEngine() = preferenceStore.getString("pref_voice_engine", "system_tts")
    fun voiceName() = preferenceStore.getString("pref_voice_name", "ru-ru-x-dfa-network")
    fun speechRate() = preferenceStore.getFloat("pref_speech_rate", 1.0f)
    fun speechPitch() = preferenceStore.getFloat("pref_speech_pitch", 1.0f)
    fun ttsWebLanguage() = preferenceStore.getString("pref_tts_web_lang", "ru")
    fun elevenApiKey() = preferenceStore.getString("pref_eleven_api_key", "")
    fun elevenVoiceId() = preferenceStore.getString("pref_eleven_voice_id", "")

    // Пресеты голосов: отдельно женский и мужской системные голоса.
    // При автоозвучке реплики могут чередоваться по полу говорящего.
    fun voiceFemale() = preferenceStore.getString("pref_voice_female", "")
    fun voiceMale() = preferenceStore.getString("pref_voice_male", "")

    // Авто-OCR видимой страницы + мгновенная озвучка результата
    fun autoScanAndSpeak() = preferenceStore.getBoolean("pref_auto_scan_speak", false)

    // Пресет направления сканирования/чтения страницы:
    // rtl (манга), ltr (комиксы), vertical (вебтуны)
    fun scanReadingOrder() = preferenceStore.getString("pref_scan_reading_order", "rtl")

    // Local Model Management (модели НЕ входят в APK — по умолчанию не установлены,
    // из коробки работают только онлайн-движки)
    fun isMangaOcrDownloaded() = preferenceStore.getBoolean("pref_model_manga_ocr_downloaded", false)
    fun isFastOcrDownloaded() = preferenceStore.getBoolean("pref_model_fast_ocr_downloaded", false)
    fun isPanelDetectorDownloaded() = preferenceStore.getBoolean("pref_model_panel_detector_downloaded", false)
}
