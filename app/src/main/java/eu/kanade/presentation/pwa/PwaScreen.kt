package eu.kanade.presentation.pwa

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.reader.html.YomihonWebBridge
import eu.kanade.tachiyomi.util.system.toast
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object PwaScreen : Screen() {

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val storagePreferences = remember { Injekt.get<tachiyomi.domain.storage.service.StoragePreferences>() }

        val pickFolderLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
                storagePreferences.baseStorageDirectory.set(uri.toString())
                context.toast("Папка хранилища локальной манги успешно изменена")
            }
        }

        val pickCbzLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                val intent = Intent(context, eu.kanade.tachiyomi.ui.reader.ReaderActivity::class.java).apply {
                    data = uri
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            }
        }

        val bridge = remember {
            YomihonWebBridge(
                context = context,
                onTriggerScan = {
                    context.toast("Запуск сканирования OCR...")
                },
                onOpenSafFolder = {
                    pickFolderLauncher.launch(null)
                },
                onOpenCbzFile = {
                    pickCbzLauncher.launch(
                        arrayOf(
                            "application/x-cbz",
                            "application/zip",
                            "application/x-zip-compressed",
                            "application/x-cbr",
                            "application/x-rar-compressed",
                            "application/epub+zip",
                            "*/*",
                        ),
                    )
                },
            )
        }

        // Реальные системные отступы (статус-бар/навигация): env(safe-area-inset-*)
        // в Android WebView всегда 0, из-за чего нижняя навигация PWA уезжала
        // под системную панель. Пробрасываем инсеты в CSS-переменные --sat/--sab.
        val insets = WindowInsets.systemBars.asPaddingValues()
        val topPx = insets.calculateTopPadding().value
        val bottomPx = insets.calculateBottomPadding().value

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    // Встроенное MangaLib PWA (iframe из assets) ходит по https
                    // за обложками/главами, а само открыто с file:// — без этих
                    // флагов WebView блокирует такие запросы.
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = true
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript(
                                "document.documentElement.style.setProperty('--sat','${topPx}px');" +
                                    "document.documentElement.style.setProperty('--sab','${bottomPx}px');",
                                null,
                            )
                        }
                    }
                    webChromeClient = WebChromeClient()
                    addJavascriptInterface(bridge, "YomihonBridge")
                    loadUrl("file:///android_asset/pwa/index.html")
                }
            },
            update = { webView ->
                webView.evaluateJavascript(
                    "document.documentElement.style.setProperty('--sat','${topPx}px');" +
                        "document.documentElement.style.setProperty('--sab','${bottomPx}px');",
                    null,
                )
            },
        )
    }
}
