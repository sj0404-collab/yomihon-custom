package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Полностью ОФЛАЙН OCR-движок: Tesseract с моделями eng+rus из
 * assets/ocr_packs/tess_eng_rus.tar.xz (переехали из overlay-translator).
 *
 * Жизненный цикл по требованию пользователя:
 * • модели активируются (извлекаются из tar.xz) только при первом
 *   использовании движка;
 * • close() выгружает Tesseract и стирает извлечённые файлы — в покое
 *   остаётся лишь 2.9МБ tar.xz внутри APK.
 *
 * Улучшения против исходной интеграции в overlay-translator:
 * • PSM_SINGLE_BLOCK вместо PSM_AUTO — для баллонов манги стабильнее;
 * • preserve_interword_spaces — не склеивает слова;
 * • апскейл мелких кропов до min 320px по короткой стороне — Tesseract
 *   резко лучше читает мелкий текст баллонов.
 */
class TesseractOcrEngine(private val context: Context) {

    private var api: TessBaseAPI? = null
    private val mutex = Mutex()

    private suspend fun ensureInit(): TessBaseAPI? = mutex.withLock {
        api?.let { return@withLock it }
        val packDir = OfflinePackManager.activate(context, OfflinePackManager.PACK_TESSERACT)
            ?: return@withLock null

        // Tesseract ждёт структуру <root>/tessdata/ с файлами .traineddata
        val root = File(context.cacheDir, "tess_root")
        val tessdata = File(root, "tessdata").apply { mkdirs() }
        packDir.listFiles()?.forEach { f ->
            val dst = File(tessdata, f.name)
            if (!dst.exists() || dst.length() != f.length()) f.copyTo(dst, overwrite = true)
        }

        runCatching {
            TessBaseAPI().apply {
                if (!init(root.absolutePath, "eng+rus")) {
                    recycle()
                    error("Tesseract init failed")
                }
                pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
                setVariable("preserve_interword_spaces", "1")
            }
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Tesseract init failed" }
        }.getOrNull()?.also { api = it }
    }

    suspend fun recognize(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val engine = ensureInit() ?: return@withContext ""
        runCatching {
            // Апскейл мелких кропов: Tesseract плохо читает текст < ~20px
            val minSide = minOf(bitmap.width, bitmap.height)
            val input = if (minSide in 1..319) {
                val scale = 320f / minSide
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true,
                )
            } else {
                bitmap
            }
            mutex.withLock {
                engine.setImage(input)
                val text = engine.utF8Text.orEmpty().trim()
                engine.clear()
                if (input !== bitmap && !input.isRecycled) input.recycle()
                text
            }
        }.getOrDefault("")
    }

    /** Выгружает движок и удаляет извлечённые модели (остаётся только tar.xz в APK). */
    suspend fun close() {
        mutex.withLock {
            runCatching { api?.recycle() }
            api = null
        }
        OfflinePackManager.deactivate(context, OfflinePackManager.PACK_TESSERACT)
        File(context.cacheDir, "tess_root").deleteRecursively()
    }
}
