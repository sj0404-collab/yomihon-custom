package eu.kanade.presentation.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.tts.AutoReadEngine
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt

/**
 * «Линейка чтения»: подсветка текущей озвучиваемой реплики.
 *
 * Вид настраивается (Настройки → Озвучка):
 * - [OcrPreferences.highlightColor] — цвет рамки/линии;
 * - [OcrPreferences.highlightStyle] — `box` (рамка), `underline`
 *   (подчёркивание) или `both`;
 * - [OcrPreferences.highlightWidth] — толщина в dp.
 *
 * Номер реплики показывается рядом с рамкой: он нужен глазами, чтобы видеть
 * порядок чтения, но в озвучку не попадает (снимается SpeechMarkup.strip).
 */
@Composable
fun AutoReadHighlight(
    region: AutoReadEngine.SpokenRegion,
    modifier: Modifier = Modifier,
) {
    val prefs = remember { Injekt.get<OcrPreferences>() }
    val accent = remember { Color(prefs.highlightColor().get().toULong().toLong()) }
    val style = remember { prefs.highlightStyle().get() }
    val strokeWidth = remember { prefs.highlightWidth().get().coerceIn(1f, 12f) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val density = LocalDensity.current

        val boxWidth = with(density) { ((region.box.right - region.box.left) * w).toDp() }
        val boxHeight = with(density) { ((region.box.bottom - region.box.top) * h).toDp() }
        val offsetModifier = Modifier.offset {
            IntOffset(
                (region.box.left * w).roundToInt(),
                (region.box.top * h).roundToInt(),
            )
        }

        if (style == "box" || style == "both") {
            Box(
                modifier = offsetModifier
                    .width(boxWidth)
                    .height(boxHeight)
                    .border(
                        width = strokeWidth.dp,
                        color = accent,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .background(accent.copy(alpha = 0.14f), RoundedCornerShape(6.dp)),
            )
        }

        if (style == "underline" || style == "both") {
            // Подчёркивание: линия по нижней границе реплики.
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (region.box.left * w).roundToInt(),
                            (region.box.bottom * h).roundToInt(),
                        )
                    }
                    .width(boxWidth)
                    .height(strokeWidth.dp)
                    .background(accent, RoundedCornerShape(strokeWidth.dp / 2)),
            )
        }

        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 12.dp, end = 12.dp, bottom = 96.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
            ),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row {
                    Text(
                        "🔊 ${region.index}/${region.total}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (region.marks.isNotBlank()) {
                        Text(
                            "  ${region.marks}",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    region.translated ?: region.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
