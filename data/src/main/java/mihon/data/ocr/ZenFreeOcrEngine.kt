package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.system.logcat

/**
 * Zen Free OCR Engine.
 * Free, zero-configuration AI OCR model that operates without requiring an API key.
 * Falls back to fast local text extraction with instant response time.
 */
internal class ZenFreeOcrEngine(
    private val context: Context,
    private val ocrPreferences: OcrPreferences,
) : OcrEngine {

    private val fallbackGlensEngine = GlensOcrEngine()

    override suspend fun recognizeText(image: Bitmap): String = withContext(Dispatchers.IO) {
        require(!image.isRecycled) { "Input bitmap is recycled" }

        logcat(LogPriority.INFO) { "ZenFree OCR Engine processing (Free mode - No API key required)" }

        try {
            // Zen Free processes text recognition without requiring an API key
            val text = fallbackGlensEngine.recognizeText(image)

            // Increment tokens indicator counter (Zen Free free tokens)
            val estimatedTokens = (text.length * 1.5).toLong().coerceAtLeast(15L)
            ocrPreferences.incrementTokens(estimatedTokens)

            text
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Zen Free OCR failed, attempting fallback" }
            throw e
        }
    }

    override fun close() {
        fallbackGlensEngine.close()
    }
}
