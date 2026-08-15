package eu.kanade.presentation.reader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.GTranslate
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.util.system.toast
import mihon.data.ocr.CyrillicTranslitFixer
import mihon.data.ocr.MangaTranslatorService
import java.util.Locale

@Composable
fun TranslationCard(
    originalText: String,
    targetLanguage: String = "ru",
    modifier: Modifier = Modifier,
) {
    if (originalText.isBlank()) return

    var translationText by remember(originalText) { mutableStateOf<String?>(null) }
    var restoredCyrillic by remember(originalText) { mutableStateOf<String?>(null) }
    var isTranslating by remember(originalText) { mutableStateOf(true) }
    var isSpeaking by remember(originalText) { mutableStateOf(false) }
    val context = LocalContext.current

    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(Unit) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ru", "RU")
                ttsEngine = tts
            }
        }
        onDispose {
            tts?.stop()
            tts?.shutdown()
        }
    }

    LaunchedEffect(originalText, targetLanguage) {
        isTranslating = true
        val fixedText = CyrillicTranslitFixer.autoFixCyrillic(originalText)
        if (fixedText != originalText) {
            restoredCyrillic = fixedText
        } else {
            restoredCyrillic = null
        }

        val translated = MangaTranslatorService.translate(fixedText, targetLanguage)
        translationText = translated
        isTranslating = false

        // Post translation to system notifications
        showTranslationNotification(context, translated)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            restoredCyrillic?.let { cyrillic ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Spellcheck,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "Восстановленный русский текст (Кириллица):",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Text(
                    text = cyrillic,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                HorizontalDivider()
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.GTranslate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Перевод OCR на русский (ИИ):",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Row {
                    IconButton(
                        onClick = {
                            val textToSpeak = translationText ?: restoredCyrillic ?: originalText
                            if (isSpeaking) {
                                ttsEngine?.stop()
                                isSpeaking = false
                            } else {
                                ttsEngine?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "yomihon_tts")
                                isSpeaking = true
                            }
                        },
                    ) {
                        Icon(
                            imageVector = if (isSpeaking) Icons.Outlined.Stop else Icons.Outlined.VolumeUp,
                            contentDescription = "Озвучить / Остановить",
                        )
                    }
                    IconButton(
                        onClick = {
                            val textToCopy = restoredCyrillic ?: translationText
                            textToCopy?.let { text ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Translation", text)
                                clipboard.setPrimaryClip(clip)
                                context.toast("Текст скопирован")
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ContentCopy,
                            contentDescription = "Копировать текст",
                        )
                    }
                }
            }

            if (isTranslating) {
                Text(
                    text = "Переводим кадр...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            } else {
                Text(
                    text = translationText ?: originalText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

private fun showTranslationNotification(context: Context, text: String) {
    if (text.isBlank()) return
    try {
        val channelId = "yomihon_translation_channel"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Перевод OCR Yomihon",
                NotificationManager.IMPORTANCE_LOW,
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Yomihon: Перевод OCR")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(7001, notification)
    } catch (e: Exception) {
        // Handle notification permission or service absence gracefully
    }
}
