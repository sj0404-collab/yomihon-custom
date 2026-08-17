package eu.kanade.presentation.reader

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

/**
 * Быстрые настройки озвучки прямо в читалке: выбор системного голоса,
 * скорость речи, проба и сохранение. Раньше кнопка "настройки" уводила
 * из читалки в общие настройки, а выбора голоса там не было вовсе.
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
    var voices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var engineReady by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engineReady = true
                tts = engine
                voices = runCatching {
                    engine!!.voices.orEmpty()
                        .filter { !it.isNetworkConnectionRequired || true }
                        .sortedWith(
                            compareBy(
                                { !it.locale.language.equals("ru", ignoreCase = true) },
                                { it.locale.displayName },
                                { it.name },
                            ),
                        )
                        .map { v ->
                            val net = if (v.isNetworkConnectionRequired) " • сеть" else " • локальный"
                            v.name to (v.locale.displayName + net)
                        }
                }.getOrDefault(emptyList())
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
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (!engineReady) {
                    Text("Инициализация системного TTS…")
                } else if (voices.isEmpty()) {
                    Text(
                        "Системный TTS-движок не найден или в нём нет голосов.\n\n" +
                            "Установите движок озвучки (например, Speech Services by Google) " +
                            "и русский голос в настройках системы:\n" +
                            "Настройки → Спец. возможности → Синтез речи.",
                    )
                } else {
                    Text(
                        text = "Скорость: ${"%.1f".format(rate)}×",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = rate,
                        onValueChange = { rate = it },
                        valueRange = 0.5f..2f,
                    )
                    Text(
                        text = "Голос:",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Column(modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        voices.forEach { (name, label) ->
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
                        val engine = tts ?: return@TextButton
                        engine.setSpeechRate(rate.coerceIn(0.5f, 2f))
                        engine.voices?.find { it.name == selectedVoice }?.let { engine.voice = it }
                        val r = engine.speak(
                            "Проверка выбранного голоса Ёмихон.",
                            TextToSpeech.QUEUE_FLUSH,
                            null,
                            "tts_test",
                        )
                        if (r != TextToSpeech.SUCCESS) context.toast("Движок TTS не отвечает")
                    },
                ) { Text("Проба") }
                TextButton(onClick = onOpenFullSettings) { Text("Все настройки") }
            }
        },
    )
}
