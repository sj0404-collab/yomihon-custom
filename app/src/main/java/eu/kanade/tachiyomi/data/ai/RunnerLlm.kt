package eu.kanade.tachiyomi.data.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.ocr.service.OcrPreferences
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * ПОЛУ-ОНЛАЙН LLM: GGUF/большие модели исполняются НЕ на телефоне, а на
 * GitHub Actions ранере (workflow llm-runner.yml):
 *  1) приложение по PAT-токену диспатчит workflow с выбранной моделью и
 *     случайным id сессии;
 *  2) ранер скачивает GGUF, поднимает llama.cpp server + cloudflared-туннель
 *     и публикует артефакт endpoint-<session> c URL и ключом сессии;
 *  3) приложение опрашивает артефакты, забирает endpoint и дальше говорит с
 *     моделью напрямую (OpenAI-совместимый /v1/chat/completions).
 *
 * Каждая сессия сохраняется НА ТЕЛЕФОНЕ (files/llm_sessions/<session>.json):
 * URL, ключ, модель, история сообщений — при перезапуске приложения диалог
 * продолжается без потери контекста, пока жив ранер (до ~5.5 часов).
 */
object RunnerLlm {

    data class Session(
        val id: String,
        val model: String,
        var url: String? = null,
        var apiKey: String? = null,
        val messages: MutableList<Pair<String, String>> = mutableListOf(), // role -> content
        var createdAt: Long = System.currentTimeMillis(),
    )

    /** key -> (описание, ТОЧНЫЙ размер скачивания в ранер, МБ — проверено HEAD). */
    val GGUF_MODELS = listOf(
        Triple("qwen2.5-0.5b", "Qwen2.5 0.5B (GGUF Q4) — самый быстрый старт", 468),
        Triple("qwen2.5-1.5b", "Qwen2.5 1.5B (GGUF Q4) — лучший русский", 1065),
        Triple("llama3.2-1b", "Llama 3.2 1B (GGUF Q4) — английский", 770),
        Triple("gemma3-1b", "Gemma 3 1B (GGUF Q4) — компактная от Google", 768),
    )

    private const val REPO = "sj0404-collab/yomihon-custom"
    private const val WORKFLOW = "llm-runner.yml"

    private fun prefs(): OcrPreferences = Injekt.get()

    private fun sessionsDir(context: Context): File =
        File(context.filesDir, "llm_sessions").apply { mkdirs() }

    fun listSessions(context: Context): List<Session> =
        sessionsDir(context).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
            ?.sortedByDescending { it.createdAt }
            .orEmpty()

    fun saveSession(context: Context, s: Session) {
        File(sessionsDir(context), "${s.id}.json").writeText(toJson(s).toString())
    }

    fun deleteSession(context: Context, s: Session) {
        File(sessionsDir(context), "${s.id}.json").delete()
    }

    private fun toJson(s: Session) = JSONObject()
        .put("id", s.id).put("model", s.model).put("url", s.url ?: "")
        .put("apiKey", s.apiKey ?: "").put("createdAt", s.createdAt)
        .put(
            "messages",
            JSONArray().apply {
                s.messages.forEach { (r, c) -> put(JSONObject().put("role", r).put("content", c)) }
            },
        )

