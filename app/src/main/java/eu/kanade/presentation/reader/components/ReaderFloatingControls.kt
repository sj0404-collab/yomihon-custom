package eu.kanade.presentation.reader.components

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import mihon.domain.ocr.service.ScanRegion
import kotlin.math.roundToInt

/**
 * SAO-стиль: единственная перемещаемая плавающая кнопка. Тап (со звуком)
 * раскрывает вертикальное меню со всеми действиями: OCR (с выбором области),
 * автопрокрутка со скоростью, настройки озвучки. Кнопку можно перетащить
 * в любое место экрана — позиция сохраняется, пока открыта читалка.
 */
@Composable
fun ReaderFloatingControls(
    visible: Boolean,
    onTriggerOcr: () -> Unit,
    onOpenOcrSettings: () -> Unit,
    onOpenAiChat: () -> Unit = {},
    onScanRegionChange: (ScanRegion) -> Unit,
    onAutoscrollToggle: (Boolean, Float) -> Unit,
    onAutoSpeakPage: () -> Unit = {},
    onStopSpeak: () -> Unit = {},
    onReadingOrderChange: (String) -> Unit = {},
    readingOrder: String = "rtl",
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showRegions by remember { mutableStateOf(false) }
    var isAutoscrollActive by remember { mutableStateOf(false) }
    var autoscrollSpeed by remember { mutableFloatStateOf(2f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Короткий SAO-подобный "бип" на открытие/закрытие меню и действия
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_SYSTEM, 55) }.getOrNull() }
    DisposableEffect(Unit) {
        onDispose { runCatching { tone?.release() } }
    }
    fun beepOpen() = runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60) }
    fun beepAction() = runCatching { tone?.startTone(ToneGenerator.TONE_PROP_ACK, 70) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                    .padding(16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Раскрывающееся меню (SAO): столбец пунктов над кнопкой
                AnimatedVisibility(
                    visible = menuOpen,
                    enter = fadeIn() + scaleIn(initialScale = 0.8f),
                    exit = fadeOut() + scaleOut(targetScale = 0.8f),
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.End,
                        ) {
                            if (showRegions) {
                                Text(
                                    "Область сканирования",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                SmallFloatingActionButton(onClick = {
                                    beepAction()
                                    onScanRegionChange(ScanRegion.FULL_PAGE)
                                    showRegions = false; menuOpen = false
                                    onTriggerOcr()
                                }) { Text("  100% страница  ") }
                                SmallFloatingActionButton(onClick = {
                                    beepAction()
                                    onScanRegionChange(ScanRegion.TOP_HALF)
                                    showRegions = false; menuOpen = false
                                    onTriggerOcr()
                                }) { Text("  ⬆ Верхние 50%  ") }
                                SmallFloatingActionButton(onClick = {
                                    beepAction()
                                    onScanRegionChange(ScanRegion.BOTTOM_HALF)
                                    showRegions = false; menuOpen = false
                                    onTriggerOcr()
                                }) { Text("  ⬇ Нижние 50%  ") }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("OCR скан  ", style = MaterialTheme.typography.labelMedium)
                                    SmallFloatingActionButton(onClick = {
                                        beepAction()
                                        showRegions = true
                                    }) {
                                        Icon(Icons.Outlined.DocumentScanner, contentDescription = "OCR")
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (isAutoscrollActive) "Стоп прокрутки  " else "Автопрокрутка  ",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    SmallFloatingActionButton(onClick = {
                                        beepAction()
                                        isAutoscrollActive = !isAutoscrollActive
                                        onAutoscrollToggle(isAutoscrollActive, autoscrollSpeed)
                                    }) {
                                        Icon(
                                            if (isAutoscrollActive) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                            contentDescription = "Автопрокрутка",
                                            tint = if (isAutoscrollActive) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                    }
                                }
                                if (isAutoscrollActive) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Speed, contentDescription = null)
                                        Slider(
                                            value = autoscrollSpeed,
                                            onValueChange = {
                                                autoscrollSpeed = it
                                                onAutoscrollToggle(true, autoscrollSpeed)
                                            },
                                            valueRange = 1f..10f,
                                            modifier = Modifier.width(140.dp),
                                        )
                                        Text("×${autoscrollSpeed.roundToInt()}")
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Прочитать страницу  ", style = MaterialTheme.typography.labelMedium)
                                    SmallFloatingActionButton(onClick = {
                                        beepAction()
                                        menuOpen = false
                                        onAutoSpeakPage()
                                    }) {
                                        Icon(Icons.Outlined.PlayArrow, contentDescription = "Прочитать страницу")
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Стоп чтения  ", style = MaterialTheme.typography.labelMedium)
                                    SmallFloatingActionButton(onClick = {
                                        beepAction()
                                        onStopSpeak()
                                    }) {
                                        Icon(Icons.Outlined.Pause, contentDescription = "Стоп чтения")
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val orderLabel = when (readingOrder) {
                                        "ltr" -> "→ Слева направо"
                                        "vertical" -> "↓ Сверху вниз"
                                        else -> "← Справа налево"
                                    }
                                    Text("$orderLabel  ", style = MaterialTheme.typography.labelMedium)
                                    SmallFloatingActionButton(onClick = {
                                        beepAction()
                                        val next = when (readingOrder) {
                                            "rtl" -> "ltr"
                                            "ltr" -> "vertical"
                                            else -> "rtl"
                                        }
                                        onReadingOrderChange(next)
                                    }) {
                                        Icon(Icons.Outlined.DocumentScanner, contentDescription = "Порядок чтения")
                                    }
                                }
                                // AI-чат убран из читалки: теперь он —
                                // отдельная вкладка «AI» в нижней навигации.
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Озвучка (TTS)  ", style = MaterialTheme.typography.labelMedium)
                                    SmallFloatingActionButton(onClick = {
                                        beepAction()
                                        menuOpen = false
                                        onOpenOcrSettings()
                                    }) {
                                        Icon(Icons.Outlined.RecordVoiceOver, contentDescription = "Озвучка")
                                    }
                                }
                            }
                        }
                    }
                }

                // Главная кнопка: тап — меню, перетаскивание — перемещение
                FloatingActionButton(
                    onClick = {
                        beepOpen()
                        if (menuOpen) showRegions = false
                        menuOpen = !menuOpen
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offsetX = (offsetX + dragAmount.x).coerceAtMost(0f)
                            offsetY = (offsetY + dragAmount.y).coerceAtMost(0f)
                        }
                    },
                ) {
                    Icon(
                        if (menuOpen) Icons.Outlined.Close else Icons.Outlined.Menu,
                        contentDescription = "Меню читалки",
                    )
                }
            }
        }
    }
}
