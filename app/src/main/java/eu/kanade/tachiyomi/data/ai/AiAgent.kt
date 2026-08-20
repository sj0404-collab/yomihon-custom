package eu.kanade.tachiyomi.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import mihon.domain.ocr.model.OcrImage
import mihon.domain.ocr.repository.OcrRepository
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Агентское ядро встроенного AI-чата (вкладка «AI»). Это НЕ заглушка:
 * модель (Zen/OpenRouter, как в AiAssistant) получает список реальных
 * инструментов и вызывает их через простой текстовый протокол
 * `@tool имя {json}` — одна строка на вызов. Результат инструмента
 * возвращается модели, и она отвечает пользователю.
 *
 * Реальные инструменты:
 *  • write_file    — сохранить файл в workspace (/sdcard/Yomikai/AI)
 *  • gen_image     — сгенерировать картинку через Pollinations (без ключа)
 *  • check_site    — проверить, работает ли сайт (реальный HTTP-запрос)
 *  • list_ext      — перечислить установленные расширения-источники
 *  • filter_ext    — скрыть/показать источники по запросу (правит
 *                    hidden_catalogues — тот же механизм, что в настройках)
 *  • find_manga    — поиск тайтла по включённым источникам (реальный
 *                    getSearchManga каждого источника, до 8 источников)
 *  • zip_workspace — упаковать workspace в zip
 *
 * Смена доменов источников: URL расширений вшиты в их APK; агент не может
 * их «переписать», но check_site честно проверяет зеркала, а find_manga
 * показывает, в каком источнике тайтл РЕАЛЬНО открывается — это решает
 * задачу «не искать долго».
 */
object AiAgent {

    data class ToolCall(val name: String, val args: JSONObject)
    data class ToolResult(val name: String, val output: String, val fileProduced: File? = null)

    data class AgentReply(
        val text: String,
        val toolResults: List<ToolResult>,
        val images: List<File>,
    )

    private val sourceManager: SourceManager by lazy { Injekt.get() }
    private val sourcePrefs: SourcePreferences by lazy { Injekt.get() }

    private const val SYSTEM_PROMPT =
        "Ты — встроенный AI-агент манга-читалки Yomikai (как arena.ai agent, но внутри приложения). " +
            "Отвечай кратко и по-русски. У тебя есть ИНСТРУМЕНТЫ. Чтобы вызвать инструмент, " +
            "напиши отдельной строкой: @tool имя {json-аргументы}. Доступные инструменты:\n" +
            "@tool write_file {\"name\":\"путь/файл.txt\",\"content\":\"текст\"} — сохранить файл в workspace\n" +
            "@tool gen_image {\"prompt\":\"описание на английском\"} — нарисовать картинку (Pollinations)\n" +
            "@tool check_site {\"url\":\"https://...\"} — проверить, работает ли сайт\n" +
            "@tool list_ext {} — список установленных расширений-источников с их доменами\n" +
            "@tool filter_ext {\"hide\":\"подстрока\",\"show\":\"подстрока\"} — скрыть/показать источники по имени/языку\n" +
            "@tool find_manga {\"title\":\"название\"} — найти мангу по включённым источникам, вернёт где реально открывается\n" +
            "@tool zip_workspace {} — упаковать workspace в zip\n" +
            "Можно несколько @tool в одном ответе. После строк @tool больше ничего не пиши — " +
            "результаты придут следующим сообщением, тогда и ответишь пользователю."

