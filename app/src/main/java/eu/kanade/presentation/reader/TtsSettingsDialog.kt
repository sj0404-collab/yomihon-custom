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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import eu.kanade.tachiyomi.data.tts.TtsSpeaker
import eu.kanade.tachiyomi.util.system.toast
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Настройки озвучки: три источника голосов.
 * • Системные — все голоса Android TTS (локальные и сетевые)
 * • Веб (без ключа) — Google Translate TTS прямо с сайта
 * • ElevenLabs — нейроголоса по API-ключу
 */
@Composable
fun TtsSettingsDialog(
    onDismissRequest: () -> Unit,
    onOpenFullSettings: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Injekt.get<OcrPreferences>() }

    var engine by remember { mutableStateOf(prefs.voiceEngine().get()) }
    var selectedVoice by remember { mutableStateOf(prefs.voiceName().get()) }
    var rate by remember { mutableFloatStateOf(prefs.speechRate().get()) }
    var webLang by remember { mutableStateOf(prefs.ttsWebLanguage().get()) }
    var elevenKey by remember { mutableStateOf(prefs.elevenApiKey().get()) }
    var elevenVoice by remember { mutableStateOf(prefs.elevenVoiceId().get()) }

    var voices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var sysReady by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        var probe: TextToSpeech? = null
        probe = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                voices = runCatching {
                    probe!!.voices.orEmpty()
                        .sortedWith(
                            compareBy(
                                { !it.locale.language.equals("ru", ignoreCase = true) },
                                { it.isNetworkConnectionRequired },
                                { it.name },
                            ),
                        )
                        .map { v ->
                            v.name to (
                                v.locale.displayName +
                                    if (v.isNetworkConnectionRequired) " • сеть" else " • локальный"
                                )
                        }
                }.getOrDefault(emptyList())
            }
            sysReady = true
        }
        onDispose {
            probe?.stop()
            probe?.shutdown()
        }
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(Icons.Outlined.RecordVoiceOver, contentDescription = null) },
        title = { Text("Озвучка (TTS)") },
        text = {
            Column {
                Row {
                    FilterChip(
                        selected = engine == TtsSpeaker.ENGINE_SYSTEM,
                        onClick = { engine = TtsSpeaker.ENGINE_SYSTEM },
                        label = { Text("Системные") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    FilterChip(
                        selected = engine == TtsSpeaker.ENGINE_GOOGLE_WEB,
                        onClick = { engine = TtsSpeaker.ENGINE_GOOGLE_WEB },
                        label = { Text("Веб") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    FilterChip(
                        selected = engine == TtsSpeaker.ENGINE_ELEVENLABS,
                        onClick = { engine = TtsSpeaker.ENGINE_ELEVENLABS },
                        label = { Text("ElevenLabs") },
                    )
                }

                Text(
                    text = "Скорость: ${"%.1f".format(rate)}× (для системных голосов)",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Slider(
                    value = rate,
                    onValueChange = { rate = it },
                    valueRange = 0.5f..2f,
                )

                when (engine) {
                    TtsSpeaker.ENGINE_SYSTEM -> {
                        when {
                            !sysReady -> Text("Инициализация системного TTS…")
                            voices.isEmpty() -> Text(
                                "Голосов не найдено. Установите TTS-движок " +
                                    "(Speech Services by Google, RHVoice) в настройках системы.",
                            )
                            else -> LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                                items(voices, key = { it.first }) { (name, label) ->
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
                    TtsSpeaker.ENGINE_GOOGLE_WEB -> {
                        Text(
                            "Озвучка с сайта Google Translate — без API-ключа, нужен интернет.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = webLang,
                            onValueChange = { webLang = it },
                            label = { Text("Язык (ru, en, ja…)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }
                    TtsSpeaker.ENGINE_ELEVENLABS -> {
                        Text(
                            "Нейроголоса ElevenLabs. Нужен API-ключ с elevenlabs.io. " +
                                "Без ключа автоматически используется веб-озвучка.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = elevenKey,
                            onValueChange = { elevenKey = it },
                            label = { Text("API-ключ") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                        OutlinedTextField(
                            value = elevenVoice,
                            onValueChange = { elevenVoice = it },
                            label = { Text("Voice ID (пусто = Rachel)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    prefs.voiceEngine().set(engine)
                    prefs.voiceName().set(selectedVoice)
                    prefs.speechRate().set(rate.coerceIn(0.5f, 2f))
                    prefs.ttsWebLanguage().set(webLang.trim().ifBlank { "ru" })
                    prefs.elevenApiKey().set(elevenKey.trim())
                    prefs.elevenVoiceId().set(elevenVoice.trim())
                    context.toast("Настройки озвучки сохранены")
                    onDismissRequest()
                },
            ) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        // Проба ТЕКУЩЕГО выбора без сохранения
                        prefs.voiceEngine().set(engine)
                        prefs.voiceName().set(selectedVoice)
                        prefs.speechRate().set(rate.coerceIn(0.5f, 2f))
                        prefs.ttsWebLanguage().set(webLang.trim().ifBlank { "ru" })
                        prefs.elevenApiKey().set(elevenKey.trim())
                        prefs.elevenVoiceId().set(elevenVoice.trim())
                        TtsSpeaker.speak(context, "Проверка выбранного голоса Ёмикай.")
                    },
                ) { Text("Проба") }
                TextButton(onClick = onOpenFullSettings) { Text("Ещё") }
            }
        },
    )
}
