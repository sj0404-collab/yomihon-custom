package eu.kanade.tachiyomi.ui.mangalib

import android.annotation.SuppressLint
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
 * Приложение MangaLib живёт в assets (pwa/mangalib/index.html) и открывается в
 * WebView с тем же нативным мостом YomihonBridge, что и Yomihon PWA: OCR через
 * нативные модели (без tesseract.js), системная озвучка, библиотека, история.
 * WebView создаётся один раз и переживает переключение вкладок, поэтому
 * состояние читалки и позиция не теряются ни онлайн, ни оффлайн.
 */
data object MangaLibTab : Tab {

    // Держим WebView в памяти процесса, чтобы вкладка не перезагружалась.
    @SuppressLint("StaticFieldLeak")
    private var cachedWebView: WebView? = null

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
        cachedWebView?.evaluateJavascript(
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
            cachedWebView?.goBack()
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                cachedWebView?.let { existing ->
                    (existing.parent as? android.view.ViewGroup)?.removeView(existing)
                    return@AndroidView existing
                }
                WebView(ctx.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.cacheMode = WebSettings.LOAD_DEFAULT

                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            canGoBack = view.canGoBack()
                            // Прячем несовместимые с нативной обвязкой разделы
                            // MangaLib PWA (свои OCR-настройки/tesseract):
                            // нативный мост уже даёт OCR и озвучку.
                            view.evaluateJavascript(
                                """
                                (function () {
                                  try {
                                    localStorage.setItem('yomihon_embedded', '1');
                                    localStorage.setItem('ocr_engine', 'native');
                                    var css = document.createElement('style');
                                    css.textContent =
                                      '[data-section="ocr-settings"],' +
                                      '[data-screen="ocr-settings"],' +
                                      '.ocr-settings,' +
                                      '[data-tesseract]' +
                                      '{display:none !important}';
                                    document.documentElement.appendChild(css);
                                  } catch (e) {}
                                })();
                                """.trimIndent(),
                                null,
                            )
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
                    cachedWebView = this
                }
            },
            update = { webView ->
                canGoBack = webView.canGoBack()
            },
        )
    }
}