    /**
     * Один ход агента: prompt пользователя (+опц. текст из вложений) →
     * модель → выполнение @tool-вызовов → второй запрос модели с
     * результатами → финальный ответ.
     */
    suspend fun run(
        context: Context,
        userText: String,
        attachmentsInfo: String? = null,
        history: List<Pair<String, String>> = emptyList(), // role to content
    ): AgentReply = withContext(Dispatchers.IO) {
        val results = mutableListOf<ToolResult>()
        val images = mutableListOf<File>()

        val historyBlock = history.takeLast(8).joinToString("\n") { (role, c) ->
            (if (role == "user") "Пользователь: " else "Ассистент: ") + c.take(300)
        }
        val prompt = buildString {
            if (historyBlock.isNotBlank()) append("Контекст диалога:\n").append(historyBlock).append("\n\n")
            if (!attachmentsInfo.isNullOrBlank()) append("Вложения пользователя:\n").append(attachmentsInfo).append("\n\n")
            append(userText)
        }

        var answer = AiAssistant.chat(prompt, SYSTEM_PROMPT, maxTokens = 900)
            ?: return@withContext AgentReply(
                "Нет ответа от AI-провайдера (сеть/лимиты). Попробуйте ещё раз или смените модель в настройках озвучки.",
                emptyList(), emptyList(),
            )

        // До 2 раундов инструментов, чтобы не зациклиться
        repeat(2) {
            val calls = parseToolCalls(answer!!)
            if (calls.isEmpty()) return@repeat
            val outputs = calls.map { call ->
                val r = runCatching { execute(context, call) }
                    .getOrElse { ToolResult(call.name, "ОШИБКА: ${it.message?.take(160)}") }
                if (r.fileProduced != null && r.name == "gen_image") images += r.fileProduced
                results += r
                "${r.name}: ${r.output.take(700)}"
            }
            val followUp = "Результаты инструментов:\n" + outputs.joinToString("\n---\n") +
                "\n\nТеперь дай финальный ответ пользователю (без @tool, если всё сделано)."
            answer = AiAssistant.chat(
                prompt + "\n\n(твои вызовы выполнены)\n" + followUp,
                SYSTEM_PROMPT,
                maxTokens = 900,
            ) ?: outputs.joinToString("\n")
        }

        val cleanText = answer!!.lines().filterNot { it.trimStart().startsWith("@tool") }
            .joinToString("\n").trim().ifBlank { "Готово. Результаты — ниже и в workspace." }
        AgentReply(cleanText, results, images)
    }

    private fun parseToolCalls(text: String): List<ToolCall> =
        text.lines().mapNotNull { line ->
            val t = line.trim()
            if (!t.startsWith("@tool ")) return@mapNotNull null
            val rest = t.removePrefix("@tool ").trim()
            val space = rest.indexOf(' ')
            val name = if (space > 0) rest.substring(0, space) else rest
            val json = if (space > 0) rest.substring(space + 1).trim() else "{}"
            runCatching { ToolCall(name, JSONObject(json.ifBlank { "{}" })) }.getOrNull()
        }

    private suspend fun execute(context: Context, call: ToolCall): ToolResult = when (call.name) {
        "write_file" -> {
            val name = call.args.optString("name").ifBlank { "note_${System.currentTimeMillis() / 1000}.txt" }
            val content = call.args.optString("content")
            val f = AiWorkspace.writeText(context, name, content)
            if (f != null) {
                ToolResult("write_file", "Сохранено: ${AiWorkspace.relPath(context, f)} (${f.length()} байт)", f)
            } else {
                ToolResult("write_file", "ОШИБКА: некорректный путь")
            }
        }

        "gen_image" -> {
            val prompt = call.args.optString("prompt").ifBlank { "anime illustration" }
            val f = generateImage(context, prompt)
            if (f != null) {
                ToolResult("gen_image", "Картинка готова: ${AiWorkspace.relPath(context, f)}", f)
            } else {
                ToolResult("gen_image", "ОШИБКА: Pollinations не ответил (сеть?)")
            }
        }

        "check_site" -> {
            val url = call.args.optString("url")
            ToolResult("check_site", checkSite(url))
        }

        "list_ext" -> ToolResult("list_ext", listExtensions())

        "filter_ext" -> {
            val hide = call.args.optString("hide")
            val show = call.args.optString("show")
            ToolResult("filter_ext", filterExtensions(hide, show))
        }

        "find_manga" -> {
            val title = call.args.optString("title")
            ToolResult("find_manga", findManga(title))
        }

        "zip_workspace" -> {
            val f = AiWorkspace.zipAll(context)
            ToolResult("zip_workspace", "Архив: ${AiWorkspace.relPath(context, f)} (${f.length() / 1024} КБ)", f)
        }

        else -> ToolResult(call.name, "Неизвестный инструмент")
    }

    // ---- Реализации инструментов ----

