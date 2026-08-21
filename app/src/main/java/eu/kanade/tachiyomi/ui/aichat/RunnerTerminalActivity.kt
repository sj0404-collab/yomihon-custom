package eu.kanade.tachiyomi.ui.aichat

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.HttpAuthHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity

/**
 * ВСТРОЕННОЕ ОКНО ТЕРМИНАЛА РАНЕРА (по запросу пользователя: «открыть его
 * ранер сам, будь то линукс либо виндовс, и печатать/искать прямо в нём»).
 *
 * Отдельная активити с WebView на ttyd-туннель: полноценная консоль ранера
 * (bash на Linux, cmd на Windows) с клавиатурой — печатать команды,
 * grep-ать логи, смотреть файлы. Basic-auth подставляется автоматически
 * (yomikai + ключ сессии) — ничего вводить руками не нужно.
 */
class RunnerTerminalActivity : BaseActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val user = intent.getStringExtra(EXTRA_USER) ?: "yomikai"
        val pass = intent.getStringExtra(EXTRA_PASS) ?: ""

        title = "Терминал ранера"
        val wv = WebView(this)
        webView = wv
        setContentView(wv)
        wv.settings.javaScriptEnabled = true // ttyd — это xterm.js, без JS никак
        wv.settings.domStorageEnabled = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        wv.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler,
                host: String?,
                realm: String?,
            ) {
                // Автоподстановка basic-auth: логин/пароль сессии
                handler.proceed(user, pass)
            }
        }
        wv.loadUrl(url)
    }

    override fun onDestroy() {
        runCatching {
            webView?.stopLoading()
            webView?.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "terminal_url"
        private const val EXTRA_USER = "terminal_user"
        private const val EXTRA_PASS = "terminal_pass"

        fun newIntent(context: Context, url: String, user: String, pass: String): Intent =
            Intent(context, RunnerTerminalActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_USER, user)
                putExtra(EXTRA_PASS, pass)
            }
    }
}
