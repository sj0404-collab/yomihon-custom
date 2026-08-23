package eu.kanade.presentation.more.settings.screen

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.Female
import androidx.compose.material.icons.outlined.Male
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.tts.OnnxTts
import eu.kanade.tachiyomi.data.tts.TtsSpeaker
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.domain.ocr.service.OcrPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Полноэкранный выбор TTS-голосов с тремя вкладками:
 * 1. Оффлайн — системные локальные голоса + ONNX нейроголоса
 * 2. Полу-онлайн — Google Translate TTS (без ключа)
 * 3. Онлайн — ElevenLabs (API-ключ) + другие облачные
 *
 * Каждый голос: кнопка «Проба» и «Активировать».
 * Поддерживаются пресеты (мужской/женский по умолчанию) и
 * произвольные комбинации.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsVoicePickerScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Injekt.get<OcrPreferences>() }
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Выбор голосов озвучки") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WifiOff, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Оффлайн")
                    }},
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudQueue, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Полу-онлайн")
                    }},
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Cloud, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Онлайн")
                    }},
                )
            }

            when (selectedTab) {
                0 -> OfflineTab(context, prefs)
                1 -> SemiOnlineTab(context, prefs)
                2 -> OnlineTab(context, prefs)
            }
        }
    }
}

// ==========================================
// Tab 1: OFFLINE — System local + ONNX voices
// ==========================================
@Composable
private fun OfflineTab(context: Context, prefs: OcrPreferences) {
    var systemVoices by remember { mutableStateOf<List<android.speech.tts.Voice>>(emptyList()) }
    var systemReady by remember { mutableStateOf(false) }
    var ttsProbe by remember { mutableStateOf<TextToSpeech?>(null) }
    val enginePkg = remember { prefs.systemTtsEngine().get() }
    var onnxCatalog = remember { OnnxTts.CATALOG }
    val onnxProgress = OnnxTts.progress.collectAsState()
    // Версия установленных пакетов: после скачивания рантайма/голоса флаги
    // isInstalled пересчитываются сразу (раньше были заморожены в remember{}
    // и «ничего не происходило» — кнопки не менялись до перезахода).
    val installedVersion by OnnxTts.installedVersion.collectAsState()

    DisposableEffect(enginePkg) {
        systemVoices = emptyList(); systemReady = false; ttsProbe = null
        var tts: TextToSpeech? = null; var disposed = false
        val listener = TextToSpeech.OnInitListener { status ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!disposed) {
                    ttsProbe = tts.takeIf { status == TextToSpeech.SUCCESS }
                    systemReady = true
                }
            }
        }
        tts = if (enginePkg.isBlank()) {
            TextToSpeech(context.applicationContext, listener)
        } else {
            TextToSpeech(context.applicationContext, listener, enginePkg)
        }
        onDispose { disposed = true; runCatching { tts?.stop(); tts?.shutdown() } }
    }

    LaunchedEffect(ttsProbe, systemReady, enginePkg) {
        val probe = ttsProbe ?: return@LaunchedEffect
        eu.kanade.tachiyomi.data.tts.VoiceHelper.prepareForLanguage(probe, "ru")
        for (attempt in 0 until 8) {
            val found = eu.kanade.tachiyomi.data.tts.VoiceHelper.russianVoices(probe, enginePkg)
            if (found.isNotEmpty()) { systemVoices = found; return@LaunchedEffect }
            kotlinx.coroutines.delay(300)
        }
        systemVoices = eu.kanade.tachiyomi.data.tts.VoiceHelper.russianVoices(probe, enginePkg)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // System voices section
        item {
            Text(
                "Системные голоса (Android TTS)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (systemVoices.isEmpty() && systemReady) {
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        "Русские голоса не найдены. Установите RHVoice или другой TTS-движок с русскими голосами.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        items(systemVoices) { voice ->
            val kind = eu.kanade.tachiyomi.data.tts.VoiceHelper.classify(voice)
            val isActive = prefs.voiceName().get() == voice.name
            val genderIcon = when (kind) {
                eu.kanade.tachiyomi.data.tts.VoiceKind.FEMALE -> Icons.Outlined.Female
                eu.kanade.tachiyomi.data.tts.VoiceKind.MALE -> Icons.Outlined.Male
                else -> Icons.Default.PlayArrow
            }
            val genderLabel = when (kind) {
                eu.kanade.tachiyomi.data.tts.VoiceKind.FEMALE -> "Женский"
                eu.kanade.tachiyomi.data.tts.VoiceKind.MALE -> "Мужской"
                else -> "Другой"
            }

            Card(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(genderIcon, genderLabel, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(voice.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text("$genderLabel · ${voice.locale}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isActive) {
                        Icon(Icons.Default.CheckCircle, "Активен", tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = {
                        TtsSpeaker.speak(context, "Привет! Это тест голоса.") {
                            if (!it) context.toast("Готово")
                        }
                    }) { Icon(Icons.Default.PlayArrow, "Проба") }
                    Spacer(Modifier.width(4.dp))
                    OutlinedButton(onClick = {
                        prefs.voiceName().set(voice.name)
                        prefs.voiceEngine().set(TtsSpeaker.ENGINE_SYSTEM)
                        context.toast("Голос активирован: ${voice.name}")
                    }) { Text("Актив.") }
                }
            }
        }

        // ONNX voices section
        item {
            Spacer(Modifier.height(16.dp))
            Text(
                "Нейроголоса ONNX (офлайн)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                "Скачиваются один раз, работают без интернета. Требуется TTS-рантайм (~30 МБ).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }

        // Runtime download button
        item {
            val runtimeInstalled = remember(installedVersion) { OnnxTts.isRuntimeInstalled(context) }
            val runtimeProg = onnxProgress.value[OnnxTts.RUNTIME_PACK_ID]
            Card(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("TTS-рантайм (sherpa-onnx)", fontWeight = FontWeight.Medium)
                        Text(if (runtimeInstalled) "Установлен" else "~46 МБ · Нужен для нейроголосов",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (runtimeProg != null) {
                            LinearProgressIndicator(
                                progress = { runtimeProg },
                                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                            )
                        }
                    }
                    if (runtimeInstalled) {
                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50))
                    } else {
                        var downloadingRuntime by remember { mutableStateOf(false) }
                        Button(
                            enabled = !downloadingRuntime,
                            onClick = {
                                downloadingRuntime = true
                                context.toast("Загрузка рантайма началась")
                                kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                    val ok = runCatching { OnnxTts.downloadRuntime(context) }.getOrDefault(false)
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        downloadingRuntime = false
                                        context.toast(if (ok) "Рантайм установлен" else "Ошибка загрузки рантайма — смотрите логи")
                                    }
                                }
                            },
                        ) { Text(if (downloadingRuntime) "Скачивается…" else "Скачать") }
                    }
                }
            }
        }

        items(onnxCatalog) { voice ->
            val installed = remember(installedVersion) { OnnxTts.isInstalled(context, voice) }
            val isOnnxActive = prefs.voiceEngine().get() == TtsSpeaker.ENGINE_ONNX &&
                prefs.onnxVoice().get() == voice.id
            val prog = onnxProgress.value[voice.id]

            Card(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isOnnxActive)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (voice.gender == "female") Icons.Outlined.Female else Icons.Outlined.Male,
                        if (voice.gender == "female") "Женский" else "Мужской",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(voice.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text("${voice.sizeMb} МБ · ${voice.gender}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (prog != null) {
                            LinearProgressIndicator(
                                progress = { prog },
                                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                            )
                        }
                    }
                    if (isOnnxActive) {
                        Icon(Icons.Default.CheckCircle, "Активен", tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                    }
                    if (installed) {
                        IconButton(onClick = {
                            prefs.voiceEngine().set(TtsSpeaker.ENGINE_ONNX)
                            prefs.onnxVoice().set(voice.id)
                            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) { TtsSpeaker.speakOnnxTest(context, voice) }
                        }) { Icon(Icons.Default.PlayArrow, "Проба") }
                        Spacer(Modifier.width(4.dp))
                        OutlinedButton(onClick = {
                            prefs.voiceEngine().set(TtsSpeaker.ENGINE_ONNX)
                            prefs.onnxVoice().set(voice.id)
                            context.toast("Нейроголос активирован: ${voice.name}")
                        }) { Text("Актив.") }
                    } else {
                        Button(onClick = {
                            context.toast("Загрузка голоса ${voice.name}…")
                            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                                val ok = runCatching { OnnxTts.download(context, voice) }.getOrDefault(false)
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    if (ok) context.toast("Голос ${voice.name} установлен") 
                                    else context.toast("Ошибка загрузки ${voice.name}")
                                }
                            }
                        }) { Text("${voice.sizeMb} МБ") }
                    }
                }
            }
        }
    }
}

