package eu.kanade.tachiyomi.ui.reader.html

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.webkit.JavascriptInterface
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.ocr.OcrModelDownloader
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.data.ocr.OcrPageSourceResolver
import eu.kanade.tachiyomi.data.ocr.ResolvedOcrPages
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import mihon.domain.manga.model.toDomainManga
import mihon.domain.ocr.interactor.ScanPageOcr
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.service.OcrPreferences
import mihon.domain.ocr.service.ScanRegion
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.Constants
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.updates.interactor.GetUpdates
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.util.Date
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
    private val getManga: GetManga by lazy { Injekt.get() }
    private val getChapter: GetChapter by lazy { Injekt.get() }
    private val getChaptersByMangaId: GetChaptersByMangaId by lazy { Injekt.get() }
    private val updateChapter: UpdateChapter by lazy { Injekt.get() }
    private val upsertHistory: UpsertHistory by lazy { Injekt.get() }
    private val coverCache: CoverCache by lazy { Injekt.get() }
    private val scanPageOcr: ScanPageOcr by lazy { Injekt.get() }
    private val pageSourceResolver: OcrPageSourceResolver by lazy { Injekt.get() }

    private var readerPages: ResolvedOcrPages? = null
    private var readerChapterId: Long = -1L

    private val sourceManager: SourceManager by lazy { Injekt.get() }
    private val networkToLocalManga: NetworkToLocalManga by lazy { Injekt.get() }
    private val updateManga: UpdateManga by lazy { Injekt.get() }
    private val updateMangaFromRemote: UpdateMangaFromRemote by lazy { Injekt.get() }
    private val downloadManager: DownloadManager by lazy { Injekt.get() }
    private val extensionManager: ExtensionManager by lazy { Injekt.get() }
    private val getCategories: GetCategories by lazy { Injekt.get() }
    private val setMangaCategories: SetMangaCategories by lazy { Injekt.get() }
    private val createCategoryWithName: CreateCategoryWithName by lazy { Injekt.get() }

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val taskResults = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val taskCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val extensionSteps = java.util.concurrent.ConcurrentHashMap<String, String>()

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



    // region Browse: каталоги источников, поиск, добавление в библиотеку — всё в HTML

    /** Список включённых каталожных источников. */
    @JavascriptInterface
    fun getSources(): String {
        val array = JSONArray()
        runCatching {
            sourceManager.getOnlineSources()
                .filterIsInstance<CatalogueSource>()
                .sortedBy { it.name.lowercase() }
                .forEach { src ->
                    array.put(
                        JSONObject().apply {
                            put("id", src.id.toString())
                            put("name", src.name)
                            put("lang", src.lang)
                            put("supportsLatest", src.supportsLatest)
                        },
                    )
                }
        }
        return array.toString()
    }

    /**
     * Каталог источника: mode = popular | latest | search.
     * Манги сразу сохраняются в БД (insertNetworkManga) — фронт получает стабильные id.
     */
    @JavascriptInterface
    fun browseSource(sourceIdStr: String, mode: String, query: String, page: Int): String {
        return runCatching {
            runBlocking {
                // ID источников — 63-битные Long и НЕ влезают в точность чисел JS,
                // поэтому принимаем их строкой.
                val sourceId = sourceIdStr.toLongOrNull()
                    ?: return@runBlocking """{"error":"некорректный id источника"}"""
                val source = sourceManager.get(sourceId) as? CatalogueSource
                    ?: return@runBlocking """{"error":"источник не найден"}"""
                val mangasPage = when (mode) {
                    "latest" -> source.getLatestUpdates(page)
                    "search" -> source.getSearchManga(page, query, FilterList())
                    else -> source.getPopularManga(page)
                }
                val local = networkToLocalManga(mangasPage.mangas.map { it.toDomainManga(sourceId) })
                JSONObject().apply {
                    put("hasNextPage", mangasPage.hasNextPage)
                    put(
                        "items",
                        JSONArray().apply {
                            local.forEach { m ->
                                put(
                                    JSONObject().apply {
                                        put("mangaId", m.id)
                                        put("title", m.title)
                                        put("thumbnailUrl", m.thumbnailUrl ?: "")
                                        put("favorite", m.favorite)
                                    },
                                )
                            }
                        },
                    )
                }.toString()
            }
        }.getOrElse { e -> """{"error":${JSONObject.quote(e.message ?: "ошибка сети")}}""" }
    }

    /** Добавить/убрать из библиотеки. Возвращает новое состояние favorite. */
    @JavascriptInterface
    fun toggleFavorite(mangaId: Long): Boolean {
        return runCatching {
            runBlocking {
                val manga = getManga.await(mangaId) ?: return@runBlocking false
                val now = !manga.favorite
                updateManga.await(
                    MangaUpdate(
                        id = mangaId,
                        favorite = now,
                        dateAdded = if (now) System.currentTimeMillis() else null,
                    ),
                )
                now
            }
        }.getOrDefault(false)
    }

    /** Обновить детали и главы манги из источника (как pull-to-refresh в нативе). */
    @JavascriptInterface
    fun refreshManga(mangaId: Long): String {
        return runCatching {
            runBlocking {
                val manga = getManga.await(mangaId) ?: return@runBlocking "not_found"
                updateMangaFromRemote(
                    manga = manga,
                    fetchDetails = true,
                    fetchChapters = true,
                    manualFetch = true,
                ).fold(
                    onSuccess = { "ok:" + it.newChapters.size },
                    onFailure = { "error:" + (it.message ?: "сбой") },
                )
            }
        }.getOrDefault("error:internal")
    }

    // endregion

    // region Async tasks: точная индикация загрузки без блокировки JS-потока

    /**
     * Запускает загрузку каталога в фоне и сразу возвращает id задачи.
     * Раньше browseSource выполнялся синхронно в JS-потоке WebView — интерфейс
     * замирал, а спиннер «Загрузка…» не соответствовал реальному состоянию.
     * Теперь фронт опрашивает pollTask(id) и индикатор честный.
     */
    @JavascriptInterface
    fun startBrowseSource(sourceIdStr: String, mode: String, query: String, page: Int): Int {
        val taskId = taskCounter.incrementAndGet()
        ioScope.launch {
            val result = runCatching { browseSource(sourceIdStr, mode, query, page) }
                .getOrElse { e -> """{"error":${JSONObject.quote(e.message ?: "ошибка сети")}}""" }
            taskResults[taskId] = result
        }
        return taskId
    }

    /** Пустая строка = задача ещё выполняется; иначе — готовый JSON (одноразово). */
    @JavascriptInterface
    fun pollTask(taskId: Int): String {
        val r = taskResults[taskId] ?: return ""
        taskResults.remove(taskId)
        return r
    }

    // endregion

    // region Extensions: список, установка и удаление прямо из PWA (как в нативе)

    /** Обновляет список доступных расширений с репозиториев. Возвращает id задачи. */
    @JavascriptInterface
    fun refreshExtensions(): Int {
        val taskId = taskCounter.incrementAndGet()
        ioScope.launch {
            runCatching { extensionManager.findAvailableExtensions() }
            taskResults[taskId] = "done"
        }
        return taskId
    }

    /**
     * Установленные + доступные для скачивания расширения одним JSON.
     * Тот же ExtensionManager, что и у нативного экрана — никаких заглушек.
     */
    @JavascriptInterface
    fun getExtensionsList(): String {
        return runCatching {
            val installed = extensionManager.installedExtensionsFlow.value
            val installedPkgs = installed.map { it.pkgName }.toSet()
            val available = extensionManager.availableExtensionsFlow.value
            val untrusted = extensionManager.untrustedExtensionsFlow.value
            JSONObject().apply {
                put(
                    "installed",
                    JSONArray().apply {
                        installed.sortedBy { it.name.lowercase() }.forEach { ext ->
                            put(
                                JSONObject().apply {
                                    put("pkgName", ext.pkgName)
                                    put("name", ext.name)
                                    put("versionName", ext.versionName)
                                    put("lang", ext.lang)
                                    put("isNsfw", ext.isNsfw)
                                    put("hasUpdate", ext.hasUpdate)
                                    put("isObsolete", ext.isObsolete)
                                    put("step", extensionSteps[ext.pkgName] ?: "")
                                },
                            )
                        }
                    },
                )
                put(
                    "available",
                    JSONArray().apply {
                        available
                            .filter { it.pkgName !in installedPkgs }
                            .sortedWith(compareBy({ it.lang != "ru" && it.lang != "all" }, { it.name.lowercase() }))
                            .forEach { ext ->
                                put(
                                    JSONObject().apply {
                                        put("pkgName", ext.pkgName)
                                        put("name", ext.name)
                                        put("versionName", ext.versionName)
                                        put("lang", ext.lang)
                                        put("isNsfw", ext.isNsfw)
                                        put("iconUrl", ext.iconUrl)
                                        put("step", extensionSteps[ext.pkgName] ?: "")
                                    },
                                )
                            }
                    },
                )
                put(
                    "untrusted",
                    JSONArray().apply {
                        untrusted.forEach { ext ->
                            put(JSONObject().apply { put("pkgName", ext.pkgName); put("name", ext.name) })
                        }
                    },
                )
            }.toString()
        }.getOrDefault("""{"installed":[],"available":[],"untrusted":[]}""")
    }

    /**
     * Скачивает и устанавливает расширение. Прогресс (Pending/Downloading/Installing/
     * Installed/Error) фронт читает через getExtensionsList — поле step.
     * Финальная установка APK идёт через системный инсталлятор, как в нативе.
     */
    @JavascriptInterface
    fun installExtension(pkgName: String): Boolean {
        val ext = extensionManager.availableExtensionsFlow.value.find { it.pkgName == pkgName }
            ?: return false
        extensionSteps[pkgName] = InstallStep.Pending.name
        ioScope.launch {
            extensionManager.installExtension(ext)
                .onEach { step ->
                    extensionSteps[pkgName] = step.name
                    if (step == InstallStep.Installed || step == InstallStep.Error) {
                        // держим финальный статус недолго, чтобы фронт успел показать
                        launch {
                            kotlinx.coroutines.delay(4000)
                            if (extensionSteps[pkgName] == step.name) extensionSteps.remove(pkgName)
                        }
                    }
                }
                .catch { extensionSteps[pkgName] = InstallStep.Error.name }
                .collect()
        }
        return true
    }

    @JavascriptInterface
    fun updateExtension(pkgName: String): Boolean {
        val ext = extensionManager.installedExtensionsFlow.value.find { it.pkgName == pkgName }
            ?: return false
        extensionSteps[pkgName] = InstallStep.Pending.name
        ioScope.launch {
            extensionManager.updateExtension(ext)
                .onEach { step -> extensionSteps[pkgName] = step.name }
                .catch { extensionSteps[pkgName] = InstallStep.Error.name }
                .collect()
        }
        return true
    }

    @JavascriptInterface
    fun uninstallExtension(pkgName: String) {
        val ext = extensionManager.installedExtensionsFlow.value.find { it.pkgName == pkgName }
            ?: return
        extensionManager.uninstallExtension(ext)
    }

    @JavascriptInterface
    fun cancelExtensionInstall(pkgName: String) {
        val ext = extensionManager.availableExtensionsFlow.value.find { it.pkgName == pkgName }
            ?: extensionManager.installedExtensionsFlow.value.find { it.pkgName == pkgName }
            ?: return
        extensionManager.cancelInstallUpdateExtension(ext)
        extensionSteps.remove(pkgName)
    }

    // endregion

    // region Downloads & categories — управление из HTML

    @JavascriptInterface
    fun downloadChapters(mangaId: Long, chapterIdsCsv: String): Boolean {
        return runCatching {
            runBlocking {
                val manga = getManga.await(mangaId) ?: return@runBlocking false
                val ids = chapterIdsCsv.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
                val chapters = getChaptersByMangaId.await(mangaId).filter { it.id in ids }
                if (chapters.isEmpty()) return@runBlocking false
                downloadManager.downloadChapters(manga, chapters, autoStart = true)
                true
            }
        }.getOrDefault(false)
    }

    @JavascriptInterface
    fun getDownloadedChapterIds(mangaId: Long): String {
        val array = JSONArray()
        runCatching {
            runBlocking {
                val manga = getManga.await(mangaId) ?: return@runBlocking
                getChaptersByMangaId.await(mangaId).forEach { c ->
                    if (downloadManager.isChapterDownloaded(c.name, c.scanlator, c.url, manga.title, manga.source)) {
                        array.put(c.id)
                    }
                }
            }
        }
        return array.toString()
    }

    @JavascriptInterface
    fun getCategoriesList(): String {
        val array = JSONArray()
        runCatching {
            runBlocking {
                getCategories.await().forEach { c ->
                    array.put(JSONObject().apply { put("id", c.id); put("name", c.name) })
                }
            }
        }
        return array.toString()
    }

    @JavascriptInterface
    fun getMangaCategories(mangaId: Long): String {
        val array = JSONArray()
        runCatching {
            runBlocking { getCategories.await(mangaId).forEach { array.put(it.id) } }
        }
        return array.toString()
    }

    @JavascriptInterface
    fun setMangaCategoriesCsv(mangaId: Long, categoryIdsCsv: String) {
        runCatching {
            runBlocking {
                val ids = categoryIdsCsv.split(',').mapNotNull { it.trim().toLongOrNull() }
                setMangaCategories.await(mangaId, ids)
            }
        }
    }

    @JavascriptInterface
    fun createCategory(name: String): Boolean {
        return runCatching { runBlocking { createCategoryWithName.await(name) }; true }.getOrDefault(false)
    }

    /** Обложка по прямому URL через нативный HTTP-клиент источника недоступна из WebView (CORS/referer) — грузим здесь. */
    @JavascriptInterface
    fun fetchCoverUrl(sourceIdStr: String, url: String): String {
        return runCatching {
            runBlocking {
                if (url.isBlank()) return@runBlocking ""
                val source = sourceIdStr.toLongOrNull()
                    ?.let { sourceManager.get(it) } as? eu.kanade.tachiyomi.source.online.HttpSource
                val client = source?.client ?: Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client
                val reqBuilder = okhttp3.Request.Builder().url(url)
                source?.headers?.let { reqBuilder.headers(it) }
                val response = client.newCall(reqBuilder.build()).execute()
                response.use {
                    if (!it.isSuccessful) return@runBlocking ""
                    val bytes = it.body.bytes()
                    if (bytes.size > 3_000_000) return@runBlocking ""
                    val mime = it.header("Content-Type") ?: "image/jpeg"
                    "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                }
            }
        }.getOrDefault("")
    }

    // endregion

    // region In-HTML reader: manga details, chapter pages, progress, per-page OCR

    @JavascriptInterface
    fun getMangaDetails(mangaId: Long): String {
        return runCatching {
            runBlocking {
                val manga = getManga.await(mangaId) ?: return@runBlocking "{}"
                val chapters = getChaptersByMangaId.await(mangaId, applyScanlatorFilter = true)
                    .sortedWith(getChapterSort(manga, sortDescending = true))
                JSONObject().apply {
                    put("id", manga.id)
                    put("title", manga.title)
                    put("author", manga.author ?: "")
                    put("artist", manga.artist ?: "")
                    put("description", manga.description ?: "")
                    put("genres", JSONArray(manga.genre.orEmpty()))
                    put("status", manga.status)
                    put("favorite", manga.favorite)
                    put(
                        "chapters",
                        JSONArray().apply {
                            chapters.forEach { c ->
                                put(
                                    JSONObject().apply {
                                        put("id", c.id)
                                        put("name", c.name)
                                        put("chapterNumber", c.chapterNumber)
                                        put("read", c.read)
                                        put("bookmark", c.bookmark)
                                        put("lastPageRead", c.lastPageRead)
                                        put("dateUpload", c.dateUpload)
                                        put("scanlator", c.scanlator ?: "")
                                    },
                                )
                            }
                        },
                    )
                }.toString()
            }
        }.getOrDefault("{}")
    }

    /** Обложка манги из кэша как data-URI (пустая строка если ещё не закэширована). */
    @JavascriptInterface
    fun getCover(mangaId: Long): String {
        return runCatching {
            runBlocking {
                val manga = getManga.await(mangaId) ?: return@runBlocking ""
                val custom = coverCache.getCustomCoverFile(mangaId)
                val file = if (custom.exists()) custom else coverCache.getCoverFile(manga.thumbnailUrl)
                if (file == null || !file.exists() || file.length() == 0L) return@runBlocking ""
                val bytes = file.readBytes()
                val mime = if (bytes.size > 4 && bytes[1] == 'P'.code.toByte()) "image/png" else "image/jpeg"
                "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }.getOrDefault("")
    }

    /** Открывает главу для HTML-читалки. Возвращает количество страниц (0 = ошибка). */
    @JavascriptInterface
    fun openChapterPages(chapterId: Long): Int {
        return runCatching {
            runBlocking {
                val chapter = getChapter.await(chapterId) ?: return@runBlocking 0
                val manga = getManga.await(chapter.mangaId) ?: return@runBlocking 0
                readerPages?.close()
                readerPages = pageSourceResolver.resolve(manga, chapter)
                readerChapterId = chapterId
                readerPages?.pages?.size ?: 0
            }
        }.getOrDefault(0)
    }

    /** Страница открытой главы как JPEG data-URI (скачивается/читается лениво). */
    @JavascriptInterface
    fun getPageImage(pageIndex: Int): String {
        return runCatching {
            runBlocking {
                val input = readerPages?.getPageInput(pageIndex) ?: return@runBlocking ""
                val bitmap = input.openBitmap() ?: return@runBlocking ""
                try {
                    val maxWidth = 1440
                    val scaled = if (bitmap.width > maxWidth) {
                        val h = (bitmap.height.toLong() * maxWidth / bitmap.width).toInt().coerceAtLeast(1)
                        Bitmap.createScaledBitmap(bitmap, maxWidth, h, true)
                    } else {
                        bitmap
                    }
                    val output = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)
                    if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
                    "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        }.getOrDefault("")
    }

    @JavascriptInterface
    fun closeChapterPages() {
        runCatching { readerPages?.close() }
        readerPages = null
        readerChapterId = -1L
    }

    /** Сохраняет прогресс чтения + историю (та же БД, что у нативной читалки). */
    @JavascriptInterface
    fun saveChapterProgress(chapterId: Long, lastPageRead: Int, completed: Boolean) {
        runCatching {
            runBlocking {
                updateChapter.await(
                    ChapterUpdate(
                        id = chapterId,
                        lastPageRead = lastPageRead.toLong(),
                        read = if (completed) true else null,
                    ),
                )
                upsertHistory.await(HistoryUpdate(chapterId, Date(), 0L))
            }
        }
    }

    @JavascriptInterface
    fun setChapterRead(chapterId: Long, read: Boolean) {
        runCatching {
            runBlocking {
                updateChapter.await(
                    ChapterUpdate(id = chapterId, read = read, lastPageRead = if (!read) 0L else null),
                )
            }
        }
    }

    @JavascriptInterface
    fun setChapterBookmark(chapterId: Long, bookmark: Boolean) {
        runCatching {
            runBlocking { updateChapter.await(ChapterUpdate(id = chapterId, bookmark = bookmark)) }
        }
    }

    /** OCR текущей страницы открытой главы выбранным движком (онлайн по умолчанию). */
    @JavascriptInterface
    fun ocrPage(pageIndex: Int): String {
        return runCatching {
            runBlocking {
                val input = readerPages?.getPageInput(pageIndex) ?: return@runBlocking ""
                val bitmap = input.openBitmap() ?: return@runBlocking ""
                try {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val result = scanPageOcr.await(
                        readerChapterId,
                        pageIndex,
                        OcrImage(bitmap.width, bitmap.height, pixels),
                    )
                    result.regions.joinToString("\n") { it.text }.trim()
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        }.getOrDefault("")
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