    private fun fromJson(j: JSONObject) = Session(
        id = j.getString("id"),
        model = j.getString("model"),
        url = j.optString("url").ifBlank { null },
        apiKey = j.optString("apiKey").ifBlank { null },
        createdAt = j.optLong("createdAt"),
        messages = mutableListOf<Pair<String, String>>().apply {
            val arr = j.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                add(m.getString("role") to m.getString("content"))
            }
        },
    )

    /**
     * Запуск новой сессии: dispatch workflow → ожидание артефакта endpoint
     * (обычно 2-4 минуты: скачивание модели в ранер). [onStatus] — живые
     * статусы для UI. null при ошибке/отсутствии токена.
     */
    suspend fun startSession(
        context: Context,
        modelKey: String,
        onStatus: (String) -> Unit,
    ): Session? = startSessionInternal(context, modelKey, "", onStatus)

    private suspend fun startSessionInternal(
        context: Context,
        modelKey: String,
        customUrl: String,
        onStatus: (String) -> Unit,
    ): Session? = withContext(Dispatchers.IO) {
        val token = prefs().githubPat().get()
        if (token.isBlank()) {
            onStatus("Нет GitHub-токена: задайте его в настройках вкладки AI (⚙)")
            return@withContext null
        }
        val session = Session(
            id = "s" + System.currentTimeMillis().toString(36) + (1000..9999).random(),
            model = modelKey,
        )
        onStatus("Запуск ранера…")
        val dispatched = runCatching {
            val conn = URL("https://api.github.com/repos/$REPO/actions/workflows/$WORKFLOW/dispatches")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "token $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val body = JSONObject()
                .put("ref", "main")
                .put(
                    "inputs",
                    JSONObject()
                        .put("model", modelKey)
                        .put("session", session.id)
                        .put("custom_url", customUrl),
                )
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        }.getOrDefault(false)
        if (!dispatched) {
            onStatus("Не удалось запустить workflow (токен? сеть?)")
            return@withContext null
        }

        // Ожидание артефакта endpoint-<session>: модель качается в ранер 2-5 мин
        onStatus("Ранер скачивает модель (2-5 мин)…")
        val deadline = System.currentTimeMillis() + 8 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            delay(15_000)
            val endpoint = fetchEndpointArtifact(token, session.id)
            if (endpoint != null) {
                session.url = endpoint.first
                session.apiKey = endpoint.second
                saveSession(context, session)
                onStatus("Сессия готова: ${endpoint.first}")
                return@withContext session
            }
            onStatus("Ждём туннель… (${(deadline - System.currentTimeMillis()) / 1000}с)")
        }
        onStatus("Таймаут: ранер не поднялся за 8 минут")
        null
    }

    /** Ищет артефакт endpoint-<session>, скачивает zip и достаёт endpoint.json. */
    private fun fetchEndpointArtifact(token: String, sessionId: String): Pair<String, String>? {
        return runCatching {
            val listConn = URL("https://api.github.com/repos/$REPO/actions/artifacts?per_page=20")
                .openConnection() as HttpURLConnection
            listConn.setRequestProperty("Authorization", "token $token")
            val body = listConn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            listConn.disconnect()
            val arts = JSONObject(body).getJSONArray("artifacts")
            var dlUrl: String? = null
            for (i in 0 until arts.length()) {
                val a = arts.getJSONObject(i)
                if (a.getString("name") == "endpoint-$sessionId" && !a.getBoolean("expired")) {
                    dlUrl = a.getString("archive_download_url")
                    break
                }
            }
            if (dlUrl == null) return null
            val zipConn = URL(dlUrl).openConnection() as HttpURLConnection
            zipConn.setRequestProperty("Authorization", "token $token")
            zipConn.instanceFollowRedirects = true
            val zipBytes = zipConn.inputStream.use { it.readBytes() }
            zipConn.disconnect()
            var json: String? = null
            ZipInputStream(zipBytes.inputStream()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (e.name == "endpoint.json") {
                        json = zis.readBytes().toString(Charsets.UTF_8)
                        break
                    }
                    e = zis.nextEntry
                }
            }
            val j = JSONObject(json ?: return null)
            j.getString("url") to j.getString("api_key")
        }.getOrNull()
    }

    /** Чат с ранером: OpenAI-совместимый endpoint llama.cpp. */
    suspend fun chat(context: Context, session: Session, userText: String): String? =
        withContext(Dispatchers.IO) {
            val url = session.url ?: return@withContext null
            val key = session.apiKey ?: return@withContext null
            session.messages += "user" to userText
            val messages = JSONArray()
            // Контекст: последние 24 сообщения сессии — без потери нити диалога
            session.messages.takeLast(24).forEach { (r, c) ->
                messages.put(JSONObject().put("role", r).put("content", c))
            }
            val answer = runCatching {
                val conn = URL("$url/v1/chat/completions").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 20_000
                conn.readTimeout = 180_000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $key")
                val body = JSONObject()
                    .put("model", session.model)
                    .put("messages", messages)
                    .put("max_tokens", 800)
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val text = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                conn.disconnect()
                JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content")?.trim()
            }.onFailure {
                logcat(LogPriority.WARN, it) { "Runner LLM chat failed" }
            }.getOrNull()
            if (answer != null) {
                session.messages += "assistant" to answer
                saveSession(context, session)
            }
            answer
        }

    /** Жива ли сессия (реальный запрос /health к туннелю). */
    suspend fun isAlive(session: Session): Boolean = withContext(Dispatchers.IO) {
        val url = session.url ?: return@withContext false
        runCatching {
            val conn = URL("$url/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            session.apiKey?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        }.getOrDefault(false)
    }

    /** Статус ранера для индикации в UI. */
    data class RunnerStatus(
        val alive: Boolean,
        /** Аптайм сессии, мс (от createdAt). */
        val uptimeMs: Long,
        /** Осталось до 5.5-часового лимита job, мс. */
        val remainingMs: Long,
        /** Время последней проверки. */
        val checkedAt: Long,
    )

    private const val SESSION_LIFETIME_MS = 330L * 60_000L // 5.5 часов

    /**
     * ИНДИКАЦИЯ РАНЕРА (по требованию пользователя): живой /health-пинг +
     * аптайм + сколько осталось до конца сессии.
     */
    suspend fun status(session: Session): RunnerStatus {
        val alive = isAlive(session)
        val uptime = System.currentTimeMillis() - session.createdAt
        return RunnerStatus(
            alive = alive,
            uptimeMs = uptime,
            remainingMs = (SESSION_LIFETIME_MS - uptime).coerceAtLeast(0),
            checkedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Своя GGUF-модель ПО ССЫЛКЕ: воркфлоу принимает custom_url — ранер
     * скачает её вместо каталожной. Размер неизвестен заранее — воркфлоу
     * напишет его в лог, а стартовый статус покажет прогресс этапов.
     */
    suspend fun startSessionWithUrl(
        context: Context,
        ggufUrl: String,
        onStatus: (String) -> Unit,
    ): Session? = startSessionInternal(context, "custom", ggufUrl, onStatus)
}
