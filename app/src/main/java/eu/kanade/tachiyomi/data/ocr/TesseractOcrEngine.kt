package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.ocr.service.OcrPreferences
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Полностью ОФЛАЙН OCR-движок: Tesseract с моделями eng+rus из
 * assets/ocr_packs (переехали из overlay-translator).
 *
 * Жизненный цикл по требованию пользователя:
 * • модели активируются (извлекаются из tar.xz) только при первом
 *   использовании движка;
 * • close() выгружает Tesseract и стирает извлечённые файлы — в покое
 *   остаётся лишь 2.9МБ tar.xz внутри APK. Если включено «держать модели
 *   распакованными» (pref_keep_offline_packs) — извлечённые файлы
 *   сохраняются, старт следующего распознавания мгновенный.
 *
 * Настраивается из «Text Recognition → Настройки → Офлайн-распознавание»:
 * • языки: eng+rus / rus / eng (pref_tess_langs);
 * • режим сегментации страницы: single_block / auto / sparse / single_line
 *   (pref_tess_psm) — для баллонов манги стабильнее single_block, для
 *   страниц с разбросанным текстом — sparse;
 * • апскейл мелких кропов до N px по короткой стороне (pref_tess_upscale) —
 *   Tesseract резко лучше читает мелкий текст баллонов;
 * • предобработка ч/б + контраст (pref_tess_preprocess) — убирает цветной
 *   фон баллонов, поднимает точность на скринах с градиентами.
 */
class TesseractOcrEngine(private val context: Context) {

    private var api: TessBaseAPI? = null
    private var initedLangs: String? = null
    private var initedPsm: String? = null
    private val mutex = Mutex()

    private val prefs: OcrPreferences by lazy { Injekt.get() }

    private fun psmOf(key: String): Int = when (key) {
        "auto" -> TessBaseAPI.PageSegMode.PSM_AUTO
        "sparse" -> TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
        "single_line" -> TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
        else -> TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
    }

    private suspend fun ensureInit(): TessBaseAPI? = mutex.withLock {
        val wantLangs = prefs.tessLangs().get().ifBlank { "eng+rus" }
        val wantPsm = prefs.tessPsm().get().ifBlank { "single_block" }
        api?.let { existing ->
            // Настройки языков/PSM могли поменяться с прошлого раза
            if (initedLangs == wantLangs) {
                if (initedPsm != wantPsm) {
                    existing.pageSegMode = psmOf(wantPsm)
                    initedPsm = wantPsm
                }
                return@withLock existing
            }
            runCatching { existing.recycle() }
            api = null
        }
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
                // OEM_LSTM_ONLY: только нейросетевой распознаватель — быстрее
                // и точнее устаревшего legacy-движка на текстах манги.
                if (!init(root.absolutePath, wantLangs, TessBaseAPI.OEM_LSTM_ONLY)) {
                    recycle()
                    error("Tesseract init failed for langs=$wantLangs")
                }
                pageSegMode = psmOf(wantPsm)
                setVariable("preserve_interword_spaces", "1")
                // Не пытаться распознавать инвертированный текст вторым
                // проходом — экономит до ~30% времени на кадр.
                setVariable("tessedit_do_invert", "0")
            }
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "Tesseract init failed" }
        }.getOrNull()?.also {
            api = it
            initedLangs = wantLangs
            initedPsm = wantPsm
        }
    }

    /** Ч/б + контраст: убирает цветные фоны баллонов, поднимает точность. */
    private fun preprocess(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val contrast = 1.6f
        val offset = (-0.3f * 255f * (contrast - 1f))
        val cm = ColorMatrix().apply {
            setSaturation(0f) // grayscale
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        contrast, 0f, 0f, 0f, offset,
                        0f, contrast, 0f, 0f, offset,
                        0f, 0f, contrast, 0f, offset,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    suspend fun recognize(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val engine = ensureInit() ?: return@withContext ""
        runCatching {
            // Апскейл мелких кропов: Tesseract плохо читает текст < ~20px.
            // Порог настраивается (0 = выключен).
            val minTarget = prefs.tessUpscaleMinSide().get().coerceIn(0, 1024)
            val minSide = minOf(bitmap.width, bitmap.height)
            var input = if (minTarget > 0 && minSide in 1 until minTarget) {
                val scale = minTarget.toFloat() / minSide
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true,
                )
            } else {
                bitmap
            }
            if (prefs.tessPreprocess().get()) {
                val processed = preprocess(input)
                if (input !== bitmap && !input.isRecycled) input.recycle()
                input = processed
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

    /**
     * Выгружает движок. Извлечённые модели удаляются, ТОЛЬКО если
     * пользователь не включил «держать модели распакованными».
     */
    suspend fun close() {
        mutex.withLock {
            runCatching { api?.recycle() }
            api = null
            initedLangs = null
            initedPsm = null
        }
        if (!prefs.keepOfflinePacks().get()) {
            OfflinePackManager.deactivate(context, OfflinePackManager.PACK_TESSERACT)
            File(context.cacheDir, "tess_root").deleteRecursively()
        }
    }
}
