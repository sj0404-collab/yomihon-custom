package eu.kanade.tachiyomi.ui.aichat

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image as ImageIcon
import androidx.compose.material.icons.outlined.LibraryAddCheck
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.util.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.tachiyomi.data.ai.AiAgent
import eu.kanade.tachiyomi.data.ai.AiWorkspace
import eu.kanade.tachiyomi.data.tts.TtsSpeaker
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toast
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Вкладка «AI» — встроенный агент приложения (вместо AI-чата в читалке).
 *
 * Возможности (все реальные, без заглушек):
 * • чат с Zen/OpenRouter моделями (те же, что в озвучке);
 * • вложения: картинки (прогоняются через текущий OCR-движок, текст идёт
 *   модели) и любые файлы (текстовые читаются, бинарные сохраняются в
 *   workspace/inbox);
 * • инструменты агента: генерация картинок (Pollinations), запись файлов
 *   в workspace, проверка сайтов, фильтрация расширений, поиск манги по
 *   источникам, zip-архив workspace;
 * • под каждым сообщением: ▶ озвучить, ⏹ стоп, копировать; режим
 *   «выбрать несколько» — копирование пачки сообщений разом;
 * • вкладка Workspace: файлы/папки/архивы агента (/sdcard/Yomikai/AI),
 *   превью картинок, «Поделиться» (скачать наружу), удаление.
 */
