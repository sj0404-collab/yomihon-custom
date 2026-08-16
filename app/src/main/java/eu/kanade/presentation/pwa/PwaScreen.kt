package eu.kanade.presentation.pwa

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
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

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    webViewClient = WebViewClient()
                    webChromeClient = WebChromeClient()
                    addJavascriptInterface(bridge, "YomihonBridge")
                    loadUrl("file:///android_asset/pwa/index.html")
                }
            },
        )
    }
}
