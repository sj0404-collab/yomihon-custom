package eu.kanade.presentation.more.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.data.ocr.OcrModelDownloader
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class OcrModelStep : OnboardingStep {

    override val isComplete: Boolean = true

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val ocrPreferences = remember { Injekt.get<OcrPreferences>() }
        var selectedChoice by remember { mutableStateOf(1) } // 0 = All, 1 = None (default), 2 = Fast only

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Загрузка локальных OCR-моделей",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = "Выберите вариант установки офлайн-моделей для распознавания текста. Вы всегда сможете скачать или удалить их позже в настройках OCR.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Choice 1: Download All
            Card(
                onClick = {
                    selectedChoice = 0
                    OcrModelDownloader.downloadPack(context, "manga_ocr") { ok ->
                        ocrPreferences.isMangaOcrDownloaded().set(ok)
                    }
                    OcrModelDownloader.downloadPack(context, "manga_ocr_fast") { ok ->
                        ocrPreferences.isFastOcrDownloaded().set(ok)
                    }
                    OcrModelDownloader.downloadPack(context, "panel_detector") { ok ->
                        ocrPreferences.isPanelDetectorDownloaded().set(ok)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedChoice == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(
                        selected = selectedChoice == 0,
                        onClick = null,
                    )
                    Column {
                        Text(
                            text = "1. Скачать все локальные модели",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Скачать Manga OCR, Fast OCR и Panel Detector (~160 МБ) в папку приложения для работы офлайн. APK останется лёгким — модели хранятся снаружи.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Choice 2: Do Not Download
            Card(
                onClick = {
                    selectedChoice = 1
                    ocrPreferences.isMangaOcrDownloaded().set(false)
                    ocrPreferences.isFastOcrDownloaded().set(false)
                    ocrPreferences.isPanelDetectorDownloaded().set(false)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedChoice == 1) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(
                        selected = selectedChoice == 1,
                        onClick = null,
                    )
                    Column {
                        Text(
                            text = "2. Не скачивать локальные модели",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Рекомендуется. Используются бесплатные онлайн ИИ-модели (Zen Free, Google Lens, Gemini, OpenRouter). Локальные модели можно доустановить позже в Настройки → OCR.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // Choice 3: Select One
            Card(
                onClick = {
                    selectedChoice = 2
                    ocrPreferences.isMangaOcrDownloaded().set(false)
                    ocrPreferences.isPanelDetectorDownloaded().set(false)
                    OcrModelDownloader.downloadPack(context, "manga_ocr_fast") { ok ->
                        ocrPreferences.isFastOcrDownloaded().set(ok) // Fast OCR only
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedChoice == 2) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(
                        selected = selectedChoice == 2,
                        onClick = null,
                    )
                    Column {
                        Text(
                            text = "3. Скачать одну легкую модель (Fast ARM)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Скачать только быструю модель Fast OCR (~30 МБ) для базовой работы без интернета.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
