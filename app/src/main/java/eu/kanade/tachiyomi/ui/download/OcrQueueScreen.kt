package eu.kanade.tachiyomi.ui.download

import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.settings.widget.EditTextPreferenceWidget
import eu.kanade.presentation.more.settings.widget.InfoWidget
import eu.kanade.presentation.more.settings.widget.ListPreferenceWidget
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.tts.TtsSpeaker
import eu.kanade.tachiyomi.data.tts.VoiceHelper
import eu.kanade.tachiyomi.data.tts.VoiceKind
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.service.OcrPreferences
import mihon.domain.ocr.service.ScanRegion
import mihon.feature.ocr.titleRes
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object OcrQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val context = androidx.compose.ui.platform.LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { OcrQueueScreenModel() }
        val state by screenModel.state.collectAsState()
        val isQueueRunning by screenModel.isQueueRunning.collectAsState()
        val hasQueue = state.totalCount > 0
        val ocrPreferences = remember { Injekt.get<OcrPreferences>() }

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
        var fabExpanded by remember { mutableStateOf(true) }
        val nestedScrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    fabExpanded = available.y >= 0
                    return scrollBehavior.nestedScrollConnection.onPreScroll(available, source)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    return scrollBehavior.nestedScrollConnection.onPostScroll(consumed, available, source)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPreFling(available)
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPostFling(consumed, available)
                }
            }
        }

        var currentTab by remember { mutableIntStateOf(0) }

        tachiyomi.presentation.core.components.material.Scaffold(
            topBar = {
                AppBar(
                    titleContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(MR.strings.label_text_recognition),
                                maxLines = 1,
                                modifier = Modifier.weight(1f, false),
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (state.totalCount > 0) {
                                val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
                                Pill(
                                    text = state.totalCount.toString(),
                                    modifier = Modifier.padding(start = 4.dp),
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = pillAlpha),
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    },
                    navigateUp = navigator::pop,
                    actions = {
                        if (hasQueue) {
                            AppBarActions(
                                listOf(
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_cancel_all),
                                        onClick = screenModel::clearQueue,
                                    ),
                                ).toPersistentList(),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = hasQueue && currentTab == 0,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    ExtendedFloatingActionButton(
                        text = {
                            Text(
                                text = stringResource(
                                    if (isQueueRunning) MR.strings.action_pause else MR.strings.action_resume,
                                ),
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = if (isQueueRunning) Icons.Outlined.Pause else Icons.Filled.PlayArrow,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            if (isQueueRunning) {
                                screenModel.pauseQueue()
                            } else {
                                screenModel.resumeQueue()
                            }
                        },
                        expanded = fabExpanded,
                    )
                }
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                PrimaryTabRow(selectedTabIndex = currentTab) {
                    Tab(
                        selected = currentTab == 0,
                        onClick = { currentTab = 0 },
                        text = { Text("Распознавание") },
                    )
                    Tab(
                        selected = currentTab == 1,
                        onClick = { currentTab = 1 },
                        text = { Text("Голоса") },
                    )
                }
                when (currentTab) {
                    0 -> RecognitionTab(
                        screenModel = screenModel,
                        hasQueue = hasQueue,
                        stateItems = state.items,
                        ocrPreferences = ocrPreferences,
                        nestedScrollConnection = nestedScrollConnection,
                    )
                    else -> VoicesTab(ocrPreferences = ocrPreferences)
                }
            }
        }
    }

    /**
     * Строка модельного пака с ЖИВЫМ ИНДИКАТОРОМ (по требованию пользователя):
     *  • не установлен — кнопка «Скачать» с размером;
     *  • качается — LinearProgressIndicator с процентами (реальные байты);
     *  • установлен — галочка, реальный размер на диске, кнопка «Удалить».
     */
    @Composable
    private fun ModelPackRow(
        pack: String,
        title: String,
        sizeHint: String,
        installed: Boolean,
        onInstalledChange: (Boolean) -> Unit,
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val progressMap by eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.progress
            .collectAsState()
        val progress = progressMap[pack]
        val downloading = progress != null

        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            downloading -> "Загрузка… ${(progress!! * 100).toInt()}%"
                            installed -> {
                                val bytes = eu.kanade.tachiyomi.data.ocr.OcrModelDownloader
                                    .installedSize(context, pack)
                                val mb = if (bytes > 0) "${bytes / 1048576} МБ на диске" else sizeHint
                                "✅ Установлена • $mb"
                            }
                            else -> "$sizeHint • не установлена"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    downloading -> androidx.compose.material3.CircularProgressIndicator(
                        progress = { progress!! },
                        modifier = Modifier.padding(8.dp).size(28.dp),
                    )
                    installed -> androidx.compose.material3.TextButton(
                        onClick = {
                            eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.deletePack(context, pack)
                            onInstalledChange(false)
                        },
                    ) { Text("Удалить") }
                    else -> androidx.compose.material3.FilledTonalButton(
                        onClick = {
                            eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.downloadPack(context, pack) { ok ->
                                onInstalledChange(ok)
                            }
                        },
                    ) { Text("Скачать") }
                }
            }
            if (downloading) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress!! },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }

    // region Вкладка «Распознавание» (OCR + офлайн-модели + очередь)

    @Composable
    private fun RecognitionTab(
        screenModel: OcrQueueScreenModel,
        hasQueue: Boolean,
        stateItems: List<OcrItem>,
        ocrPreferences: OcrPreferences,
        nestedScrollConnection: NestedScrollConnection,
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current

        val ocrModelPreference = remember { ocrPreferences.ocrModel() }
        val ocrModel by ocrModelPreference.changes().collectAsState(initial = ocrModelPreference.get())
        val autoOcrOnDownloadPreference = remember { ocrPreferences.autoOcrOnDownload() }
        val autoOcrOnDownload by autoOcrOnDownloadPreference
            .changes()
            .collectAsState(initial = autoOcrOnDownloadPreference.get())
        val owocrAddressPreference = remember { ocrPreferences.owocrAddress() }
        val owocrAddress by owocrAddressPreference
            .changes()
            .collectAsState(initial = owocrAddressPreference.get())
        val useFallbackModelsPreference = remember { ocrPreferences.useFallbackModels() }
        val useFallbackModels by useFallbackModelsPreference
            .changes()
            .collectAsState(initial = useFallbackModelsPreference.get())
        val openrouterKeyPref = remember { ocrPreferences.openrouterApiKey() }
        val openrouterKey by openrouterKeyPref.changes().collectAsState(initial = openrouterKeyPref.get())
        val googleKeyPref = remember { ocrPreferences.googleApiKey() }
        val googleKey by googleKeyPref.changes().collectAsState(initial = googleKeyPref.get())
        val tokenCountPref = remember { ocrPreferences.tokenUsageCount() }
        val tokenCount by tokenCountPref.changes().collectAsState(initial = tokenCountPref.get())
        val scanRegionPref = remember { ocrPreferences.scanRegion() }
        val scanRegion by scanRegionPref.changes().collectAsState(initial = scanRegionPref.get())
        val isMangaOcrDownPref = remember { ocrPreferences.isMangaOcrDownloaded() }
        val isMangaOcrDown by isMangaOcrDownPref.changes().collectAsState(initial = isMangaOcrDownPref.get())
        val isFastOcrDownPref = remember { ocrPreferences.isFastOcrDownloaded() }
        val isFastOcrDown by isFastOcrDownPref.changes().collectAsState(initial = isFastOcrDownPref.get())
        val isPanelDetectorDownPref = remember { ocrPreferences.isPanelDetectorDownloaded() }
        val isPanelDetectorDown by isPanelDetectorDownPref.changes()
            .collectAsState(initial = isPanelDetectorDownPref.get())

        // Офлайн-Tesseract: расширенные настройки
        val tessLangsPref = remember { ocrPreferences.tessLangs() }
        val tessLangs by tessLangsPref.changes().collectAsState(initial = tessLangsPref.get())
        val tessPsmPref = remember { ocrPreferences.tessPsm() }
        val tessPsm by tessPsmPref.changes().collectAsState(initial = tessPsmPref.get())
        val tessUpscalePref = remember { ocrPreferences.tessUpscaleMinSide() }
        val tessUpscale by tessUpscalePref.changes().collectAsState(initial = tessUpscalePref.get())
        val tessPreprocessPref = remember { ocrPreferences.tessPreprocess() }
        val tessPreprocess by tessPreprocessPref.changes().collectAsState(initial = tessPreprocessPref.get())
        val keepPacksPref = remember { ocrPreferences.keepOfflinePacks() }
        val keepPacks by keepPacksPref.changes().collectAsState(initial = keepPacksPref.get())

        // Синхронизация флагов с реальным наличием файлов на диске
        LaunchedEffect(Unit) {
            isMangaOcrDownPref.set(
                eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.isPackInstalled(context, "manga_ocr"),
            )
            isFastOcrDownPref.set(
                eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.isPackInstalled(context, "manga_ocr_fast"),
            )
            isPanelDetectorDownPref.set(
                eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.isPackInstalled(context, "panel_detector"),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
                .verticalScroll(rememberScrollState()),
        ) {
            PreferenceGroupHeader(title = stringResource(MR.strings.label_settings))
            ListPreferenceWidget(
                value = scanRegion,
                title = "Область сканирования страницы",
                subtitle = when (scanRegion) {
                    ScanRegion.FULL_PAGE -> "Сканировать всю страницу целиком (100%)"
                    ScanRegion.TOP_HALF -> "Сканировать верхнюю часть страницы (Top 50%)"
                    ScanRegion.BOTTOM_HALF -> "Сканировать нижнюю часть страницы (Bottom 50%)"
                },
                icon = null,
                entries = mapOf(
                    ScanRegion.FULL_PAGE to "1. Вся страница целиком (100%)",
                    ScanRegion.TOP_HALF to "2. Верхняя часть страницы (50%)",
                    ScanRegion.BOTTOM_HALF to "3. Нижняя часть страницы (50%)",
                ),
                onValueChange = scanRegionPref::set,
            )

            ListPreferenceWidget(
                value = ocrModel,
                title = stringResource(MR.strings.pref_ocr_model),
                subtitle = stringResource(ocrModel.titleRes),
                icon = null,
                entries = mapOf(
                    OcrModel.LEGACY to stringResource(OcrModel.LEGACY.titleRes),
                    OcrModel.FAST to stringResource(OcrModel.FAST.titleRes),
                    OcrModel.GLENS to stringResource(OcrModel.GLENS.titleRes),
                    OcrModel.OWOCR to stringResource(OcrModel.OWOCR.titleRes),
                    OcrModel.OPENROUTER to stringResource(OcrModel.OPENROUTER.titleRes),
                    OcrModel.GOOGLE to stringResource(OcrModel.GOOGLE.titleRes),
                    OcrModel.ZEN_FREE to stringResource(OcrModel.ZEN_FREE.titleRes),
                    OcrModel.TESSERACT to stringResource(OcrModel.TESSERACT.titleRes),
                ),
                onValueChange = ocrModelPreference::set,
            )

            run {
                val fallbackPresetPref = remember { ocrPreferences.fallbackPreset() }
                val fallbackPreset by fallbackPresetPref.changes()
                    .collectAsState(initial = fallbackPresetPref.get())
                ListPreferenceWidget(
                    value = fallbackPreset,
                    title = "Фолбэк при сбое движка",
                    subtitle = when (fallbackPreset) {
                        "online" -> "Только онлайн: Lens → Zen → Gemini"
                        "offline" -> "Только локальные: Tesseract → Fast → Legacy"
                        "single" -> "Без фолбэков — только выбранный движок"
                        else -> "Авто: при сети — онлайн, без сети — локальные"
                    },
                    icon = null,
                    entries = mapOf(
                        "auto" to "Авто (умный выбор по сети)",
                        "online" to "Только онлайн-движки",
                        "offline" to "Только локальные движки",
                        "single" to "Один движок, без фолбэков",
                    ),
                    onValueChange = fallbackPresetPref::set,
                )
            }

            PreferenceGroupHeader(title = "Офлайн-распознавание (Tesseract)")
            InfoWidget(
                text = "Работает полностью без сети: модели eng+rus встроены в APK (tar.xz, 2.9 МБ) " +
                    "и извлекаются только при использовании движка.",
            )
            ListPreferenceWidget(
                value = tessLangs,
                title = "Языки распознавания",
                subtitle = when (tessLangs) {
                    "rus" -> "Только русский — быстрее, если текст точно русский"
                    "eng" -> "Только английский — быстрее, если текст точно английский"
                    "eng+rus" -> "Русский + английский (по умолчанию)"
                    else -> tessLangs + " (языки без установленного пака пропускаются)"
                },
                icon = null,
                entries = buildMap {
                    put("eng+rus", "Русский + английский")
                    put("rus", "Только русский")
                    put("eng", "Только английский")
                    // Скачанные языковые паки добавляют варианты
                    eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.TESS_LANG_PACKS.forEach { (pack, code, label) ->
                        if (eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.isPackInstalled(context, pack)) {
                            put(code, label.substringBefore(" •"))
                            put("$code+rus", label.substringBefore(" •") + " + русский")
                        }
                    }
                },
                onValueChange = tessLangsPref::set,
            )

            PreferenceGroupHeader(title = "Языковые паки Tesseract (скачать один раз)")
            InfoWidget(
                text = "Официальные модели tessdata_fast. После установки язык появляется " +
                    "в списке «Языки распознавания» выше. Русский и английский уже встроены в APK.",
            )
            eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.TESS_LANG_PACKS.forEach { (pack, _, label) ->
                var installed by remember(pack) {
                    mutableStateOf(eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.isPackInstalled(context, pack))
                }
                ModelPackRow(
                    pack = pack,
                    title = label.substringBefore(" •"),
                    sizeHint = label.substringAfter("• "),
                    installed = installed,
                    onInstalledChange = { installed = it },
                )
            }
            ListPreferenceWidget(
                value = tessPsm,
                title = "Сегментация страницы",
                subtitle = when (tessPsm) {
                    "auto" -> "Авто — Tesseract сам ищет блоки текста"
                    "sparse" -> "Разреженный текст — надписи разбросаны по странице"
                    "single_line" -> "Одна строка — для узких горизонтальных кропов"
                    else -> "Один блок — лучший режим для баллонов манги"
                },
                icon = null,
                entries = mapOf(
                    "single_block" to "Один блок (баллоны манги)",
                    "auto" to "Авто (вся страница)",
                    "sparse" to "Разреженный текст (звуки, надписи)",
                    "single_line" to "Одна строка",
                ),
                onValueChange = tessPsmPref::set,
            )
            SliderItem(
                value = tessUpscale,
                valueRange = 0..640,
                steps = 9, // позиции: 0, 64, 128, …, 640
                label = "Апскейл мелкого текста",
                valueString = if (tessUpscale == 0) "выкл" else "до $tessUpscale px",
                onChange = { tessUpscalePref.set((it / 64) * 64) },
            )
            SwitchPreferenceWidget(
                checked = tessPreprocess,
                title = "Предобработка изображения",
                subtitle = "Ч/б + контраст: убирает цветные фоны баллонов, повышает точность",
                onCheckedChanged = tessPreprocessPref::set,
            )
            SwitchPreferenceWidget(
                checked = keepPacks,
                title = "Держать модели распакованными",
                subtitle = if (keepPacks) {
                    "Быстрый старт движка • ~8 МБ постоянно на диске"
                } else {
                    "Экономия места: в покое только tar.xz в APK, извлечение при использовании"
                },
                onCheckedChanged = keepPacksPref::set,
            )

            PreferenceGroupHeader(title = "Управление локальными OCR-моделями")
            InfoWidget(
                text = "Хранятся вне APK (Android/data/…/files/ocr_models или Yomihon/OCR). " +
                    "Tesseract (офлайн) всегда доступен — его модели встроены в APK.",
            )
            ModelPackRow(
                pack = "manga_ocr",
                title = "Manga OCR (Legacy) — точный, японский",
                sizeHint = "~120 МБ",
                installed = isMangaOcrDown,
                onInstalledChange = { isMangaOcrDownPref.set(it) },
            )
            ModelPackRow(
                pack = "manga_ocr_fast",
                title = "Fast Manga OCR — быстрый, японский",
                sizeHint = "~30 МБ",
                installed = isFastOcrDown,
                onInstalledChange = { isFastOcrDownPref.set(it) },
            )
            ModelPackRow(
                pack = "panel_detector",
                title = "Panel Detector — YOLO-детектор панелей",
                sizeHint = "~6 МБ",
                installed = isPanelDetectorDown,
                onInstalledChange = { isPanelDetectorDownPref.set(it) },
            )
            if (ocrModel == OcrModel.OWOCR) {
                EditTextPreferenceWidget(
                    title = stringResource(MR.strings.pref_owocr_address),
                    subtitle = stringResource(MR.strings.pref_owocr_address_summary),
                    icon = null,
                    value = owocrAddress,
                    onConfirm = {
                        owocrAddressPreference.set(it)
                        true
                    },
                )
                InfoWidget(text = stringResource(MR.strings.pref_owocr_address_note))
            }
            if (ocrModel == OcrModel.OPENROUTER) {
                EditTextPreferenceWidget(
                    title = "OpenRouter API Key",
                    subtitle = "Key for OpenRouter vision model access",
                    icon = null,
                    value = openrouterKey,
                    onConfirm = {
                        openrouterKeyPref.set(it)
                        true
                    },
                )
            }
            if (ocrModel == OcrModel.GOOGLE) {
                EditTextPreferenceWidget(
                    title = "Google AI API Key",
                    subtitle = "Key for Gemini Vision model access",
                    icon = null,
                    value = googleKey,
                    onConfirm = {
                        googleKeyPref.set(it)
                        true
                    },
                )
            }
            if (ocrModel == OcrModel.ZEN_FREE) {
                InfoWidget(text = stringResource(MR.strings.zen_free_status_label))
            }

            run {
                val aiTabPref = remember { ocrPreferences.aiTabVisible() }
                val aiTabVisible by aiTabPref.changes().collectAsState(initial = aiTabPref.get())
                SwitchPreferenceWidget(
                    checked = aiTabVisible,
                    title = "Вкладка «AI» в нижней панели",
                    subtitle = if (aiTabVisible) {
                        "Встроенный AI-агент виден в навигации"
                    } else {
                        "Скрыта — агент доступен через внешний браузер (порт 8765)"
                    },
                    onCheckedChanged = aiTabPref::set,
                )
            }

            PreferenceGroupHeader(title = stringResource(MR.strings.pref_token_usage))
            InfoWidget(text = stringResource(MR.strings.token_indicator_label, tokenCount))
            SwitchPreferenceWidget(
                checked = autoOcrOnDownload,
                title = stringResource(MR.strings.pref_auto_ocr_on_download),
                onCheckedChanged = autoOcrOnDownloadPreference::set,
            )
            SwitchPreferenceWidget(
                checked = useFallbackModels,
                title = stringResource(MR.strings.pref_use_fallback_models),
                subtitle = stringResource(MR.strings.pref_use_fallback_models_summary),
                onCheckedChanged = useFallbackModelsPreference::set,
            )

            PreferenceGroupHeader(title = stringResource(MR.strings.ocr_queue_header))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (hasQueue) 420.dp else 160.dp),
            ) {
                if (!hasQueue) {
                    EmptyScreen(
                        message = stringResource(MR.strings.ocr_queue_empty),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            screenModel.controllerBinding =
                                DownloadListBinding.inflate(LayoutInflater.from(ctx))
                            screenModel.adapter = OcrAdapter(screenModel.listener)
                            screenModel.controllerBinding.root.adapter = screenModel.adapter
                            screenModel.adapter?.isHandleDragEnabled = true
                            screenModel.controllerBinding.root.layoutManager = LinearLayoutManager(ctx)

                            ViewCompat.setNestedScrollingEnabled(screenModel.controllerBinding.root, true)

                            screenModel.controllerBinding.root
                        },
                        update = {
                            screenModel.adapter?.updateDataSet(stateItems)
                        },
                    )
                }
            }
        }
    }

    // endregion

    // region Вкладка «Голоса» (офлайн и онлайн, реальные данные)

    private data class VoiceRow(
        val name: String,
        val kindLabel: String,
        val isOffline: Boolean,
    )

    @Composable
    private fun VoicesTab(ocrPreferences: OcrPreferences) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val scope2 = androidx.compose.runtime.rememberCoroutineScope()

        val voiceEnginePref = remember { ocrPreferences.voiceEngine() }
        val voiceEngine by voiceEnginePref.changes().collectAsState(initial = voiceEnginePref.get())
        val voiceNamePref = remember { ocrPreferences.voiceName() }
        val voiceName by voiceNamePref.changes().collectAsState(initial = voiceNamePref.get())
        val voiceFemalePref = remember { ocrPreferences.voiceFemale() }
        val voiceFemale by voiceFemalePref.changes().collectAsState(initial = voiceFemalePref.get())
        val voiceMalePref = remember { ocrPreferences.voiceMale() }
        val voiceMale by voiceMalePref.changes().collectAsState(initial = voiceMalePref.get())
        val speechRatePref = remember { ocrPreferences.speechRate() }
        val speechRate by speechRatePref.changes().collectAsState(initial = speechRatePref.get())
        val speechPitchPref = remember { ocrPreferences.speechPitch() }
        val speechPitch by speechPitchPref.changes().collectAsState(initial = speechPitchPref.get())
        val webLangPref = remember { ocrPreferences.ttsWebLanguage() }
        val webLang by webLangPref.changes().collectAsState(initial = webLangPref.get())
        val elevenKeyPref = remember { ocrPreferences.elevenApiKey() }
        val elevenKey by elevenKeyPref.changes().collectAsState(initial = elevenKeyPref.get())
        val elevenVoiceIdPref = remember { ocrPreferences.elevenVoiceId() }
        val elevenVoiceId by elevenVoiceIdPref.changes().collectAsState(initial = elevenVoiceIdPref.get())

        // Живой probe системного TTS: реальные голоса устройства
        var probe by remember { mutableStateOf<TextToSpeech?>(null) }
        var probeReady by remember { mutableStateOf(false) }
        DisposableEffect(Unit) {
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) probe = tts
                probeReady = true
            }
            onDispose {
                runCatching { tts?.shutdown() }
            }
        }

        var langFilter by remember { mutableStateOf("ru") }
        var assignMode by remember { mutableIntStateOf(0) } // 0=основной, 1=♀, 2=♂

        // Реальный список голосов из системного движка, офлайн/онлайн раздельно
        val allVoices = remember(probe, probeReady, langFilter) {
            runCatching {
                VoiceHelper.voicesFor(probe, langFilter)
                    .sortedWith(
                        compareBy(
                            { it.isNetworkConnectionRequired }, // офлайн вверх
                            {
                                when (VoiceHelper.classify(it)) {
                                    VoiceKind.FEMALE -> 0
                                    VoiceKind.MALE -> 1
                                    VoiceKind.TEEN -> 2
                                    else -> 3
                                }
                            },
                            { it.name },
                        ),
                    )
                    .map { v ->
                        val kind = when (VoiceHelper.classify(v)) {
                            VoiceKind.FEMALE -> "♀ Женский"
                            VoiceKind.MALE -> "♂ Мужской"
                            VoiceKind.TEEN -> "👦 Подросток"
                            else -> "Другой"
                        }
                        VoiceRow(
                            name = v.name,
                            kindLabel = kind,
                            isOffline = !v.isNetworkConnectionRequired,
                        )
                    }
            }.getOrDefault(emptyList())
        }
        val offlineVoices = allVoices.filter { it.isOffline }
        val onlineVoices = allVoices.filter { !it.isOffline }

        // Живой список голосов ElevenLabs по ключу (реальный API, без фейков)
        var elevenVoices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var elevenLoading by remember { mutableStateOf(false) }
        LaunchedEffect(elevenKey, voiceEngine) {
            if (voiceEngine == TtsSpeaker.ENGINE_ELEVENLABS && elevenKey.isNotBlank()) {
                elevenLoading = true
                elevenVoices = TtsSpeaker.fetchElevenVoices(elevenKey)
                elevenLoading = false
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            PreferenceGroupHeader(title = "Движок озвучки")
            ListPreferenceWidget(
                value = voiceEngine,
                title = "Источник голоса",
                subtitle = when (voiceEngine) {
                    TtsSpeaker.ENGINE_GOOGLE_WEB -> "Онлайн: Google Translate, без API-ключа"
                    TtsSpeaker.ENGINE_ELEVENLABS -> "Онлайн: ElevenLabs, нужен API-ключ"
                    else -> "Системный TTS: офлайн- и онлайн-голоса устройства"
                },
                icon = null,
                entries = mapOf(
                    TtsSpeaker.ENGINE_SYSTEM to "📱 Системный TTS (офлайн + онлайн)",
                    TtsSpeaker.ENGINE_ONNX to "🧠 ONNX-нейроголоса (офлайн, скачать один раз)",
                    TtsSpeaker.ENGINE_GOOGLE_WEB to "☁ Google Web (онлайн, без ключа)",
                    TtsSpeaker.ENGINE_ELEVENLABS to "☁ ElevenLabs (онлайн, по ключу)",
                ),
                onValueChange = voiceEnginePref::set,
            )
            SliderItem(
                value = (speechRate * 100).toInt().coerceIn(50, 200),
                valueRange = 50..200,
                steps = 29,
                label = "Скорость речи",
                valueString = "×%.2f".format(speechRate),
                onChange = { speechRatePref.set(it / 100f) },
            )
            SliderItem(
                value = (speechPitch * 100).toInt().coerceIn(50, 200),
                valueRange = 50..200,
                steps = 29,
                label = "Высота голоса",
                valueString = "×%.2f".format(speechPitch),
                onChange = { speechPitchPref.set(it / 100f) },
            )

            if (voiceEngine == TtsSpeaker.ENGINE_ONNX) {
                PreferenceGroupHeader(title = "ONNX-нейроголоса (Piper, офлайн)")
                InfoWidget(
                    text = if (eu.kanade.tachiyomi.data.tts.OnnxTts.isAvailable) {
                        "Нейросетевые голоса: живее системных, работают без сети. " +
                            "Женские реплики читает женский голос, мужские — мужской."
                    } else {
                        "Библиотека sherpa-onnx не попала в эту сборку — движок недоступен. " +
                            "Обновите приложение."
                    },
                )
                val onnxProgress by eu.kanade.tachiyomi.data.tts.OnnxTts.progress.collectAsState()
                val onnxVoicePref = remember { ocrPreferences.onnxVoice() }
                val onnxVoice by onnxVoicePref.changes().collectAsState(initial = onnxVoicePref.get())
                eu.kanade.tachiyomi.data.tts.OnnxTts.CATALOG.forEach { v ->
                    var installed by remember(v.id) {
                        mutableStateOf(eu.kanade.tachiyomi.data.tts.OnnxTts.isInstalled(context, v))
                    }
                    val prog = onnxProgress[v.id]
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    (if (v.gender == "female") "♀ " else "♂ ") + v.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    when {
                                        prog != null -> "Загрузка ${(prog * 100).toInt()}%"
                                        installed -> "✅ Установлен • ${v.sizeMb} МБ"
                                        else -> "${v.sizeMb} МБ • не установлен"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            when {
                                prog != null -> androidx.compose.material3.CircularProgressIndicator(
                                    progress = { prog },
                                    modifier = Modifier.padding(8.dp).size(28.dp),
                                )
                                installed -> Row {
                                    RadioButton(
                                        selected = onnxVoice == v.id,
                                        onClick = { onnxVoicePref.set(v.id) },
                                    )
                                    androidx.compose.material3.TextButton(onClick = {
                                        eu.kanade.tachiyomi.data.tts.OnnxTts.delete(context, v)
                                        installed = false
                                    }) { Text("Удалить") }
                                }
                                else -> androidx.compose.material3.FilledTonalButton(onClick = {
                                    scope2.launch(Dispatchers.IO) {
                                        val ok = eu.kanade.tachiyomi.data.tts.OnnxTts.download(context, v)
                                        withContext(Dispatchers.Main) { installed = ok }
                                    }
                                }) { Text("Скачать") }
                            }
                        }
                        if (prog != null) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { prog },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            if (voiceEngine == TtsSpeaker.ENGINE_SYSTEM) {
                PreferenceGroupHeader(title = "Голоса устройства")
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    listOf("ru" to "Рус", "en" to "Eng", "ja" to "日本", "ko" to "한국", "zh" to "中文").forEach { (code, label) ->
                        FilterChip(
                            selected = langFilter == code,
                            onClick = { langFilter = code },
                            label = { Text(label) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = assignMode == 0,
                        onClick = { assignMode = 0 },
                        label = { Text("Основной") },
                    )
                    FilterChip(
                        selected = assignMode == 1,
                        onClick = { assignMode = 1 },
                        label = { Text("♀ Женский") },
                    )
                    FilterChip(
                        selected = assignMode == 2,
                        onClick = { assignMode = 2 },
                        label = { Text("♂ Мужской") },
                    )
                }
                InfoWidget(
                    text = when (assignMode) {
                        1 -> "Выберите голос для женских реплик: " + voiceFemale.ifBlank { "не задан" }
                        2 -> "Выберите голос для мужских реплик: " + voiceMale.ifBlank { "не задан" }
                        else -> "Основной голос озвучки: " + voiceName.ifBlank { "автоподбор" }
                    },
                )

                when {
                    !probeReady -> InfoWidget(text = "Инициализация системного TTS…")
                    allVoices.isEmpty() -> InfoWidget(
                        text = "Голосов для этого языка не найдено. Установите TTS-движок " +
                            "(Speech Services by Google, RHVoice) в настройках системы.",
                    )
                    else -> {
                        if (offlineVoices.isNotEmpty()) {
                            PreferenceGroupHeader(title = "📱 Офлайн (${offlineVoices.size}) — работают без сети")
                            offlineVoices.forEach { row ->
                                SystemVoiceRow(
                                    row = row,
                                    selected = when (assignMode) {
                                        1 -> voiceFemale == row.name
                                        2 -> voiceMale == row.name
                                        else -> voiceName == row.name
                                    },
                                    onSelect = {
                                        when (assignMode) {
                                            1 -> voiceFemalePref.set(row.name)
                                            2 -> voiceMalePref.set(row.name)
                                            else -> voiceNamePref.set(row.name)
                                        }
                                    },
                                )
                            }
                        }
                        if (onlineVoices.isNotEmpty()) {
                            PreferenceGroupHeader(title = "☁ Онлайн (${onlineVoices.size}) — качественнее, нужна сеть")
                            onlineVoices.forEach { row ->
                                SystemVoiceRow(
                                    row = row,
                                    selected = when (assignMode) {
                                        1 -> voiceFemale == row.name
                                        2 -> voiceMale == row.name
                                        else -> voiceName == row.name
                                    },
                                    onSelect = {
                                        when (assignMode) {
                                            1 -> voiceFemalePref.set(row.name)
                                            2 -> voiceMalePref.set(row.name)
                                            else -> voiceNamePref.set(row.name)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (voiceEngine == TtsSpeaker.ENGINE_GOOGLE_WEB) {
                PreferenceGroupHeader(title = "Google Web TTS")
                InfoWidget(
                    text = "Голос берётся с сайта Google Translate без API-ключа. " +
                        "У этого источника один голос на язык — выбирается только язык.",
                )
                ListPreferenceWidget(
                    value = webLang,
                    title = "Язык озвучки",
                    subtitle = webLang,
                    icon = null,
                    entries = mapOf(
                        "ru" to "Русский",
                        "en" to "English",
                        "ja" to "日本語",
                        "ko" to "한국어",
                        "zh-CN" to "中文",
                        "uk" to "Українська",
                    ),
                    onValueChange = webLangPref::set,
                )
            }

            if (voiceEngine == TtsSpeaker.ENGINE_ELEVENLABS) {
                PreferenceGroupHeader(title = "ElevenLabs")
                EditTextPreferenceWidget(
                    title = "API-ключ ElevenLabs",
                    subtitle = if (elevenKey.isBlank()) "Не задан — без ключа сработает фолбэк на Google Web" else "Задан",
                    icon = null,
                    value = elevenKey,
                    onConfirm = {
                        elevenKeyPref.set(it)
                        true
                    },
                )
                when {
                    elevenKey.isBlank() -> InfoWidget(
                        text = "Введите ключ с elevenlabs.io — список голосов вашего аккаунта загрузится автоматически.",
                    )
                    elevenLoading -> InfoWidget(text = "Загрузка голосов из вашего аккаунта…")
                    elevenVoices.isEmpty() -> InfoWidget(
                        text = "Голоса не загрузились: проверьте ключ и подключение к сети.",
                    )
                    else -> {
                        PreferenceGroupHeader(title = "☁ Голоса аккаунта (${elevenVoices.size})")
                        elevenVoices.forEach { (id, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { elevenVoiceIdPref.set(id) }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                RadioButton(
                                    selected = elevenVoiceId == id,
                                    onClick = { elevenVoiceIdPref.set(id) },
                                )
                                Column {
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        id,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Проба голоса — реальная озвучка текущими настройками
            PreferenceGroupHeader(title = "Проверка")
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = false,
                    onClick = {
                        TtsSpeaker.speak(context, "Проверка голоса. Так будет звучать озвучка Ёмикай.")
                    },
                    label = { Text("▶ Прослушать") },
                )
                FilterChip(
                    selected = false,
                    onClick = { TtsSpeaker.stop() },
                    label = { Text("⏹ Стоп") },
                )
            }
        }
    }

    @Composable
    private fun SystemVoiceRow(
        row: VoiceRow,
        selected: Boolean,
        onSelect: () -> Unit,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
            )
            Column {
                Text(
                    "${row.kindLabel} • ${row.name.substringAfterLast(':')}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    row.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // endregion
}
