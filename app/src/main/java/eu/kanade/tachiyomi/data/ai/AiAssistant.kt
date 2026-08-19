package eu.kanade.tachiyomi.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.ocr.service.OcrPreferences
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.HttpURLConnection
import java.net.URL

/**
 * Онлайн AI-ассистент читалки. Два провайдера, оба OpenAI-совместимые:
 *
 * • ZEN (opencode.ai/zen) — БЕЗ API-ключа. Бесплатные модели:
 *   mimo-v2.5-free, deepseek-v4-flash-free, laguna-s-2.1-free,
 *   nemotron-3-ultra-free, nemotron-3.5-lightning-free, hy3-free,
 *   big-pickle. Проверено живым запросом: отвечают без авторизации.
 *   ВАЖНО: vision у Zen нет («No endpoints found that support image
 *   input»), поэтому ассистент ТЕКСТОВЫЙ — пол говорящих определяет по
 *   репликам, не по картинке.
 *
 * • OPENROUTER — по API-ключу, выбор из бесплатных «:free» моделей
 *   (список тянется живьём с /api/v1/models и фильтруется по суффиксу).
 *
 * Используется авточтением: реплики без уверенного вердикта локальной
 * морфологии батчем уходят ассистенту на определение пола говорящего.
 */
object AiAssistant {

    const val PROVIDER_ZEN = "zen"
    const val PROVIDER_OPENROUTER = "openrouter"

    /**
     * Модели Zen, проверенные без ключа. Порядок = приоритет ротации:
     * первыми идут БЫСТРЫЕ без тяжёлого reasoning (laguna отвечает «жмн»
     * за долю секунды), reasoning-модели — в хвосте. При FreeUsageLimitError
     * (rate limit конкретной модели) запрос автоматически повторяется на
     * следующей модели списка.
     */
    val ZEN_MODELS = listOf(
        "laguna-s-2.1-free",
        "mimo-v2.5-free",
        "deepseek-v4-flash-free",
        "hy3-free",
        "big-pickle",
        "nemotron-3.5-lightning-free",
        "nemotron-3-ultra-free",
    )

    /** Запасной список OpenRouter :free на случай оффлайна при первом открытии. */
    val OPENROUTER_FREE_FALLBACK = listOf(
        "nvidia/nemotron-3-nano-30b-a3b:free",
        "poolside/laguna-s-2.1:free",
        "z-ai/glm-5.2:free",
        "google/gemma-4-31b-it:free",
        "nvidia/nemotron-3-super-120b-a12b:free",
    )

    private fun prefs(): OcrPreferences = Injekt.get()

    /** Запись скрытого AI-чата: что спросили, что ответила модель, сколько заняло. */
    data class LogEntry(
        val time: Long,
        val model: String,
        val prompt: String,
        val answer: String,
        val tookMs: Long,
    )

    /** Кольцевой журнал последних обращений — «скрытый чат» ассистента. */
    private val logBuffer = ArrayDeque<LogEntry>()

    @Synchronized
    fun log(): List<LogEntry> = logBuffer.toList()

    @Synchronized
    private fun addLog(e: LogEntry) {
        logBuffer.addLast(e)
        while (logBuffer.size > 40) logBuffer.removeFirst()
    }

