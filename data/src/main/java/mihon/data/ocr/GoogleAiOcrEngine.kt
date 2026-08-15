package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * OCR Engine backed by Google AI / Gemini Vision API.
 */
internal class GoogleAiOcrEngine(
    private val context: Context,
    private val ocrPreferences: OcrPreferences,
) : OcrEngine {

    override suspend fun recognizeText(image: Bitmap): String = withContext(Dispatchers.IO) {
        require(!image.isRecycled) { "Input bitmap is recycled" }

        val apiKey = ocrPreferences.googleApiKey().get()
        val model = ocrPreferences.googleModel().get().ifBlank { "gemini-2.5-flash" }
        val base64Image = encodeBitmapToBase64(image)

        val jsonBody = JSONObject().apply {
            val contents = JSONArray()
            val contentItem = JSONObject().apply {
                val parts = JSONArray()
                parts.put(JSONObject().apply {
                    put("text", "Extract all text from this image as accurately as possible. Return only the extracted text.")
                })
                parts.put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", "image/png")
                        put("data", base64Image)
                    })
                })
                put("parts", parts)
            }
            contents.put(contentItem)
            put("contents", contents)
        }

        val urlString = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json")
        }

        try {
            connection.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(Charsets.UTF_8))
            }

            val statusCode = connection.responseCode
            val responseText = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

            if (statusCode !in 200..299) {
                throw Exception("Google AI request failed HTTP $statusCode: ${responseText.take(200)}")
            }

            val jsonResponse = JSONObject(responseText)
            val candidates = jsonResponse.optJSONArray("candidates")
            val extractedText = if (candidates != null && candidates.length() > 0) {
                val candidateParts = candidates.getJSONObject(0)
                    .optJSONObject("content")?.optJSONArray("parts")
                if (candidateParts != null && candidateParts.length() > 0) {
                    candidateParts.getJSONObject(0).optString("text", "")
                } else ""
            } else ""

            // Increment usage tokens indicator
            val usageMetadata = jsonResponse.optJSONObject("usageMetadata")
            val totalTokens = usageMetadata?.optLong("totalTokenCount", 120L) ?: 120L
            ocrPreferences.incrementTokens(totalTokens)

            extractedText.trim()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Google AI OCR failed" }
            throw e
        } finally {
            connection.disconnect()
        }
    }

    override fun close() = Unit

    private fun encodeBitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
        val byteArray = stream.toByteArray()
        return Base64.getEncoder().encodeToString(byteArray)
    }
}
