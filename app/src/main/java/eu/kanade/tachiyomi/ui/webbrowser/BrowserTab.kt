package eu.kanade.tachiyomi.ui.webbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.data.tts.AutoReadEngine
import eu.kanade.tachiyomi.data.tts.TtsSpeaker
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Браузер с ПЕРСИСТЕНТНЫМ WebView: единственный экземпляр живёт, пока живо
 * приложение, и переиспользуется при каждом входе на вкладку. Переключение
 * на другие вкладки больше НЕ перезапускает страницу и не сбрасывает
 * позицию — раньше onRelease вызывал destroy() и всё начиналось заново.
 */
data object BrowserTab : Tab {

    private const val HOME_URL = "https://mangabuff.ru"

    @SuppressLint("StaticFieldLeak") // applicationContext — утечки нет
    private var sharedWebView: WebView? = null

    private var urlState = mutableStateOf(HOME_URL)
    private var canGoBackState = mutableStateOf(false)
    private var progressState = mutableFloatStateOf(1f)
    private var autoscrollActive = mutableStateOf(false)
    private var autoscrollSpeed = mutableFloatStateOf(2f)

    /** Режим авточтения: скан кадра → озвучка → скролл на кадр → повтор. */
    private var autoReadActive = mutableStateOf(false)
    private var autoReadEngine: AutoReadEngine? = null