// ==========================================
// Tab 2: SEMI-ONLINE — Google Translate TTS
// ==========================================
@Composable
private fun SemiOnlineTab(context: Context, prefs: OcrPreferences) {
    var webLang by remember { mutableStateOf(prefs.ttsWebLanguage().get()) }
    var testText by remember { mutableStateOf("Привет! Это тест голоса Google Translate.") }
    val isActive = prefs.voiceEngine().get() == TtsSpeaker.ENGINE_GOOGLE_WEB

    val languages = listOf(
        "ru" to "Русский",
        "en" to "English",
        "ja" to "日本語",
        "ko" to "한국어",
        "zh" to "中文",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "pt" to "Português",
        "it" to "Italiano",
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isActive)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Google Translate TTS",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Бесплатная озвучка с сайта Google Translate. Не требует API-ключ. Работает при наличии интернета. Один голос на язык, но очень быстрый и стабильный.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("Язык озвучки:", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    languages.forEach { (code, name) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    webLang = code
                                    prefs.ttsWebLanguage().set(code)
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = webLang == code,
                                onClick = { webLang = code; prefs.ttsWebLanguage().set(code) },
                            )
                            Text(name, modifier = Modifier.padding(start = 4.dp))
                            if (webLang == code) {
                                Spacer(Modifier.weight(1f))
                                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row {
                        if (isActive) {
                            Icon(Icons.Default.CheckCircle, "Активен",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp).padding(end = 8.dp).align(Alignment.CenterVertically))
                        }
                        Button(onClick = {
                            prefs.voiceEngine().set(TtsSpeaker.ENGINE_GOOGLE_WEB)
                            context.toast("Google Translate TTS активирован")
                        }) { Text("Активировать") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            TtsSpeaker.speak(context, testText)
                        }) { Text("Проба") }
                    }
                }
            }
        }
    }
}

