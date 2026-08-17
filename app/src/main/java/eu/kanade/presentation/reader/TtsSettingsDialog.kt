package eu.kanade.presentation.reader

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.util.system.toast
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/**
 * Настройки озвучки прямо в читалке.
 *
 * ВАЖНО: список голосов — LazyColumn БЕЗ внешнего verticalScroll: вложенный
 * скролл одного направления в Compose падает с IllegalStateException, из-за
 * этого прошлая версия диалога «не работала» (мгновенно закрывалась).
 */
@Composable
fun TtsSettingsDialog(
    onDismissRequest: () -> Unit,
    onOpenFullSettings: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Injekt.get<OcrPreferences>() }

    var selectedVoice by remember { mutableStateOf(prefs.voiceName().get()) }
    var rate by remember { mutableFloatStateOf(prefs.speechRate().get()) }
    var voices by remember { mutableStateOf<List<Triple<String, String, Boolean>>>(emptyList()) }
    var engineReady by remember { mutableStateOf(false) }
    var engineName by remember { mutableStateOf("") }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engineReady = true
                tts = engine
                engineName = runCatching { engine!!.defaultEngine.orEmpty() }.getOrDefault("")
                voices = runCatching {
                    engine!!.voices.orEmpty()
                        .sortedWith(
                            compareBy(
                                { !it.locale.language.equals("ru", ignoreCase = true) },
                                { it.isNetworkConnectionRequired },
                                { it.name },
                            ),
                        )
                        .map { v ->
                            Triple(
                                v.name,
                                v.locale.displayName + if (v.isNetworkConnectionRequired) " • сеть" else " • локальный",
                                v.isNetworkConnectionRequired,
                            )
                        }
                }.getOrDefault(emptyList())
            } else {
                engineReady = true
            }
        }
        onDispose {
            engine?.stop()
            engine?.shutdown()
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(Icons.Outlined.RecordVoiceOver, contentDescription = null) },
        title = { Text("Озвучка (TTS)") },
        text = {
            Column {
                when {
                    !engineReady -> Text("Инициализация системного TTS…")
                    voices.isEmpty() -> Text(
                        "TTS-движок не найден или в нём нет голосов.\n\n" +
                            "Установите движок (например, Speech Services by Google, RHVoice) " +
                            "и включите его: Настройки системы → Синтез речи.",
                    )
                    else -> {
                        if (engineName.isNotBlank()) {
                            Text(
                                text = "Движок: $engineName • голосов: ${voices.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "Скорость: ${"%.1f".format(rate)}×",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Slider(
                            value = rate,
                            onValueChange = { rate = it },
                            valueRange = 0.5f..2f,
                        )
                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            items(voices, key = { it.first }) { (name, label, _) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedVoice = name },
                                ) {
                                    RadioButton(
                                        selected = selectedVoice == name,
                                        onClick = { selectedVoice = name },
                                    )
                                    Column {
                                        Text(label, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            name,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    prefs.voiceName().set(selectedVoice)
                    prefs.speechRate().set(rate.coerceIn(0.5f, 2f))
                    context.toast("Голос сохранён")
                    onDismissRequest()
                },
            ) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        val engine = tts
                        if (engine == null) {
                            context.toast("Движок TTS ещё не готов")
                            return@TextButton
                        }
                        engine.setSpeechRate(rate.coerceIn(0.5f, 2f))
                        val v = engine.voices?.find { it.name == selectedVoice }
                        if (v != null) engine.voice = v else engine.language = Locale("ru", "RU")
                        val r = engine.speak(
                            "Проверка выбранного голоса Ёмихон.",
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "tts_test",
                        )
                        if (r != TextToSpeech.SUCCESS) context.toast("Движок TTS не отвечает")
                    },
                ) { Text("Проба") }
                TextButton(onClick = onOpenFullSettings) { Text("Ещё") }
            }
        },
    )
}
