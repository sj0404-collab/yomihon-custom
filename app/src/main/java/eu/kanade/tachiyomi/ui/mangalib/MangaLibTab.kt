package eu.kanade.tachiyomi.ui.mangalib

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.reader.html.YomihonWebBridge
import eu.kanade.tachiyomi.util.system.toast

/**
 * MangaLib PWA как отдельная нативная вкладка рядом с Библиотекой.
 *
 * ВАЖНО: окружение WebView сделано 1-в-1 как в PwaScreen (где MangaLib в
 * iframe работает): activity-контекст, те же настройки, БЕЗ инъекций в
 * localStorage — прошлые внедрённые флаги ломали рендер локальных экранов
 * (Полки/История/Настройки оставались чёрными, работал только Веб-режим).
 * Данные MangaLib живут в localStorage/IndexedDB, поэтому пересоздание
 * WebView при переключении вкладок состояние библиотеки не теряет.
 */
data object MangaLibTab : Tab {

    private var activeWebView: WebView? = null

    override val options: TabOptions
        @Composable
        get() {
            return TabOptions(
                index = 6u,
                title = "MangaLib",
                icon = rememberVectorPainter(Icons.Outlined.AutoStories),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        activeWebView?.evaluateJavascript(
            "window.dispatchEvent(new Event('yomihon-tab-reselect'));",
            null,
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun Content() {
        var canGoBack by remember { mutableStateOf(false) }

        // Системная кнопка "Назад" ходит по истории WebView, а не закрывает приложение.
        BackHandler(enabled = canGoBack) {
            activeWebView?.goBack()
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(Color.parseColor("#13141F"))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            canGoBack = view.canGoBack()
                        }
                    }

                    val bridge = YomihonWebBridge(
                        context = ctx,
                        onTriggerScan = { ctx.toast("OCR выполняется нативным движком") },
                        onOpenSafFolder = { ctx.toast("Выбор папки доступен во вкладке Обзор") },
                        onOpenCbzFile = { ctx.toast("Открытие CBZ доступно во вкладке Обзор") },
                    )
                    addJavascriptInterface(bridge, "YomihonBridge")

                    loadUrl("file:///android_asset/pwa/mangalib/index.html")
                    activeWebView = this
                }
            },
            update = { webView ->
                activeWebView = webView
                canGoBack = webView.canGoBack()
            },
            onRelease = { webView ->
                if (activeWebView === webView) activeWebView = null
                webView.destroy()
            },
        )
    }
}