    /** Захват ТОЛЬКО содержимого WebView — плавающие кнопки и оверлеи
     *  приложения в кадр физически не попадают. */
    private fun captureWebView(): android.graphics.Bitmap? {
        val wv = sharedWebView ?: return null
        if (wv.width <= 0 || wv.height <= 0) return null
        return runCatching {
            val bmp = android.graphics.Bitmap.createBitmap(
                wv.width,
                wv.height,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
            val canvas = android.graphics.Canvas(bmp)
            // Рисуем с учётом текущего скролла: видимый кадр
            canvas.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
            wv.draw(canvas)
            bmp
        }.getOrNull()
    }

    /**
     * Зона СТРАНИЦЫ КНИГИ во вьюпорте (доли 0..1): JS находит все крупные
     * <img>/<canvas> (страницы манги — читалки сайтов рисуют их именно так),
     * объединяет видимые прямоугольники и возвращает их границы. Шапки,
     * меню, комментарии и прочий интерфейс сайта в зону не попадают — OCR
     * получает уже обрезанный кадр.
     */
    private suspend fun detectBookZone(): android.graphics.RectF? {
        val wv = sharedWebView ?: return null
        val js = """
            (function() {
                var vh = window.innerHeight, vw = window.innerWidth;
                var minArea = vw * vh * 0.10; // картинка занимает >=10% экрана
                var top = vh, bottom = 0, left = vw, right = 0, found = false;
                var nodes = document.querySelectorAll('img, canvas');
                for (var i = 0; i < nodes.length; i++) {
                    var r = nodes[i].getBoundingClientRect();
                    var visW = Math.min(r.right, vw) - Math.max(r.left, 0);
                    var visH = Math.min(r.bottom, vh) - Math.max(r.top, 0);
                    if (visW <= 0 || visH <= 0) continue;
                    if (visW * visH < minArea) continue;
                    found = true;
                    top = Math.min(top, Math.max(r.top, 0));
                    bottom = Math.max(bottom, Math.min(r.bottom, vh));
                    left = Math.min(left, Math.max(r.left, 0));
                    right = Math.max(right, Math.min(r.right, vw));
                }
                if (!found) return "";
                return (left / vw) + "," + (top / vh) + "," + (right / vw) + "," + (bottom / vh);
            })()
        """.trimIndent()
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            try {
                wv.post {
                    wv.evaluateJavascript(js) { raw ->
                        val body = raw?.trim('"').orEmpty()
                        val parts = body.split(',').mapNotNull { it.toFloatOrNull() }
                        val rect = if (parts.size == 4 && parts[3] > parts[1] && parts[2] > parts[0]) {
                            android.graphics.RectF(parts[0], parts[1], parts[2], parts[3])
                        } else null
                        if (cont.isActive) cont.resume(rect) {}
                    }
                }
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null) {}
            }
        }
    }

    /** Кадр, обрезанный до зоны книги (если зона найдена). */
    private fun cropToZone(src: android.graphics.Bitmap, zone: android.graphics.RectF?): android.graphics.Bitmap {
        if (zone == null) return src
        val l = (zone.left * src.width).toInt().coerceIn(0, src.width - 1)
        val t = (zone.top * src.height).toInt().coerceIn(0, src.height - 1)
        val r = (zone.right * src.width).toInt().coerceIn(l + 1, src.width)
        val b = (zone.bottom * src.height).toInt().coerceIn(t + 1, src.height)
        if (r - l < 64 || b - t < 64) return src
        val cropped = android.graphics.Bitmap.createBitmap(src, l, t, r - l, b - t)
        if (cropped !== src && !src.isRecycled) src.recycle()
        return cropped
    }

    override val options: TabOptions
        @Composable
        get() {
            return TabOptions(
                index = 7u,
                title = "Браузер",
                icon = rememberVectorPainter(Icons.Outlined.Language),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun obtainWebView(context: Context): WebView {
        sharedWebView?.let { return it }

        val webView = WebView(context.applicationContext).apply {
            setBackgroundColor(Color.parseColor("#13141F"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progressState.floatValue = newProgress / 100f
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    canGoBackState.value = view.canGoBack()
                    url?.let { urlState.value = it }
                }
            }
        }
        sharedWebView = webView
        // Загрузка стартовой страницы — ПОСЛЕ первого кадра вкладки:
        // раньше loadUrl в момент создания фризил переход (инициализация
        // сети/рендера WebView блокировала главный поток при открытии).
        webView.post { webView.loadUrl(HOME_URL) }
        return webView
    }

    @Composable
    override fun Content() {
        var urlBar by urlState
        val canGoBack by canGoBackState
        val progress by progressState

        BackHandler(enabled = canGoBack) {
            sharedWebView?.goBack()
        }

        var menuOpen by remember { mutableStateOf(false) }
        var isAuto by autoscrollActive
        var speed by autoscrollSpeed
        var fabX by remember { mutableFloatStateOf(0f) }
        var fabY by remember { mutableFloatStateOf(0f) }

        // Автоскролл страницы: плавно, скорость 1..10
        LaunchedEffect(isAuto, speed) {
            while (isAuto) {
                sharedWebView?.scrollBy(0, (speed * 3).roundToInt())
                delay(16)
            }
        }

        var isAutoRead by autoReadActive
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val readEngine = remember { autoReadEngine ?: AutoReadEngine(ctx.applicationContext).also { autoReadEngine = it } }
        val currentRegion by readEngine.currentRegion.collectAsState()

        // Цикл авточтения:
        // • кадр = ПОЛНЫЙ видимый вьюпорт;
        // • скролл на 60% кадра => соседние кадры перекрываются на 40%,
        //   текст на стыке гарантированно попадает в один из кадров целиком
        //   (дубликаты отсекает нечёткая история движка);
        // • скролл ПЛАВНЫЙ (тот же механизм, что «Автопрокрутка»: мелкие шаги
        //   каждые 16мс) и идёт ТОЛЬКО после полного прочтения кадра;
        // • пустые кадры (нет нового текста) проходятся сразу, без задержек.
        LaunchedEffect(isAutoRead) {
            if (!isAutoRead) { readEngine.stop(); return@LaunchedEffect }
            readEngine.clearHistory()
            var stuckCounter = 0
            while (isAutoRead) {
                val wv = sharedWebView
                val raw = captureWebView()
                if (wv == null || raw == null) { delay(500); continue }

                // Только страница книги: зона крупных картинок, без UI сайта
                val zone = detectBookZone()
                readEngine.highlightZone = zone
                val bmp = cropToZone(raw, zone)

                var finished = false
                readEngine.readFrame(bmp, chapterId = -1L, pageIndex = wv.scrollY) { finished = true }
                while (!finished && isAutoRead) delay(120)
                if (!isAutoRead) break

                val prefs = Injekt.get<mihon.domain.ocr.service.OcrPreferences>()
                if (!prefs.autoReadAutoAdvance().get()) { isAutoRead = false; break }

                // Плавный скролл на 60% высоты кадра (перекрытие 40%)
                val startY = wv.scrollY
                val step = (wv.height * 0.6f).roundToInt().coerceAtLeast(1)
                var scrolled = 0
                while (scrolled < step && isAutoRead) {
                    val d = minOf(6, step - scrolled) // мелкий шаг = плавно, без рывков
                    wv.scrollBy(0, d)
                    scrolled += d
                    delay(16)
                }
                // Пустой кадр — не ждём дорисовку, идём дальше сразу
                if (readEngine.lastFrameHadText) delay(350)

                if (wv.scrollY <= startY) {
                    // Не сдвинулись — конец страницы (или контент короче экрана)
                    stuckCounter++
                    if (stuckCounter >= 2) { isAutoRead = false; break }
                } else {
                    stuckCounter = 0
                }
            }
        }

        // Живучесть стопа: уход с вкладки/сворачивание = полная остановка
        DisposableEffect(Unit) {
            onDispose {
                if (autoReadActive.value) {
                    autoReadActive.value = false
                    readEngine.stop()
                }
            }
        }
        DisposableEffect(Unit) {
            onDispose { /* WebView живёт дальше, скролл остановится сам по isAuto */ }
        }

        Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                OutlinedTextField(
                    value = urlBar,
                    onValueChange = { urlBar = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = { Text("Адрес или поиск", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val input = urlBar.trim()
                                val target = when {
                                    input.startsWith("http://") || input.startsWith("https://") -> input
                                    input.contains('.') && !input.contains(' ') -> "https://$input"
                                    else -> "https://www.google.com/search?q=" +
                                        java.net.URLEncoder.encode(input, "UTF-8")
                                }
                                sharedWebView?.loadUrl(target)
                            },
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Перейти/обновить")
                        }
                    },
                )
            }
            if (progress < 1f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                factory = { ctx ->
                    val webView = obtainWebView(ctx)
                    // Отцепляем от прошлого родителя, если вкладку пересоздали
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.onResume()
                    webView
                },
                update = { webView ->
                    canGoBackState.value = webView.canGoBack()
                },
                onRelease = { webView ->
                    // НЕ destroy(): просто ставим на паузу и отцепляем от иерархии,
                    // страница и позиция скролла сохраняются до возврата на вкладку
                    webView.onPause()
                    (webView.parent as? ViewGroup)?.removeView(webView)
                },
            )
        }

        // Линейка чтения (как в AlReader): подсветка текущей реплики
        currentRegion?.let { region ->
            eu.kanade.presentation.reader.components.AutoReadHighlight(region = region, engine = readEngine)
        }

        // Плавающее SAO-меню браузера: автоскролл, наверх, закрыть
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomEnd,
        ) {
            Column(
                modifier = Modifier
                    .offset { IntOffset(fabX.roundToInt(), fabY.roundToInt()) }
                    .padding(16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedVisibility(
                    visible = menuOpen,
                    enter = fadeIn() + scaleIn(initialScale = 0.8f),
                    exit = fadeOut() + scaleOut(targetScale = 0.8f),
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.End,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (isAuto) "Стоп прокрутки  " else "Автопрокрутка  ",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                SmallFloatingActionButton(onClick = { isAuto = !isAuto }) {
                                    Icon(
                                        if (isAuto) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                        contentDescription = "Автопрокрутка",
                                        tint = if (isAuto) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            if (isAuto) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Speed, contentDescription = null)
                                    Slider(
                                        value = speed,
                                        onValueChange = { speed = it },
                                        valueRange = 1f..10f,
                                        modifier = Modifier.width(140.dp),
                                    )
                                    Text("×${speed.roundToInt()}")
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (isAutoRead) "Стоп авточтения  " else "Авточтение страницы  ",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                SmallFloatingActionButton(onClick = {
                                    if (isAutoRead) {
                                        isAutoRead = false
                                        readEngine.stop()
                                    } else {
                                        isAuto = false // выключаем простой автоскролл
                                        isAutoRead = true
                                        menuOpen = false
                                    }
                                }) {
                                    Icon(
                                        if (isAutoRead) Icons.Outlined.Stop else Icons.Outlined.RecordVoiceOver,
                                        contentDescription = "Авточтение",
                                        tint = if (isAutoRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            run {
                                val prefs = Injekt.get<mihon.domain.ocr.service.OcrPreferences>()
                                var lang by remember { mutableStateOf(prefs.autoReadLanguage().get()) }
                                var translate by remember { mutableStateOf(prefs.autoReadTranslate().get()) }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val langLabel = when (lang) {
                                        "ru" -> "🇷🇺 Русский"; "en" -> "🇬🇧 English"; "ja" -> "🇯🇵 日本語"
                                        "ko" -> "🇰🇷 한국어"; "zh" -> "🇨🇳 中文"; else -> "🌐 Любой"
                                    }
                                    Text("Читать: $langLabel  ", style = MaterialTheme.typography.labelMedium)
                                    SmallFloatingActionButton(onClick = {
                                        val next = when (lang) {
                                            "ru" -> "en"; "en" -> "ja"; "ja" -> "ko"; "ko" -> "zh"; "zh" -> "any"; else -> "ru"
                                        }
                                        lang = next
                                        prefs.autoReadLanguage().set(next)
                                    }) { Icon(Icons.Outlined.Language, contentDescription = "Язык чтения") }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (translate) "Перевод: вкл  " else "Перевод: выкл  ",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    SmallFloatingActionButton(onClick = {
                                        translate = !translate
                                        prefs.autoReadTranslate().set(translate)
                                    }) {
                                        Icon(
                                            Icons.Outlined.Translate,
                                            contentDescription = "Переводить",
                                            tint = if (translate) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Наверх  ", style = MaterialTheme.typography.labelMedium)
                                SmallFloatingActionButton(onClick = {
                                    sharedWebView?.scrollTo(0, 0)
                                    menuOpen = false
                                }) {
                                    Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Наверх")
                                }
                            }
                        }
                    }
                }
                FloatingActionButton(
                    onClick = { menuOpen = !menuOpen },
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            fabX = (fabX + dragAmount.x).coerceAtMost(0f)
                            fabY = (fabY + dragAmount.y).coerceAtMost(0f)
                        }
                    },
                ) {
                    Icon(
                        if (menuOpen) Icons.Outlined.Close else Icons.Outlined.Menu,
                        contentDescription = "Меню браузера",
                    )
                }
            }
        }
        }
    }
}
