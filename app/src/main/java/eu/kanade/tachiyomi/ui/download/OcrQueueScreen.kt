package eu.kanade.tachiyomi.ui.download

import android.view.LayoutInflater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import kotlinx.collections.immutable.toPersistentList
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.service.OcrPreferences
import mihon.domain.ocr.service.ScanRegion
import mihon.feature.ocr.titleRes
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.Scaffold
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
        val voiceNamePref = remember { ocrPreferences.voiceName() }
        val voiceName by voiceNamePref.changes().collectAsState(initial = voiceNamePref.get())
        val scanRegionPref = remember { ocrPreferences.scanRegion() }
        val scanRegion by scanRegionPref.changes().collectAsState(initial = scanRegionPref.get())
        val isMangaOcrDownPref = remember { ocrPreferences.isMangaOcrDownloaded() }
        val isMangaOcrDown by isMangaOcrDownPref.changes().collectAsState(initial = isMangaOcrDownPref.get())
        val isFastOcrDownPref = remember { ocrPreferences.isFastOcrDownloaded() }
        val isFastOcrDown by isFastOcrDownPref.changes().collectAsState(initial = isFastOcrDownPref.get())
        val isPanelDetectorDownPref = remember { ocrPreferences.isPanelDetectorDownloaded() }
        val isPanelDetectorDown by isPanelDetectorDownPref.changes().collectAsState(initial = isPanelDetectorDownPref.get())

        // Синхронизация флагов с реальным наличием файлов на диске
        androidx.compose.runtime.LaunchedEffect(Unit) {
            isMangaOcrDownPref.set(eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.isPackInstalled(context, "manga_ocr"))
            isFastOcrDownPref.set(eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.isPackInstalled(context, "manga_ocr_fast"))
            isPanelDetectorDownPref.set(eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.isPackInstalled(context, "panel_detector"))
        }

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

        Scaffold(
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
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
                                        .copy(alpha = pillAlpha),
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
                    visible = hasQueue,
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
                    .padding(contentPadding)
                    .nestedScroll(nestedScrollConnection),
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

                PreferenceGroupHeader(title = "Управление локальными OCR-моделями")
                InfoWidget(
                    text = "Модели хранятся снаружи приложения (Android/data/…/files/ocr_models) и не увеличивают размер APK. " +
                        "Их также можно положить вручную в папку Yomihon/OCR на внутренней памяти.",
                )
                SwitchPreferenceWidget(
                    checked = isMangaOcrDown,
                    title = "Manga OCR (Full Float32, ~120 МБ)",
                    subtitle = if (isMangaOcrDown) "Модель установлена • Выключите чтобы удалить файлы" else "Модель не установлена • Включите чтобы скачать",
                    onCheckedChanged = { checked ->
                        if (checked) {
                            eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.downloadPack(context, "manga_ocr") { ok ->
                                isMangaOcrDownPref.set(ok)
                            }
                        } else {
                            eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.deletePack(context, "manga_ocr")
                            isMangaOcrDownPref.set(false)
                        }
                    },
                )
                SwitchPreferenceWidget(
                    checked = isFastOcrDown,
                    title = "Fast Manga OCR (ARM FP16, ~30 МБ)",
                    subtitle = if (isFastOcrDown) "Модель установлена • Выключите чтобы удалить файлы" else "Модель не установлена • Включите чтобы скачать",
                    onCheckedChanged = { checked ->
                        if (checked) {
                            eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.downloadPack(context, "manga_ocr_fast") { ok ->
                                isFastOcrDownPref.set(ok)
                            }
                        } else {
                            eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.deletePack(context, "manga_ocr_fast")
                            isFastOcrDownPref.set(false)
                        }
                    },
                )
                SwitchPreferenceWidget(
                    checked = isPanelDetectorDown,
                    title = "Panel Detector (YOLO, ~6 МБ)",
                    subtitle = if (isPanelDetectorDown) "Модель установлена • Выключите чтобы удалить файлы" else "Модель не установлена • Включите чтобы скачать",
                    onCheckedChanged = { checked ->
                        if (checked) {
                            eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.downloadPack(context, "panel_detector") { ok ->
                                isPanelDetectorDownPref.set(ok)
                            }
                        } else {
                            eu.kanade.tachiyomi.data.ocr.OcrModelDownloader.deletePack(context, "panel_detector")
                            isPanelDetectorDownPref.set(false)
                        }
                    },
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

                PreferenceGroupHeader(title = stringResource(MR.strings.pref_category_voice))
                EditTextPreferenceWidget(
                    title = stringResource(MR.strings.pref_voice_name),
                    subtitle = "Selected voice engine identifier",
                    icon = null,
                    value = voiceName,
                    onConfirm = {
                        voiceNamePref.set(it)
                        true
                    },
                )

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
                        .weight(1f),
                ) {
                    if (!hasQueue) {
                        EmptyScreen(
                            message = stringResource(MR.strings.ocr_queue_empty),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                screenModel.controllerBinding =
                                    DownloadListBinding.inflate(LayoutInflater.from(context))
                                screenModel.adapter = OcrAdapter(screenModel.listener)
                                screenModel.controllerBinding.root.adapter = screenModel.adapter
                                screenModel.adapter?.isHandleDragEnabled = true
                                screenModel.controllerBinding.root.layoutManager = LinearLayoutManager(context)

                                ViewCompat.setNestedScrollingEnabled(screenModel.controllerBinding.root, true)

                                screenModel.controllerBinding.root
                            },
                            update = {
                                screenModel.adapter?.updateDataSet(state.items)
                            },
                        )
                    }
                }
            }
        }
    }
}