data object AiChatTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 9u,
            title = "AI",
            icon = rememberVectorPainter(Icons.Outlined.SmartToy),
        )

    private data class Msg(
        val role: String, // user | ai
        val text: String,
        val images: List<File> = emptyList(),
        val toolLog: String = "",
        val reasoning: String? = null,
        val model: String = "",
    )

    // Держим историю в памяти процесса: вкладку можно покидать и возвращаться
    private val history = mutableListOf<Msg>()

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        var tab by rememberSaveable { mutableStateOf(0) } // 0=чат, 1=workspace, 2=настройки
        val messages = remember { history.toMutableStateList() }
        var input by rememberSaveable { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var selectMode by remember { mutableStateOf(false) }
        val selected = remember { mutableStateOf(setOf<Int>()) }
        val attachments = remember { mutableStateOf(listOf<File>()) }
        val listState = rememberLazyListState()

        fun push(m: Msg) {
            messages.add(m)
            history.add(m)
        }

        val pickFile = rememberLauncherForActivityResult(
            ActivityResultContracts.GetMultipleContents(),
        ) { uris ->
            scope.launch(Dispatchers.IO) {
                val added = uris.mapNotNull { uri ->
                    runCatching {
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: return@runCatching null
                        if (bytes.size > 20 * 1024 * 1024) return@runCatching null // 20МБ лимит
                        AiWorkspace.importAttachment(context, name, bytes)
                    }.getOrNull()
                }
                withContext(Dispatchers.Main) {
                    attachments.value = attachments.value + added
                    if (added.size < uris.size) context.toast("Часть файлов не добавлена (лимит 20МБ)")
                }
            }
        }

        fun send() {
            val text = input.trim()
            if (text.isEmpty() && attachments.value.isEmpty()) return
            val atts = attachments.value
            attachments.value = emptyList()
            input = ""
            push(Msg("user", text, images = atts.filter { isImage(it) }))
            busy = true
            scope.launch(Dispatchers.IO) {
                // Готовим описание вложений для модели: картинки — через OCR,
                // текстовые файлы — содержимое, бинарные — только метаданные.
                val attInfo = if (atts.isEmpty()) {
                    null
                } else {
                    val parts = mutableListOf<String>()
                    for (f in atts) {
                        parts += when {
                            isImage(f) -> {
                                val ocr = AiAgent.ocrAttachment(f)
                                "Картинка ${f.name}: " + (ocr?.let { "распознанный текст: ${it.take(600)}" }
                                    ?: "текст не распознан")
                            }
                            isTextFile(f) -> "Файл ${f.name}:\n" + runCatching { f.readText().take(2000) }
                                .getOrDefault("(не читается)")
                            else -> "Бинарный файл ${f.name} (${f.length() / 1024} КБ) — сохранён в workspace/inbox"
                        }
                    }
                    parts.joinToString("\n")
                }
                // Роутинг бэкенда. ВАЖНО (жалоба пользователя «почему для
                // локальной АИ недоступны инструменты»): агентский цикл с
                // @tool-инструментами теперь ОБЩИЙ — инструменты исполняет
                // само приложение, а модель (онлайн/локальная/ранер) только
                // пишет текст. Поэтому у локальной модели ЕСТЬ файлы,
                // картинки, проверка сайтов и всё остальное.
                val prefsBk = Injekt.get<OcrPreferences>()
                val backendKey = prefsBk.aiBackend().get()
                val chatFn: (suspend (String, String) -> eu.kanade.tachiyomi.data.ai.AiAssistant.ChatReply?)? =
                    when (backendKey) {
                        "local" -> {
                            val modelId = prefsBk.localLlmModel().get()
                            val model = eu.kanade.tachiyomi.data.ai.LocalLlm.CATALOG.firstOrNull { it.id == modelId }
                            if (model != null && eu.kanade.tachiyomi.data.ai.LocalLlm.isInstalled(context, model)) {
                                { p, sys ->
                                    eu.kanade.tachiyomi.data.ai.LocalLlm
                                        .chat(context, model, "$sys\n\n$p")
                                        ?.let {
                                            eu.kanade.tachiyomi.data.ai.AiAssistant.ChatReply(it, null, model.name)
                                        }
                                }
                            } else {
                                null // не готова — ниже честное сообщение
                            }
                        }
                        "runner" -> {
                            val session = eu.kanade.tachiyomi.data.ai.RunnerLlm.listSessions(context).firstOrNull()
                            if (session?.url != null) {
                                { p, sys ->
                                    eu.kanade.tachiyomi.data.ai.RunnerLlm
                                        .chat(context, session, "$sys\n\n$p")
                                        ?.let {
                                            eu.kanade.tachiyomi.data.ai.AiAssistant.ChatReply(it, null, session.model)
                                        }
                                }
                            } else {
                                null
                            }
                        }
                        else -> { p, sys -> eu.kanade.tachiyomi.data.ai.AiAssistant.chatFull(p, sys, maxTokens = 900) }
                    }
                if (chatFn == null) {
                    withContext(Dispatchers.Main) {
                        push(
                            Msg(
                                "ai",
                                if (backendKey == "local") {
                                    "Локальная модель не готова: скачайте её в ⚙ → Локальные LLM и прогоните «Тест»"
                                } else {
                                    "Нет живой ранер-сессии: запустите её в ⚙ → Полу-онлайн LLM"
                                },
                                model = backendKey,
                            ),
                        )
                        busy = false
                    }
                    return@launch
                }
                val reply = AiAgent.run(
                    context,
                    text.ifBlank { "Опиши вложения" },
                    attInfo,
                    history.map { it.role to it.text },
                    chatFn = chatFn,
                )
                withContext(Dispatchers.Main) {
                    push(
                        Msg(
                            "ai",
                            reply.text,
                            images = reply.images,
                            toolLog = reply.toolResults.joinToString("\n") { "🔧 ${it.name}: ${it.output.take(180)}" },
                            reasoning = reply.reasoning,
                            model = reply.model,
                        ),
                    )
                    busy = false
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding(),
        ) {
            // Верхние чипы: Чат / Workspace / настройки / выбор нескольких.
            // Ряд горизонтально скроллится, чтобы чипы не уезжали за экран.
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Чат") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("Workspace") })
                FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text("⚙") })
                if (tab == 0) {
                    ModelChip()
                    FilterChip(
                        selected = selectMode,
                        onClick = {
                            selectMode = !selectMode
                            if (!selectMode) selected.value = emptySet()
                        },
                        label = { Icon(Icons.Outlined.LibraryAddCheck, null, Modifier.size(16.dp)) },
                    )
                    if (selectMode && selected.value.isNotEmpty()) {
                        FilterChip(
                            selected = false,
                            onClick = {
                                val textAll = selected.value.sorted()
                                    .mapNotNull { messages.getOrNull(it)?.text }
                                    .joinToString("\n\n")
                                context.copyToClipboard("AI чат", textAll)
                                selected.value = emptySet()
                                selectMode = false
                            },
                            label = { Text("Копировать ${selected.value.size}") },
                        )
                        // Отправить выделенные сообщения повторно — одним документом
                        FilterChip(
                            selected = false,
                            onClick = {
                                val textAll = selected.value.sorted()
                                    .mapNotNull { messages.getOrNull(it)?.text }
                                    .joinToString("\n\n")
                                input = textAll
                                selected.value = emptySet()
                                selectMode = false
                            },
                            label = { Text("↻ Повторно") },
                        )
                        // Экспорт: PDF всегда; DOCX — если есть картинка (или тоже всегда)
                        val hasImage = selected.value.any { messages.getOrNull(it)?.images?.isNotEmpty() == true }
                        FilterChip(
                            selected = false,
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val items = selected.value.sorted().mapNotNull { idx ->
                                        val m = messages.getOrNull(idx) ?: return@mapNotNull null
                                        eu.kanade.tachiyomi.data.ai.DocExporter.Item(
                                            title = if (m.role == "user") "Вы" else "Агент",
                                            text = m.text,
                                            imagePath = m.images.firstOrNull()?.absolutePath,
                                        )
                                    }
                                    val f = eu.kanade.tachiyomi.data.ai.DocExporter.exportPdf(
                                        context, items, "chat_${System.currentTimeMillis() / 1000}.pdf",
                                    )
                                    withContext(Dispatchers.Main) {
                                        context.toast("PDF: ${f.name} (в Workspace/export)")
                                        selected.value = emptySet()
                                        selectMode = false
                                    }
                                }
                            },
                            label = { Text("📄 PDF") },
                        )
                        FilterChip(
                            selected = false,
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    val items = selected.value.sorted().mapNotNull { idx ->
                                        val m = messages.getOrNull(idx) ?: return@mapNotNull null
                                        eu.kanade.tachiyomi.data.ai.DocExporter.Item(
                                            title = if (m.role == "user") "Вы" else "Агент",
                                            text = m.text,
                                            imagePath = m.images.firstOrNull()?.absolutePath,
                                        )
                                    }
                                    val f = eu.kanade.tachiyomi.data.ai.DocExporter.exportDocx(
                                        context, items, "chat_${System.currentTimeMillis() / 1000}.docx",
                                    )
                                    withContext(Dispatchers.Main) {
                                        context.toast("DOCX: ${f.name} (в Workspace/export)")
                                        selected.value = emptySet()
                                        selectMode = false
                                    }
                                }
                            },
                            label = { Text(if (hasImage) "📝 DOCX (с картинкой)" else "📝 DOCX") },
                        )
                    }
                }
            }

            when (tab) {
                0 -> ChatBody(
                    messages = messages,
                    busy = busy,
                    listState = listState,
                    selectMode = selectMode,
                    selected = selected.value,
                    onToggleSelect = { idx ->
                        selected.value = if (idx in selected.value) selected.value - idx else selected.value + idx
                    },
                    input = input,
                    onInput = { input = it },
                    attachments = attachments.value,
                    onRemoveAttachment = { f -> attachments.value = attachments.value - f },
                    onAttach = { pickFile.launch("*/*") },
                    onSend = ::send,
                    modifier = Modifier.weight(1f),
                )
                1 -> WorkspaceBody(modifier = Modifier.weight(1f))
                else -> SettingsBody(modifier = Modifier.weight(1f))
            }
        }
    }

    /**
     * Чип текущей модели над чатом: тап — выпадающий список всех моделей
     * (Zen без ключа + OpenRouter :free при ключе) с переключением на лету,
     * плюс тумблеры «Автосмена» и «Размышления».
     */
    @Composable
    private fun ModelChip() {
        val prefs = remember { Injekt.get<OcrPreferences>() }
        val zenModelPref = remember { prefs.zenModel() }
        val zenModel by zenModelPref.changes()
            .collectAsState(initial = zenModelPref.get())
        val autoRotatePref = remember { prefs.aiAutoRotate() }
        val autoRotate by autoRotatePref.changes()
            .collectAsState(initial = autoRotatePref.get())
        val showReasoningPref = remember { prefs.aiShowReasoning() }
        val showReasoning by showReasoningPref.changes()
            .collectAsState(initial = showReasoningPref.get())
        var open by remember { mutableStateOf(false) }

        androidx.compose.foundation.layout.Box {
            FilterChip(
                selected = false,
                onClick = { open = true },
                label = { Text("🧠 " + zenModel.removeSuffix("-free").take(18)) },
            )
            androidx.compose.material3.DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
            ) {
                eu.kanade.tachiyomi.data.ai.AiAssistant.ZEN_MODELS.forEach { m ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(m) },
                        leadingIcon = {
                            androidx.compose.material3.RadioButton(
                                selected = zenModel == m,
                                onClick = null,
                            )
                        },
                        onClick = {
                            zenModelPref.set(m)
                            open = false
                        },
                    )
                }
                androidx.compose.material3.HorizontalDivider()
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(if (autoRotate) "✓ Автосмена при лимите" else "Автосмена при лимите") },
                    onClick = { autoRotatePref.set(!autoRotate) },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(if (showReasoning) "✓ Показывать размышления" else "Показывать размышления") },
                    onClick = { showReasoningPref.set(!showReasoning) },
                )
            }
        }
    }

    @Composable
    private fun SettingsBody(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val prefs = remember { Injekt.get<OcrPreferences>() }
        val tabVisiblePref = remember { prefs.aiTabVisible() }
        val serverPref = remember { prefs.aiHttpServer() }
        var serverOn by remember { mutableStateOf(eu.kanade.tachiyomi.data.ai.AiHttpServer.isRunning) }

        val backendPref = remember { prefs.aiBackend() }
        val backend by backendPref.changes().collectAsState(initial = backendPref.get())
        val localModelPref = remember { prefs.localLlmModel() }
        val localModelId by localModelPref.changes().collectAsState(initial = localModelPref.get())
        val patPref = remember { prefs.githubPat() }
        val pat by patPref.changes().collectAsState(initial = patPref.get())
        val llmProgress by eu.kanade.tachiyomi.data.ai.LocalLlm.progress.collectAsState()
        val probeState by eu.kanade.tachiyomi.data.ai.LocalLlm.probeState.collectAsState()
        var probeMessage by remember { mutableStateOf("") }
        var runnerStatus by remember { mutableStateOf("") }
        var sessions by remember { mutableStateOf(eu.kanade.tachiyomi.data.ai.RunnerLlm.listSessions(context)) }
        val scope = rememberCoroutineScope()
        val ramGb = remember { eu.kanade.tachiyomi.data.ai.LocalLlm.deviceRamGb(context) }

        Column(
            modifier = modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Настройки AI-агента", style = MaterialTheme.typography.titleMedium)

            // ---- Бэкенд чата ----
            Text("Бэкенд чата", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = backend == "online",
                    onClick = { backendPref.set("online") },
                    label = { Text("☁ Онлайн") },
                )
                FilterChip(
                    selected = backend == "local",
                    onClick = { backendPref.set("local") },
                    label = { Text("📱 Локально") },
                )
                FilterChip(
                    selected = backend == "runner",
                    onClick = { backendPref.set("runner") },
                    label = { Text("🖥 Ранер") },
                )
            }
            Text(
                when (backend) {
                    "local" -> "Чат работает полностью офлайн на модели с телефона (инструменты агента недоступны)"
                    "runner" -> "Чат идёт через GGUF-модель на GitHub-ранере (сессия до ~5.5 ч)"
                    else -> "Полный агент: Zen/OpenRouter + инструменты (файлы, картинки, поиск манги)"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- Локальные LLM (по ОЗУ, с тестом) ----
            Text(
                "Локальные LLM • у устройства $ramGb ГБ ОЗУ",
                style = MaterialTheme.typography.titleSmall,
            )
            eu.kanade.tachiyomi.data.ai.LocalLlm.CATALOG.forEach { m ->
                val fits = m.tier.minRamGb <= ramGb
                val installed = eu.kanade.tachiyomi.data.ai.LocalLlm.isInstalled(context, m)
                val prog = llmProgress[m.id]
                val probed = probeState[m.id]
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(
                            "${m.name} • ${m.tier.label}" + if (!fits) " ⛔ не влезет в ОЗУ" else "",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            m.specs + " • ${m.sizeMb} МБ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (prog != null) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { prog },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            )
                            Text("Загрузка ${(prog * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        }
                        when (probed) {
                            "ok" -> Text("✅ Тест пройден", style = MaterialTheme.typography.bodySmall)
                            "fail" -> Text("❌ Тест провален", style = MaterialTheme.typography.bodySmall)
                            "testing" -> Text("⏳ Тестирование…", style = MaterialTheme.typography.bodySmall)
                        }
                        // НАСТОЯЩИЕ КНОПКИ вместо чипов (по скриншоту: чипы
                        // выглядели как текст, «Удалить» сплющивалась в
                        // вертикальный столбик). FlowRow переносит кнопки на
                        // следующую строку вместо сжатия.
                        var busyAction by remember(m.id) { mutableStateOf(false) }
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            when {
                                prog != null -> {}
                                !installed -> androidx.compose.material3.FilledTonalButton(
                                    enabled = fits && !busyAction,
                                    onClick = {
                                        busyAction = true
                                        scope.launch(Dispatchers.IO) {
                                            eu.kanade.tachiyomi.data.ai.LocalLlm.download(context, m)
                                            withContext(Dispatchers.Main) { busyAction = false }
                                        }
                                    },
                                ) { Text("⬇ Скачать") }
                                else -> {
                                    androidx.compose.material3.FilledTonalButton(
                                        enabled = !busyAction,
                                        onClick = {
                                            busyAction = true
                                            probeMessage = "${m.name}: тест запущен, первая загрузка модели " +
                                                "в память может занять до минуты…"
                                            scope.launch(Dispatchers.IO) {
                                                val (ok, msg) = eu.kanade.tachiyomi.data.ai.LocalLlm.probe(context, m)
                                                withContext(Dispatchers.Main) {
                                                    probeMessage = "${m.name}: $msg"
                                                    if (ok) localModelPref.set(m.id)
                                                    busyAction = false
                                                }
                                            }
                                        },
                                    ) { Text("▶ Тест") }
                                    androidx.compose.material3.Button(
                                        enabled = !busyAction,
                                        onClick = { localModelPref.set(m.id) },
                                    ) { Text(if (localModelId == m.id) "✓ Активна" else "Выбрать") }
                                    androidx.compose.material3.OutlinedButton(
                                        enabled = !busyAction,
                                        onClick = {
                                            busyAction = true
                                            context.toast("Упаковка в tar.xz началась (займёт минуты)…")
                                            scope.launch(Dispatchers.IO) {
                                                val f = eu.kanade.tachiyomi.data.ai.LocalLlm.exportTarXz(context, m)
                                                withContext(Dispatchers.Main) {
                                                    context.toast(
                                                        if (f != null) {
                                                            "Экспорт: ${f.name} (${f.length() / 1048576} МБ, было ${m.sizeMb} МБ)"
                                                        } else "Экспорт не удался",
                                                    )
                                                    busyAction = false
                                                }
                                            }
                                        },
                                    ) { Text("→ tar.xz") }
                                    androidx.compose.material3.OutlinedButton(
                                        enabled = !busyAction,
                                        onClick = {
                                            eu.kanade.tachiyomi.data.ai.LocalLlm.delete(context, m)
                                            if (localModelId == m.id) localModelPref.set("")
                                        },
                                    ) { Text("🗑 Удалить") }
                                }
                            }
                        }
                    }
                }
            }
            if (probeMessage.isNotBlank()) {
                Text(probeMessage, style = MaterialTheme.typography.bodySmall)
            }

            // ---- Полу-онлайн LLM (GitHub-ранер, GGUF) ----
            Text("Полу-онлайн LLM (GitHub-ранер, GGUF)", style = MaterialTheme.typography.titleSmall)
            Text(
                "Модель скачивается в ранер GitHub Actions, телефон подключается к ней по токену. " +
                    "Нужен PAT с правом actions:write на репозиторий.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = pat,
                onValueChange = { patPref.set(it) },
                label = { Text("GitHub PAT") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
            eu.kanade.tachiyomi.data.ai.RunnerLlm.GGUF_MODELS.forEach { (key, label) ->
                FilterChip(
                    selected = false,
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val s = eu.kanade.tachiyomi.data.ai.RunnerLlm.startSession(context, key) { st ->
                                runnerStatus = st
                            }
                            withContext(Dispatchers.Main) {
                                if (s != null) {
                                    sessions = eu.kanade.tachiyomi.data.ai.RunnerLlm.listSessions(context)
                                    backendPref.set("runner")
                                }
                            }
                        }
                    },
                    label = { Text("▶ $label") },
                )
            }
            if (runnerStatus.isNotBlank()) {
                Text(runnerStatus, style = MaterialTheme.typography.bodySmall)
            }
            if (sessions.isNotEmpty()) {
                Text("Сессии (сохранены на телефоне):", style = MaterialTheme.typography.bodySmall)
                sessions.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${s.model} • ${s.messages.size} сообщ. • ${s.id}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = false,
                            onClick = {
                                eu.kanade.tachiyomi.data.ai.RunnerLlm.deleteSession(context, s)
                                sessions = eu.kanade.tachiyomi.data.ai.RunnerLlm.listSessions(context)
                            },
                            label = { Text("✕") },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Доступ из внешнего браузера", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (serverOn) {
                            "Работает: http://127.0.0.1:${eu.kanade.tachiyomi.data.ai.AiHttpServer.PORT} " +
                                "(с других устройств Wi-Fi — http://IP-телефона:${eu.kanade.tachiyomi.data.ai.AiHttpServer.PORT})"
                        } else {
                            "Выключен. Включите — чат и workspace откроются в любом браузере."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = serverOn,
                    onCheckedChange = { on ->
                        if (on) {
                            runCatching { eu.kanade.tachiyomi.data.ai.AiHttpServer.start(context) }
                                .onFailure { context.toast("Не удалось запустить сервер: ${it.message}") }
                        } else {
                            eu.kanade.tachiyomi.data.ai.AiHttpServer.stop()
                        }
                        serverOn = eu.kanade.tachiyomi.data.ai.AiHttpServer.isRunning
                        serverPref.set(serverOn)
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Скрыть вкладку AI из нижней панели", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Вкладка исчезнет из навигации. Вернуть: Ещё → Настройки, либо через внешний браузер.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = false,
                    onCheckedChange = {
                        if (!serverOn) {
                            runCatching { eu.kanade.tachiyomi.data.ai.AiHttpServer.start(context) }
                            serverPref.set(true)
                        }
                        tabVisiblePref.set(false)
                        context.toast("Вкладка скрыта. Агент доступен на http://127.0.0.1:8765")
                    },
                )
            }
            Text(
                "Модель агента настраивается там же, где модель озвучки: " +
                    "читалка → SAO-меню → Озвучка → AI-провайдер (Zen без ключа / OpenRouter).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun ChatBody(
        messages: List<Msg>,
        busy: Boolean,
        listState: androidx.compose.foundation.lazy.LazyListState,
        selectMode: Boolean,
        selected: Set<Int>,
        onToggleSelect: (Int) -> Unit,
        input: String,
        onInput: (String) -> Unit,
        attachments: List<File>,
        onRemoveAttachment: (File) -> Unit,
        onAttach: () -> Unit,
        onSend: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val context = LocalContext.current

        Column(modifier = modifier) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "Встроенный AI-агент Yomikai.\n\nУмеет: отвечать на вопросы, рисовать картинки " +
                                "(Pollinations), сохранять файлы в workspace (/sdcard/Yomikai/AI), проверять " +
                                "сайты, скрывать/показывать расширения, искать мангу по источникам.\n\n" +
                                "Примеры:\n«скрой все английские расширения»\n«проверь, работает ли mangalib.me»\n" +
                                "«найди мангу Наруто»\n«нарисуй лису с мангой»\n«сохрани список покупок в файл»",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(messages.size) { idx ->
                    val m = messages[idx]
                    MessageBubble(
                        m = m,
                        index = idx,
                        selectMode = selectMode,
                        isSelected = idx in selected,
                        onToggleSelect = onToggleSelect,
                    )
                }
                if (busy) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Агент думает/работает…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Полоса вложений
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attachments.take(4).forEach { f ->
                        FilterChip(
                            selected = true,
                            onClick = { onRemoveAttachment(f) },
                            label = { Text(f.name.take(16) + " ✕") },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(onClick = onAttach) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = "Вложение")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = onInput,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение агенту…") },
                    maxLines = 4,
                )
                IconButton(onClick = onSend, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Отправить")
                }
            }
        }
    }

    @Composable
    private fun MessageBubble(
        m: Msg,
        index: Int,
        selectMode: Boolean,
        isSelected: Boolean,
        onToggleSelect: (Int) -> Unit,
    ) {
        val context = LocalContext.current
        val isUser = m.role == "user"
        Surface(
            color = when {
                isSelected -> MaterialTheme.colorScheme.tertiaryContainer
                isUser -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = selectMode) { onToggleSelect(index) },
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    if (isUser) "Вы" else "Агент" + (if (m.model.isNotBlank()) " • ${m.model}" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Размышления reasoning-модели (если включено в меню модели)
                if (!isUser && m.reasoning != null) {
                    val prefs = remember { Injekt.get<OcrPreferences>() }
                    val show by prefs.aiShowReasoning().changes()
                        .collectAsState(initial = prefs.aiShowReasoning().get())
                    if (show) {
                        var expanded by remember { mutableStateOf(false) }
                        Text(
                            if (expanded) "🤔 ${m.reasoning}" else "🤔 Размышления… (нажмите)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { expanded = !expanded },
                        )
                    }
                }
                if (m.text.isNotBlank()) {
                    Text(m.text, style = MaterialTheme.typography.bodyMedium)
                }
                if (m.toolLog.isNotBlank()) {
                    Text(
                        m.toolLog,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                m.images.forEach { img ->
                    ImageThumb(img)
                }
                // Кнопки: озвучить / стоп / копировать
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(
                        onClick = { TtsSpeaker.speak(context, m.text) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Outlined.PlayArrow, "Озвучить", Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = { TtsSpeaker.stop() },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Outlined.Stop, "Стоп", Modifier.size(18.dp))
                    }
                    IconButton(
                        onClick = { context.copyToClipboard("AI", m.text) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Outlined.ContentCopy, "Копировать", Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun ImageThumb(img: File) {
        val context = LocalContext.current
        val bmp = remember(img.absolutePath) {
            runCatching {
                BitmapFactory.decodeFile(img.absolutePath)?.let { b ->
                    if (b.width > 512) {
                        val h = b.height * 512 / b.width
                        android.graphics.Bitmap.createScaledBitmap(b, 512, h, true).also { if (it !== b) b.recycle() }
                    } else b
                }
            }.getOrNull()
        }
        if (bmp != null) {
            Column {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = img.name,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
                Row {
                    FilterChip(
                        selected = false,
                        onClick = {
                            val uri = img.getUriCompat(context)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Скачать/поделиться"))
                        },
                        label = { Text("⬇ Скачать") },
                    )
                }
            }
        }
    }

    @Composable
    private fun WorkspaceBody(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var files by remember { mutableStateOf(AiWorkspace.listAll(context)) }
        var refreshKey by remember { mutableStateOf(0) }

        androidx.compose.runtime.LaunchedEffect(refreshKey) {
            files = withContext(Dispatchers.IO) { AiWorkspace.listAll(context) }
        }

        Column(modifier = modifier.padding(horizontal = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Workspace агента: /sdcard/Yomikai/AI",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = false,
                    onClick = { refreshKey++ },
                    label = { Text("Обновить") },
                )
                FilterChip(
                    selected = false,
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val zip = AiWorkspace.zipAll(context)
                            withContext(Dispatchers.Main) {
                                context.toast("Архив: ${zip.name}")
                                refreshKey++
                            }
                        }
                    },
                    label = { Text("Zip всего") },
                )
            }
            if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Workspace пуст. Попросите агента что-нибудь сохранить или нарисовать.")
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(files, key = { it.absolutePath }) { f ->
                        WorkspaceRow(
                            f = f,
                            rel = AiWorkspace.relPath(context, f),
                            onDelete = {
                                AiWorkspace.delete(context, AiWorkspace.relPath(context, f))
                                refreshKey++
                            },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    @Composable
    private fun WorkspaceRow(f: File, rel: String, onDelete: () -> Unit) {
        val context = LocalContext.current
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            ) {
                Icon(
                    when {
                        f.isDirectory -> Icons.Outlined.Folder
                        isImage(f) -> Icons.Outlined.ImageIcon
                        else -> Icons.Outlined.AttachFile
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(rel, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    if (f.isFile) {
                        Text(
                            "${f.length() / 1024} КБ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (f.isFile) {
                    IconButton(
                        onClick = {
                            val uri = f.getUriCompat(context)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = when {
                                    isImage(f) -> "image/*"
                                    f.extension == "zip" -> "application/zip"
                                    else -> "*/*"
                                }
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Поделиться"))
                        },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(Icons.Outlined.Share, "Поделиться", Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Outlined.Delete, "Удалить", Modifier.size(18.dp))
                }
            }
        }
    }

    private fun isImage(f: File) = f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif")
    private fun isTextFile(f: File) = f.extension.lowercase() in
        setOf("txt", "md", "json", "xml", "html", "csv", "log", "kt", "java", "js", "py")
}