    /** Pollinations: бесплатная генерация картинок без ключа. */
    suspend fun generateImage(context: Context, prompt: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://image.pollinations.ai/prompt/" +
                URLEncoder.encode(prompt.take(400), "UTF-8").replace("+", "%20") +
                "?width=768&height=768&nologo=true"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("User-Agent", "Yomikai/1.0")
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return@runCatching null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            if (bytes.size < 1000) return@runCatching null
            val f = AiWorkspace.newImageFile(context, prompt)
            f.writeBytes(bytes)
            f
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "Pollinations failed" }
            null
        }
    }

    /** Реальная проверка сайта: HTTP-статус, редиректы, время ответа. */
    private fun checkSite(rawUrl: String): String {
        if (rawUrl.isBlank()) return "ОШИБКА: пустой URL"
        val url = if (rawUrl.startsWith("http")) rawUrl else "https://$rawUrl"
        return runCatching {
            val started = System.currentTimeMillis()
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
            )
            val code = conn.responseCode
            val took = System.currentTimeMillis() - started
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            when {
                code in 200..299 -> "$url — РАБОТАЕТ (HTTP $code, ${took}мс)"
                code in 300..399 -> "$url — редирект на ${location ?: "?"} (HTTP $code)"
                code == 403 -> "$url — HTTP 403: вероятно, Cloudflare-защита; в браузере может открыться"
                else -> "$url — НЕ работает (HTTP $code)"
            }
        }.getOrElse { "$url — НЕ отвечает: ${it.message?.take(100)}" }
    }

    private fun listExtensions(): String {
        val sources = sourceManager.getAll().filterIsInstance<CatalogueSource>()
        if (sources.isEmpty()) return "Расширения не установлены"
        val disabled = sourcePrefs.disabledSources().get()
        return sources.take(60).joinToString("\n") { s ->
            val domain = (s as? HttpSource)?.baseUrl ?: "локальный"
            val state = if (s.id.toString() in disabled) "СКРЫТ" else "виден"
            "• ${s.name} [${s.lang}] — $domain — $state (id=${s.id})"
        }
    }

    /** Скрыть/показать источники по подстроке имени, языка или домена. */
    private fun filterExtensions(hide: String, show: String): String {
        val sources = sourceManager.getAll().filterIsInstance<CatalogueSource>()
        val pref = sourcePrefs.disabledSources()
        val current = pref.get().toMutableSet()
        val log = StringBuilder()
        fun matches(s: CatalogueSource, q: String): Boolean {
            val d = (s as? HttpSource)?.baseUrl.orEmpty()
            return s.name.contains(q, true) || s.lang.contains(q, true) || d.contains(q, true)
        }
        if (hide.isNotBlank()) {
            val victims = sources.filter { matches(it, hide) }
            victims.forEach { current += it.id.toString() }
            log.append("Скрыто ${victims.size}: ${victims.joinToString { it.name }.take(200)}\n")
        }
        if (show.isNotBlank()) {
            val victims = sources.filter { matches(it, show) }
            victims.forEach { current -= it.id.toString() }
            log.append("Показано ${victims.size}: ${victims.joinToString { it.name }.take(200)}\n")
        }
        pref.set(current)
        return log.toString().ifBlank { "Ничего не найдено по запросу" }
    }

    /**
     * Реальный поиск тайтла по включённым источникам: до 8 источников,
     * каждому 12с. Возвращает, где тайтл реально находится.
     */
    private suspend fun findManga(title: String): String {
        if (title.isBlank()) return "ОШИБКА: пустое название"
        val disabled = sourcePrefs.disabledSources().get()
        val sources = sourceManager.getAll().filterIsInstance<CatalogueSource>()
            .filter { it.id.toString() !in disabled }
            .take(8)
        if (sources.isEmpty()) return "Нет включённых источников"
        val sb = StringBuilder()
        for (s in sources) {
            val found = withTimeoutOrNull(12_000) {
                runCatching {
                    s.getSearchManga(1, title, eu.kanade.tachiyomi.source.model.FilterList()).mangas
                }.getOrNull()
            }
            when {
                found == null -> sb.append("• ${s.name} [${s.lang}] — таймаут/ошибка\n")
                found.isEmpty() -> sb.append("• ${s.name} [${s.lang}] — не найдено\n")
                else -> {
                    val top = found.take(3).joinToString("; ") { it.title.take(60) }
                    sb.append("• ${s.name} [${s.lang}] — НАЙДЕНО ${found.size}: $top\n")
                }
            }
        }
        return sb.toString()
    }

    /** OCR картинки-вложения текущим движком распознавания (с фолбэками). */
    suspend fun ocrAttachment(file: File): String? = withContext(Dispatchers.IO) {
        runCatching {
            val opts = BitmapFactory.Options()
            var bmp: Bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@runCatching null
            if (bmp.width > 1600) {
                val h = bmp.height * 1600 / bmp.width
                val scaled = Bitmap.createScaledBitmap(bmp, 1600, h, true)
                bmp.recycle()
                bmp = scaled
            }
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val image = OcrImage(bmp.width, bmp.height, pixels)
            bmp.recycle()
            val repo = Injekt.get<OcrRepository>()
            repo.recognizeText(image).trim().ifBlank { null }
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "AI attachment OCR failed" }
            null
        }
    }

    // JSONArray импортирован для будущих инструментов; подавляем предупреждение
    @Suppress("unused")
    private val unusedKeep = JSONArray()
}