// ==========================================
// Tab 3: ONLINE — ElevenLabs + cloud
// ==========================================
@Composable
private fun OnlineTab(context: Context, prefs: OcrPreferences) {
    var apiKey by remember { mutableStateOf(prefs.elevenApiKey().get()) }
    var voiceId by remember { mutableStateOf(prefs.elevenVoiceId().get()) }
    var elevenVoices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val isActive = prefs.voiceEngine().get() == TtsSpeaker.ENGINE_ELEVENLABS

    LaunchedEffect(apiKey) {
        if (apiKey.isNotBlank()) {
            loading = true
            elevenVoices = TtsSpeaker.fetchElevenVoices(apiKey)
            loading = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // API key input
        item {
            Card(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("ElevenLabs API", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Премиальные нейроголоса с эмоциями. Требуется бесплатный API-ключ с elevenlabs.io. Ограничение: 10 000 символов/месяц на бесплатном плане.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            prefs.elevenApiKey().set(it)
                        },
                        label = { Text("API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (apiKey.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(onClick = {
                                prefs.voiceEngine().set(TtsSpeaker.ENGINE_ELEVENLABS)
                                context.toast("ElevenLabs активирован")
                            }) { Text("Активировать") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                TtsSpeaker.speak(context, "Привет! Это тест ElevenLabs.")
                            }) { Text("Проба") }
                        }
                    }
                }
            }
        }

        // Voice list
        if (apiKey.isNotBlank()) {
            item { Text("Доступные голосы:", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }

            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            items(elevenVoices) { (id, name) ->
                val selected = voiceId == id
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected)
                            MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        }
                        if (selected) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                        }
                        IconButton(onClick = {
                            voiceId = id
                            prefs.elevenVoiceId().set(id)
                            TtsSpeaker.speak(context, "Привет! Это тест голоса.")
                        }) { Icon(Icons.Default.PlayArrow, "Проба") }
                        Spacer(Modifier.width(4.dp))
                        OutlinedButton(onClick = {
                            voiceId = id
                            prefs.elevenVoiceId().set(id)
                            context.toast("Голос выбран: $name")
                        }) { Text("Выбрать") }
                    }
                }
            }

            if (elevenVoices.isEmpty() && !loading) {
                item {
                    Text("Нет доступных голосов. Проверьте API-ключ.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
