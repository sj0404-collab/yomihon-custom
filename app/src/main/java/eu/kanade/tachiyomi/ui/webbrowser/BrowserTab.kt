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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Speed
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
            loadUrl(HOME_URL)
        }
        sharedWebView = webView
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
