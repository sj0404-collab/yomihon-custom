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

    // Пресет цепочки фолбэков:
    //  auto      — умный порядок: онлайн при сети, локальные без сети
    //  online    — только онлайн-движки (GLENS -> ZEN_FREE -> GOOGLE)
    //  offline   — только локальные (TESSERACT -> FAST -> LEGACY)
    //  single    — без фолбэков, только выбранный движок
    fun fallbackPreset() = preferenceStore.getString("pref_fallback_preset", "auto")

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

    // ---- Авточтение (браузер и читалка) ----
    // Язык, который читаем; всё остальное на кадре игнорируется:
    // ru / en / ja / ko / zh / any
    fun autoReadLanguage() = preferenceStore.getString("pref_autoread_language", "ru")

    // Переводить ли реплики на русский перед озвучкой (для en/ja/…)
    fun autoReadTranslate() = preferenceStore.getBoolean("pref_autoread_translate", true)

    // Автолистание после дочитывания кадра (в браузере — автоскролл на кадр)
    fun autoReadAutoAdvance() = preferenceStore.getBoolean("pref_autoread_advance", true)

    // AI-определение пола говорящего (Gemini Vision по лицам и баллонам):
    // женские реплики читает женский голос-пресет, мужские — мужской.
    // Требует Google AI ключ; выключено по умолчанию (онлайн, медленнее).
    fun aiGenderVoices() = preferenceStore.getBoolean("pref_ai_gender_voices", false)

    // Целевой язык перевода перед озвучкой. Раньше был жёстко "ru" в коде
    // AutoReadEngine, из-за чего англоязычный пользователь получал русскую
    // озвучку независимо от настроек.
    fun translateTarget() = preferenceStore.getString("pref_translate_target", "ru")

    // ---- Офлайн-распознавание (Tesseract, модели в APK) ----
    // Языки распознавания: eng+rus | rus | eng (оба .traineddata лежат в APK)
    fun tessLangs() = preferenceStore.getString("pref_tess_langs", "eng+rus")

    // Режим сегментации страницы Tesseract:
    // single_block (баллон целиком, дефолт) | auto | sparse | single_line
    fun tessPsm() = preferenceStore.getString("pref_tess_psm", "single_block")

    // Апскейл мелких кропов: минимальная короткая сторона в px (0 = выкл).
    // Tesseract резко лучше читает текст, когда буквы >= ~20px.
    fun tessUpscaleMinSide() = preferenceStore.getInt("pref_tess_upscale", 320)

    // Предобработка перед распознаванием: ч/б + усиление контраста
    fun tessPreprocess() = preferenceStore.getBoolean("pref_tess_preprocess", true)

    // Держать офлайн-модели распакованными между сессиями:
    // быстрее старт движка, но ~8МБ постоянно на диске (иначе — только
    // tar.xz внутри APK, извлечение при каждом первом использовании).
    fun keepOfflinePacks() = preferenceStore.getBoolean("pref_keep_offline_packs", false)

    // ---- Онлайн AI-ассистент (пол говорящих, помощь читалке) ----
    // Провайдер: zen (без ключа) | openrouter (нужен ключ)
    fun aiProvider() = preferenceStore.getString("pref_ai_provider", "zen")

    // Модель Zen (opencode.ai/zen, бесплатные *-free, работают без ключа)
    fun zenModel() = preferenceStore.getString("pref_zen_model", "mimo-v2.5-free")

    // Бесплатная модель OpenRouter (суффикс :free)
    fun openrouterFreeModel() = preferenceStore.getString("pref_openrouter_free_model", "")

    // ---- Вид подсветки реплики ----
    // Цвет рамки/подчёркивания текущей реплики (ARGB). По умолчанию — бирюзовый,
    // как на скриншотах пользователя.
    fun highlightColor() = preferenceStore.getLong("pref_highlight_color", 0xFF00E5FFL)

    // Стиль: box (рамка) | underline (подчёркивание) | both
    fun highlightStyle() = preferenceStore.getString("pref_highlight_style", "box")

    // Толщина рамки/линии в dp
    fun highlightWidth() = preferenceStore.getFloat("pref_highlight_width", 3f)

    // Показывать номера реплик на странице (порядок чтения). Номера видны
    // глазами, но TTS их не произносит — см. SpeechMarkup.
    fun showSpeechNumbers() = preferenceStore.getBoolean("pref_show_speech_numbers", true)

    // Разные голоса разным персонажам одного пола в сцене
    fun perSpeakerVoices() = preferenceStore.getBoolean("pref_per_speaker_voices", true)

    // Local Model Management (модели НЕ входят в APK — по умолчанию не установлены,
    // из коробки работают только онлайн-движки)
    fun isMangaOcrDownloaded() = preferenceStore.getBoolean("pref_model_manga_ocr_downloaded", false)
    fun isFastOcrDownloaded() = preferenceStore.getBoolean("pref_model_fast_ocr_downloaded", false)
    fun isPanelDetectorDownloaded() = preferenceStore.getBoolean("pref_model_panel_detector_downloaded", false)
}