    /** Живой список бесплатных моделей OpenRouter (":free"). */
    suspend fun fetchOpenRouterFreeModels(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("https://openrouter.ai/api/v1/models").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            val arr = JSONObject(body).getJSONArray("data")
            buildList {
                for (i in 0 until arr.length()) {
                    val id = arr.getJSONObject(i).optString("id")
                    if (id.endsWith(":free")) add(id)
                }
            }.sorted()
        }.getOrDefault(OPENROUTER_FREE_FALLBACK)
    }

    /**
     * Один chat-запрос выбранному провайдеру. null при любой ошибке —
     * вызывающий код обязан деградировать мягко (нейтральный голос,
     * пропуск перевода и т.п.).
     */
    suspend fun chat(userPrompt: String, systemPrompt: String? = null, maxTokens: Int = 500): String? =
        withContext(Dispatchers.IO) {
            val p = prefs()
            val provider = p.aiProvider().get()
            val (url, model, key) = when (provider) {
                PROVIDER_OPENROUTER -> Triple(
                    "https://openrouter.ai/api/v1/chat/completions",
                    p.openrouterFreeModel().get().ifBlank { OPENROUTER_FREE_FALLBACK.first() },
                    p.openrouterApiKey().get(),
                )
                else -> Triple(
                    "https://opencode.ai/zen/v1/chat/completions",
                    p.zenModel().get().ifBlank { ZEN_MODELS.first() },
                    "", // Zen работает без ключа
                )
            }
            if (provider == PROVIDER_OPENROUTER && key.isBlank()) {
                logcat(LogPriority.WARN) { "OpenRouter selected but no API key; falling back to Zen" }
                return@withContext zenChatWithRotation(userPrompt, systemPrompt, maxTokens)
            }
            if (provider != PROVIDER_OPENROUTER) {
                return@withContext zenChatWithRotation(userPrompt, systemPrompt, maxTokens)
            }
            chatRaw(url, model, key, userPrompt, systemPrompt, maxTokens)
        }

    /**
     * Zen с авторотацией: выбранная модель первая, при rate limit / ошибке —
     * следующая из списка. Бесплатные лимиты Zen помодельные, поэтому
     * ротация почти всегда находит живую модель.
     */
    private fun zenChatWithRotation(userPrompt: String, systemPrompt: String?, maxTokens: Int): String? {
        val preferred = prefs().zenModel().get().ifBlank { ZEN_MODELS.first() }
        val order = listOf(preferred) + ZEN_MODELS.filter { it != preferred }
        for (m in order) {
            val answer = chatRaw(
                "https://opencode.ai/zen/v1/chat/completions",
                m, "", userPrompt, systemPrompt, maxTokens,
            )
            if (answer != null) return answer
        }
        return null
    }

    private fun chatRaw(
        url: String,
        model: String,
        apiKey: String,
        userPrompt: String,
        systemPrompt: String?,
        maxTokens: Int = 500,
    ): String? {
        val startedAt = System.currentTimeMillis()
        return try {
            val messages = JSONArray()
            if (!systemPrompt.isNullOrBlank()) {
                messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
            }
            messages.put(JSONObject().put("role", "user").put("content", userPrompt))
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("max_tokens", maxTokens)
                .put("temperature", 0.0)

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                logcat(LogPriority.WARN) { "AI assistant HTTP $code ($model): ${text.take(160)}" }
                return null
            }
            val answer = JSONObject(text)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
                ?.trim()?.ifBlank { null }
            addLog(LogEntry(startedAt, model, userPrompt.take(200), (answer ?: "<пусто>").take(200), System.currentTimeMillis() - startedAt))
            answer
        } catch (e: Exception) {
            addLog(LogEntry(startedAt, model, userPrompt.take(200), "ОШИБКА: ${e.message?.take(120)}", System.currentTimeMillis() - startedAt))
            logcat(LogPriority.WARN, e) { "AI assistant call failed ($model)" }
            null
        }
    }

    /** Строка подготовленного кадра: говорить ли, каким полом, каким текстом. */
    data class PreparedLine(val speak: Boolean, val gender: String?, val text: String)

    /**
     * ГЛАВНЫЙ шаг конвейера (по требованию пользователя): текст кадра
     * отправляется В ЧАТ ДО озвучки. Модель одним запросом:
     *  1) вычищает реплики, повторяющие прошлый кадр (перекрытие скролла);
     *  2) назначает пол говорящего каждой оставшейся;
     *  3) возвращает чистый текст для синтеза.
     * Протокол ответа — по строке на реплику: «N|г|текст» (г: м/ж/н) или
     * «N|-» для пропуска дубля. Всё видно в скрытом чате (журнале).
     * Таймаут 8с: при сбое вызывающий код откатывается на локальный конвейер.
     */
    suspend fun prepareFrame(newLines: List<String>, prevLines: List<String>): List<PreparedLine>? {
        if (newLines.isEmpty()) return emptyList()
        val prevBlock = if (prevLines.isEmpty()) {
            "(прошлый кадр пуст)"
        } else {
            prevLines.takeLast(20).joinToString("\n") { "- ${it.take(90)}" }
        }
        val newBlock = newLines.mapIndexed { i, t -> "${i + 1}. ${t.take(140)}" }.joinToString("\n")
        val answer = kotlinx.coroutines.withTimeoutOrNull(8_000) {
            chat(
                userPrompt = "Прошлый кадр манги содержал реплики:\n$prevBlock\n\n" +
                    "Новый кадр:\n$newBlock\n\n" +
                    "Для КАЖДОЙ реплики нового кадра ответь отдельной строкой строго в формате " +
                    "«N|г|текст», где N — номер, г — пол говорящего (м/ж/н), " +
                    "текст — реплика, очищенная от мусора OCR. Если реплика повторяет прошлый кадр " +
                    "(даже частично/с искажениями) — ответь «N|-». Больше НИЧЕГО не пиши.",
                systemPrompt = "Ты конвейер озвучки манги: чистишь повторы и назначаешь пол. " +
                    "Отвечаешь только строками формата N|г|текст или N|-.",
                maxTokens = 600,
            )
        } ?: return null

        val byIndex = HashMap<Int, PreparedLine>()
        for (line in answer.lines()) {
            val parts = line.trim().split('|', limit = 3)
            val n = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
            if (n !in 1..newLines.size) continue
            if (parts.size < 2 || parts[1].trim() == "-") {
                byIndex[n] = PreparedLine(speak = false, gender = null, text = "")
                continue
            }
            val gender = when (parts[1].trim().lowercase()) {
                "м" -> "male"
                "ж" -> "female"
                else -> null
            }
            val text = parts.getOrNull(2)?.trim().orEmpty().ifBlank { newLines[n - 1] }
            byIndex[n] = PreparedLine(speak = true, gender = gender, text = text)
        }
        if (byIndex.isEmpty()) return null // модель ответила не по протоколу
        return List(newLines.size) { i ->
            byIndex[i + 1] ?: PreparedLine(speak = true, gender = null, text = newLines[i])
        }
    }

    /**
     * Пол говорящих — СВЕРХБЫСТРЫЙ формат: модель отвечает строкой из букв,
     * по одной на реплику: «м» (мужской), «ж» (женский), «н» (не ясно).
     * Никакого JSON и рассуждений: max_tokens=40, ответ приходит за долю
     * секунды даже у reasoning-моделей. Плюс жёсткий таймаут 6с — если сеть
     * тупит, чтение продолжается нейтральным голосом, а не ждёт модель.
     * Фолбэк: локальный словарь морфологии (LocalSpeakerAi) уже отработал
     * ДО этого вызова — сюда приходят только реплики без вердикта.
     */
    suspend fun detectGendersByText(lines: List<String>): List<String?> {
        if (lines.isEmpty()) return emptyList()
        val numbered = lines.mapIndexed { i, t -> "${i + 1}) ${t.take(100)}" }.joinToString("\n")
        val answer = kotlinx.coroutines.withTimeoutOrNull(6_000) {
            chat(
                userPrompt = "Кто говорит каждую реплику? Ответь ТОЛЬКО строкой из ${lines.size} букв " +
                    "без пробелов: м=мужчина, ж=женщина, н=неясно. Пример ответа: мжнм\n\n" + numbered,
                systemPrompt = "Отвечай только буквами м/ж/н, ничего больше. Без рассуждений.",
                maxTokens = 40,
            )
        } ?: return List(lines.size) { null }

        // Берём последнюю строку ответа (reasoning-модели любят префиксы),
        // выбрасываем всё, кроме м/ж/н
        val letters = answer.lines().lastOrNull { l -> l.any { it in "мжн" } }
            ?.filter { it in "мжн" }.orEmpty()
        return List(lines.size) { i ->
            when (letters.getOrNull(i)) {
                'м' -> "male"
                'ж' -> "female"
                else -> null
            }
        }
    }
}
