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

    /** Модели Zen, проверенные без ключа (18.08.2026). */
    val ZEN_MODELS = listOf(
        "mimo-v2.5-free",
        "deepseek-v4-flash-free",
        "laguna-s-2.1-free",
        "nemotron-3.5-lightning-free",
        "nemotron-3-ultra-free",
        "hy3-free",
        "big-pickle",
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
    suspend fun chat(userPrompt: String, systemPrompt: String? = null): String? =
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
                return@withContext chatRaw(
                    "https://opencode.ai/zen/v1/chat/completions",
                    p.zenModel().get().ifBlank { ZEN_MODELS.first() },
                    "",
                    userPrompt,
                    systemPrompt,
                )
            }
            chatRaw(url, model, key, userPrompt, systemPrompt)
        }

    private fun chatRaw(
        url: String,
        model: String,
        apiKey: String,
        userPrompt: String,
        systemPrompt: String?,
    ): String? {
        return try {
            val messages = JSONArray()
            if (!systemPrompt.isNullOrBlank()) {
                messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
            }
            messages.put(JSONObject().put("role", "user").put("content", userPrompt))
            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("max_tokens", 500)
                .put("temperature", 0.0)

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
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
            JSONObject(text)
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content")
                ?.trim()?.ifBlank { null }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "AI assistant call failed ($model)" }
            null
        }
    }

    /**
     * Пол говорящих для реплик, где локальная морфология не уверена.
     * Один батч-запрос на кадр. Ответ — JSON-массив "male"/"female"/"unknown".
     */
    suspend fun detectGendersByText(lines: List<String>): List<String?> {
        if (lines.isEmpty()) return emptyList()
        val numbered = lines.mapIndexed { i, t -> "${i + 1}. ${t.take(140)}" }.joinToString("\n")
        val answer = chat(
            userPrompt = "Реплики из манги. Определи пол говорящего КАЖДОЙ реплики по стилю речи, " +
                "окончаниям глаголов и содержанию. Ответь ТОЛЬКО JSON-массивом строк той же длины, " +
                "каждая строго \"male\", \"female\" или \"unknown\". Без пояснений.\n\n" + numbered,
            systemPrompt = "Ты определяешь пол говорящего по тексту реплики. Отвечаешь только JSON-массивом.",
        ) ?: return List(lines.size) { null }

        return runCatching {
            val cleaned = answer.replace("```json", "").replace("```", "").trim()
            val start = cleaned.indexOf('[')
            val end = cleaned.lastIndexOf(']')
            val arr = JSONArray(cleaned.substring(start, end + 1))
            List(lines.size) { i ->
                when (arr.optString(i, "unknown").lowercase()) {
                    "male" -> "male"
                    "female" -> "female"
                    else -> null
                }
            }
        }.getOrDefault(List(lines.size) { null })
    }
}
