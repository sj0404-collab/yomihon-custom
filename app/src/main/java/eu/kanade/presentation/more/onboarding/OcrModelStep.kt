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
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class OcrModelStep : OnboardingStep {

    override val isComplete: Boolean = true

    @Composable
    override fun Content() {
        val ocrPreferences = remember { Injekt.get<OcrPreferences>() }
        var selectedChoice by remember { mutableStateOf(0) } // 0 = All, 1 = None, 2 = Select One

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
                    ocrPreferences.isMangaOcrDownloaded().set(true)
                    ocrPreferences.isFastOcrDownloaded().set(true)
                    ocrPreferences.isPanelDetectorDownloaded().set(true)
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
                            text = "Установить Manga OCR, Fast OCR и Panel Detector для точной работы офлайн.",
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
                            text = "Экономия памяти (~80 МБ). Использовать бесплатные онлайн/ИИ модели (Zen Free, OpenRouter, Gemini).",
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
                    ocrPreferences.isFastOcrDownloaded().set(true) // Fast OCR only
                    ocrPreferences.isPanelDetectorDownloaded().set(false)
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
                            text = "Загрузить только быструю модель Fast OCR для работы без интернета.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
