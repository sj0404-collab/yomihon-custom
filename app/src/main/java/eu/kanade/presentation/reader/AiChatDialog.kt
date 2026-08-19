package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.ai.AiAssistant
import kotlinx.coroutines.launch

/**
 * AI-чат читалки: спрашивай о том, что на странице. Ассистенту передаётся
 * распознанный текст текущей страницы (последний OCR-кадр) как контекст —
 * можно попросить перевод, пересказ, объяснение реплики, кто что сказал.
 * Провайдер тот же, что в озвучке: Zen (без ключа) / OpenRouter (по ключу).
 */
@Composable
fun AiChatDialog(
    pageContext: String,
    onDismissRequest: () -> Unit,
) {
    data class Msg(val fromUser: Boolean, val text: String)

    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val messages = remember { mutableStateOf(listOf<Msg>()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun send() {
        val question = input.trim()
        if (question.isEmpty() || busy) return
        input = ""
        messages.value = messages.value + Msg(true, question)
        busy = true
        scope.launch {
            val context = if (pageContext.isNotBlank()) {
                "Текст на текущей странице манги:\n${pageContext.take(1500)}\n\nВопрос читателя: "
            } else {
                "Вопрос читателя манги: "
            }
            val answer = AiAssistant.chat(
                userPrompt = context + question,
                systemPrompt = "Ты помощник читалки манги. Отвечай кратко и по делу на русском.",
                maxTokens = 400,
            ) ?: "Не удалось получить ответ (сеть/лимиты). Попробуй ещё раз."
            messages.value = messages.value + Msg(false, answer)
            busy = false
        }
    }

    LaunchedEffect(messages.value.size) {
        if (messages.value.isNotEmpty()) listState.animateScrollToItem(messages.value.size - 1)
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(Icons.Outlined.SmartToy, contentDescription = null) },
        title = { Text("AI-помощник") },
        text = {
            Column {
                Text(
                    if (pageContext.isBlank()) {
                        "Страница ещё не сканировалась — задай общий вопрос или запусти авточтение."
                    } else {
                        "Вижу текст страницы (${pageContext.length} симв.). Спрашивай: перевод, пересказ, кто говорит…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 300.dp)
                        .padding(vertical = 6.dp),
                ) {
                    items(messages.value) { msg ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (msg.fromUser) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                        ) {
                            Text(
                                msg.text,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
                if (busy) {
                    Text(
                        "⏳ Думаю…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Вопрос…") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { send() }, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Отправить")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text("Закрыть") }
        },
    )
}
