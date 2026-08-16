package eu.kanade.tachiyomi.ui.reader.html

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.ocr.OcrModelDownloader
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.service.OcrPreferences
import mihon.domain.ocr.service.ScanRegion
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.Constants
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.updates.interactor.GetUpdates
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * JavaScript bridge that exposes the FULL native Yomihon ecosystem to the PWA
 * WebView: real library, updates, history, reader navigation, OCR/TTS settings
 * and local model management. The PWA UI is a first-class client of the same
 * domain layer the native UI uses — no mock data.
 */
class YomihonWebBridge(
    private val context: Context,
    private val onTriggerScan: () -> Unit,
    private val onOpenSafFolder: () -> Unit,
    private val onOpenCbzFile: () -> Unit,
) {
    private val ocrPreferences: OcrPreferences by lazy { Injekt.get() }
    private val getLibraryManga: GetLibraryManga by lazy { Injekt.get() }
    private val getUpdates: GetUpdates by lazy { Injekt.get() }
    private val getHistory: GetHistory by lazy { Injekt.get() }
    private val getNextChapters: GetNextChapters by lazy { Injekt.get() }

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var ttsEngine: TextToSpeech? = null

    init {
        ttsEngine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsEngine?.language = Locale("ru", "RU")
            }
        }
    }

    // region App info

    @JavascriptInterface
    fun getAppInfo(): String {
        return JSONObject().apply {
            put("versionName", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("commitSha", BuildConfig.COMMIT_SHA)
            put("buildTime", BuildConfig.BUILD_TIME)
        }.toString()
    }

    // endregion

    // region Library / Updates / History (real data from the domain layer)

    @JavascriptInterface
    fun getLibrary(): String {
        val array = JSONArray()
        runCatching {
            val library = runBlocking { getLibraryManga.await() }
                .sortedByDescending { it.lastRead }
            for (item in library) {
                array.put(
                    JSONObject().apply {
                        put("mangaId", item.manga.id)
                        put("title", item.manga.title)
                        put("unreadCount", item.unreadCount)
                        put("totalChapters", item.totalChapters)
                        put("readCount", item.readCount)
                        put("bookmarkCount", item.bookmarkCount)
                        put("hasStarted", item.hasStarted)
                        put("lastRead", item.lastRead)
                        put("latestUpload", item.latestUpload)
                    },
                )
            }
        }
        return array.toString()
    }

    @JavascriptInterface
    fun getUpdatesList(): String {
        val array = JSONArray()
        runCatching {
            val after = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90)
            val updates = runBlocking { getUpdates.await(read = false, after = after) }
            for (u in updates.take(60)) {
                array.put(
                    JSONObject().apply {
                        put("mangaId", u.mangaId)
                        put("mangaTitle", u.mangaTitle)
                        put("chapterId", u.chapterId)
                        put("chapterName", u.chapterName)
                        put("scanlator", u.scanlator ?: "")
                        put("read", u.read)
                        put("bookmark", u.bookmark)
                        put("dateFetch", u.dateFetch)
                    },
                )
            }
        }
        return array.toString()
    }

    @JavascriptInterface
    fun getHistoryList(): String {
        val array = JSONArray()
        runCatching {
            val history = runBlocking { getHistory.subscribe("").first() }
            for (h in history.take(60)) {
                array.put(
                    JSONObject().apply {
                        put("mangaId", h.mangaId)
                        put("chapterId", h.chapterId)
                        put("title", h.title)
                        put("chapterNumber", h.chapterNumber)
                        put("readAt", h.readAt?.time ?: 0L)
                        put("readDuration", h.readDuration)
                    },
                )
            }
        }
        return array.toString()
    }

    // endregion

    // region Navigation into native screens

    @JavascriptInterface
    fun openManga(mangaId: Long) {
        runCatching {
            mainScope.launch { HomeScreen.showNativeUi() }
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Constants.SHORTCUT_MANGA
                putExtra(Constants.MANGA_EXTRA, mangaId)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }

    @JavascriptInterface
    fun openChapter(mangaId: Long, chapterId: Long) {
        runCatching {
            context.startActivity(ReaderActivity.newIntent(context, mangaId, chapterId))
        }
    }

    /** Continues reading: resolves the next unread chapter natively and opens the reader. */
    @JavascriptInterface
    fun continueReading(mangaId: Long): Boolean {
        return runCatching {
            val next = runBlocking { getNextChapters.await(mangaId, onlyUnread = true) }.firstOrNull()
                ?: return false
            context.startActivity(ReaderActivity.newIntent(context, mangaId, next.id))
            true
        }.getOrDefault(false)
    }

    /** Continues the most recent reading session across the whole library. */
    @JavascriptInterface
    fun continueLastRead(): Boolean {
        return runCatching {
            val next = runBlocking { getNextChapters.await(onlyUnread = true) }.firstOrNull()
                ?: return false
            context.startActivity(ReaderActivity.newIntent(context, next.mangaId, next.id))
            true
        }.getOrDefault(false)
    }

    @JavascriptInterface
    fun openNativeScreen(screen: String) {
        // ВАЖНО: при usePwaMode HomeScreen всегда рисует PWA поверх нативных
        // вкладок, поэтому интентов недостаточно — сначала временно скрываем PWA.
        val tab: HomeScreen.Tab = when (screen) {
            "library" -> HomeScreen.Tab.Library()
            "updates" -> HomeScreen.Tab.Updates
            "history" -> HomeScreen.Tab.History
            "browse" -> HomeScreen.Tab.Browse(toExtensions = false)
            "extensions" -> HomeScreen.Tab.Browse(toExtensions = true)
            "downloads" -> {
                openNativeMore()
                return
            }
            else -> return
        }
        mainScope.launch {
            HomeScreen.showNativeUi()
            HomeScreen.openTab(tab)
        }
    }

    /** Открывает нативный раздел "Ещё" (очереди загрузок/OCR, настройки). */
    @JavascriptInterface
    fun openNativeMore() {
        mainScope.launch {
            HomeScreen.showNativeUi()
            HomeScreen.openTab(HomeScreen.Tab.More(toDownloads = true))
        }
    }

    /** Возврат из нативного UI обратно в PWA (используется системной кнопкой Назад). */
    @JavascriptInterface
    fun returnToPwa() {
        mainScope.launch { HomeScreen.returnToPwa() }
    }

    // endregion

    // region OCR settings (same preferences the native UI uses)

    @JavascriptInterface
    fun getOcrSettings(): String {
        return JSONObject().apply {
            put("model", ocrPreferences.ocrModel().get().name)
            put("scanRegion", ocrPreferences.scanRegion().get().name)
            put("useFallback", ocrPreferences.useFallbackModels().get())
            put("zenFreeEnabled", ocrPreferences.zenFreeEnabled().get())
            put("tokenUsage", ocrPreferences.tokenUsageCount().get())
            put("owocrAddress", ocrPreferences.owocrAddress().get())
            put("openrouterModel", ocrPreferences.openrouterModel().get())
            put("googleModel", ocrPreferences.googleModel().get())
            put("hasOpenrouterKey", ocrPreferences.openrouterApiKey().get().isNotBlank())
            put("hasGoogleKey", ocrPreferences.googleApiKey().get().isNotBlank())
            put("mangaOcrInstalled", OcrModelDownloader.isPackInstalled(context, "manga_ocr"))
            put("fastOcrInstalled", OcrModelDownloader.isPackInstalled(context, "manga_ocr_fast"))
            put("panelDetectorInstalled", OcrModelDownloader.isPackInstalled(context, "panel_detector"))
            put("voiceName", ocrPreferences.voiceName().get())
            put("speechRate", ocrPreferences.speechRate().get())
        }.toString()
    }

    @JavascriptInterface
    fun setOcrModel(modelName: String) {
        runCatching {
            val model = OcrModel.valueOf(modelName)
            ocrPreferences.ocrModel().set(model)
            context.toast("Модель OCR изменена на $modelName")
        }
    }

    @JavascriptInterface
    fun setScanRegion(regionName: String) {
        runCatching {
            val region = ScanRegion.valueOf(regionName)
            ocrPreferences.scanRegion().set(region)
            context.toast("Область сканирования изменена на $regionName")
        }
    }

    @JavascriptInterface
    fun setUseFallback(enabled: Boolean) {
        ocrPreferences.useFallbackModels().set(enabled)
    }

    @JavascriptInterface
    fun setApiKey(provider: String, key: String) {
        when (provider) {
            "openrouter" -> ocrPreferences.openrouterApiKey().set(key)
            "google" -> ocrPreferences.googleApiKey().set(key)
        }
        context.toast("API-ключ сохранён")
    }

    @JavascriptInterface
    fun setOwocrAddress(address: String) {
        ocrPreferences.owocrAddress().set(address)
    }

    /** Downloads a local model pack (stored OUTSIDE the APK, in ocr_models/). */
    @JavascriptInterface
    fun downloadModelPack(pack: String) {
        OcrModelDownloader.downloadPack(context, pack) { ok ->
            when (pack) {
                "manga_ocr" -> ocrPreferences.isMangaOcrDownloaded().set(ok)
                "manga_ocr_fast" -> ocrPreferences.isFastOcrDownloaded().set(ok)
                "panel_detector" -> ocrPreferences.isPanelDetectorDownloaded().set(ok)
            }
        }
    }

    @JavascriptInterface
    fun deleteModelPack(pack: String) {
        OcrModelDownloader.deletePack(context, pack)
        when (pack) {
            "manga_ocr" -> ocrPreferences.isMangaOcrDownloaded().set(false)
            "manga_ocr_fast" -> ocrPreferences.isFastOcrDownloaded().set(false)
            "panel_detector" -> ocrPreferences.isPanelDetectorDownloaded().set(false)
        }
    }

    // endregion

    // region Scan / pickers

    @JavascriptInterface
    fun scanCurrentPage() {
        onTriggerScan()
    }

    @JavascriptInterface
    fun openSafFolderPicker() {
        onOpenSafFolder()
    }

    @JavascriptInterface
    fun openCbzFilePicker() {
        onOpenCbzFile()
    }

    // endregion

    // region Text-to-speech

    @JavascriptInterface
    fun getSystemVoices(): String {
        val array = JSONArray()
        runCatching {
            val voices = ttsEngine?.voices.orEmpty()
            for (voice in voices) {
                val obj = JSONObject().apply {
                    put("name", voice.name)
                    put("label", "${voice.locale.displayName} (${voice.name})")
                }
                array.put(obj)
            }
        }
        if (array.length() == 0) {
            array.put(JSONObject().apply {
                put("name", "ru-ru-x-dfa-network")
                put("label", "Русский (Нейросетевой голос)")
            })
            array.put(JSONObject().apply {
                put("name", "ru-ru-x-sfg-local")
                put("label", "Русский (Системный локальный)")
            })
        }
        return array.toString()
    }

    @JavascriptInterface
    fun setVoice(voiceName: String, rate: Float) {
        ocrPreferences.voiceName().set(voiceName)
        ocrPreferences.speechRate().set(rate.coerceIn(0.5f, 2.0f))
    }

    @JavascriptInterface
    fun speakText(text: String, voiceName: String, rate: Float) {
        runCatching {
            ttsEngine?.let { tts ->
                tts.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
                val targetVoice = tts.voices?.find { it.name == voiceName }
                if (targetVoice != null) {
                    tts.voice = targetVoice
                }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "html_tts")
            }
        }
    }

    @JavascriptInterface
    fun stopSpeech() {
        runCatching {
            ttsEngine?.stop()
        }
    }

    // endregion
}
