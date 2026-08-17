package eu.kanade.tachiyomi.ui.mangalib

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
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

/**
 * MangaLib PWA как отдельная нативная вкладка рядом с Библиотекой.
 *
 * - systemBarsPadding: WebView живёт строго в пределах экрана приложения,
 *   не заезжая под статусбар — кнопки в верхней части снова нажимаются.
 * - Файловые кнопки MangaLib используют штатный механизм WebView
 *   (onShowFileChooser -> системный пикер), а не тост-заглушки.
 * - Мост YomihonBridge даёт OCR/перевод/озвучку нативными движками.
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
        var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

        // Системный пикер для <input type=file> внутри MangaLib (файлы, CBZ, изображения).
        val fileChooserLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            filePathCallback?.onReceiveValue(uris.toTypedArray())
            filePathCallback = null
        }

        BackHandler(enabled = canGoBack) {
            activeWebView?.goBack()
        }

        AndroidView(
            // В пределах экрана приложения: не под статусбаром и не под шторкой.
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
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

                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: WebView,
                            callback: ValueCallback<Array<Uri>>,
                            fileChooserParams: FileChooserParams,
                        ): Boolean {
                            // Отменяем предыдущий незакрытый колбэк, иначе пикер
                            // больше никогда не откроется.
                            filePathCallback?.onReceiveValue(null)
                            filePathCallback = callback
                            val types = fileChooserParams.acceptTypes
                                ?.filter { it.isNotBlank() }
                                ?.toTypedArray()
                                .takeUnless { it.isNullOrEmpty() }
                                ?: arrayOf("*/*")
                            runCatching { fileChooserLauncher.launch(types) }
                                .onFailure {
                                    filePathCallback?.onReceiveValue(null)
                                    filePathCallback = null
                                }
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            canGoBack = view.canGoBack()
                        }
                    }

                    val bridge = YomihonWebBridge(
                        context = ctx,
                        onTriggerScan = {},
                        onOpenSafFolder = {},
                        onOpenCbzFile = {},
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
