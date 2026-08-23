package mihon.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.domain.ocr.exception.OcrException
import tachiyomi.core.common.util.system.logcat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Downloadable Russian/Cyrillic OCR based on PaddleOCR mobile TFLite models.
 *
 * The detector finds text-line blobs on a full page. PP-OCRv3 recognizes each
 * crop; PP-OCRv5 is loaded as a conservative verifier only for low-confidence
 * crops. No dictionary spell replacement is performed: benchmarks showed that
 * it could turn a visually correct word (for example "мятой") into a wrong but
 * frequent dictionary word. Models live outside the APK in ocr_models/.
 */
internal class CyrillicOcrEngine(
    private val context: Context,
    private val environment: Environment,
    private val textPostprocessor: TextPostprocessor,
) : OcrEngine {

    private lateinit var detector: CompiledModel
    private lateinit var primary: CompiledModel
    private var verifier: CompiledModel? = null

    private lateinit var detectorInput: TensorBuffer
    private lateinit var detectorOutput: TensorBuffer
    private lateinit var primaryInput: TensorBuffer
    private lateinit var primaryOutput: TensorBuffer
    private var verifierInput: TensorBuffer? = null
    private var verifierOutput: TensorBuffer? = null

    private lateinit var primaryChars: List<String>
    private var verifierChars: List<String>? = null

    private val detectorPixels = IntArray(DETECTOR_SIZE * DETECTOR_SIZE)
    private val detectorFloats = FloatArray(DETECTOR_SIZE * DETECTOR_SIZE * 3)
    private val recognizerPixels = IntArray(RECOGNIZER_HEIGHT * RECOGNIZER_WIDTH)
    private val recognizerFloats = FloatArray(RECOGNIZER_HEIGHT * RECOGNIZER_WIDTH * 3)
    private val componentQueue = IntArray(DETECTOR_SIZE * DETECTOR_SIZE)
    private val visited = BooleanArray(DETECTOR_SIZE * DETECTOR_SIZE)

    private lateinit var detectorBitmap: Bitmap
    private lateinit var detectorCanvas: Canvas
    private lateinit var detectorPaint: Paint
    private lateinit var recognizerBitmap: Bitmap
    private lateinit var recognizerCanvas: Canvas
    private lateinit var recognizerPaint: Paint

    private val mutex = Mutex()

    @Volatile
    private var initialized = false

    private data class TextBox(val rect: Rect) {
        val centerY: Float get() = (rect.top + rect.bottom) / 2f
        val height: Int get() = rect.height()
    }

    private data class Recognition(val text: String, val confidence: Float)

    suspend fun ensureInitialized() {
        if (initialized) return
        mutex.withLock {
            if (!initialized && !init()) throw OcrException.InitializationError()
        }
    }

    private fun init(): Boolean {
        val detectorPath = OcrModelFiles.resolve(context, DETECTOR_PATH) ?: return missing(DETECTOR_PATH)
        val primaryPath = OcrModelFiles.resolve(context, PRIMARY_PATH) ?: return missing(PRIMARY_PATH)
        val primaryDict = OcrModelFiles.resolve(context, PRIMARY_DICT_PATH) ?: return missing(PRIMARY_DICT_PATH)
        val verifierPath = OcrModelFiles.resolve(context, VERIFIER_PATH)
        val verifierDict = OcrModelFiles.resolve(context, VERIFIER_DICT_PATH)

        return runCatching {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val options = CompiledModel.Options(Accelerator.CPU).apply {
                cpuOptions = CompiledModel.CpuOptions(threads, null, null)
            }
            detector = CompiledModel.create(detectorPath, options, environment)
            primary = CompiledModel.create(primaryPath, options, environment)
            verifier = if (verifierPath != null && verifierDict != null) {
                CompiledModel.create(verifierPath, options, environment)
            } else {
                null
            }

            detectorInput = detector.createInputBuffers()[0]
            detectorOutput = detector.createOutputBuffers()[0]
            primaryInput = primary.createInputBuffers()[0]
            primaryOutput = primary.createOutputBuffers()[0]
            verifierInput = verifier?.createInputBuffers()?.get(0)
            verifierOutput = verifier?.createOutputBuffers()?.get(0)

            primaryChars = readDictionary(primaryDict)
            verifierChars = verifierDict?.let(::readDictionary)

            detectorBitmap = Bitmap.createBitmap(DETECTOR_SIZE, DETECTOR_SIZE, Bitmap.Config.ARGB_8888)
            detectorCanvas = Canvas(detectorBitmap)
            detectorPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            recognizerBitmap = Bitmap.createBitmap(RECOGNIZER_WIDTH, RECOGNIZER_HEIGHT, Bitmap.Config.ARGB_8888)
            recognizerCanvas = Canvas(recognizerBitmap)
            recognizerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            initialized = true
            logcat(LogPriority.INFO) {
                "Cyrillic OCR initialized (PP-OCRv3 + ${if (verifier != null) "PP-OCRv5 verifier" else "no verifier"})"
            }
            true
        }.onFailure { error ->
            logcat(LogPriority.ERROR, error) { "Failed to initialize Cyrillic OCR" }
            closeInternal()
        }.getOrDefault(false)
    }

    private fun missing(path: String): Boolean {
        logcat(LogPriority.INFO) { "Cyrillic OCR model is not installed: $path" }
        return false
    }

    private fun readDictionary(path: String): List<String> =
        java.io.File(path).readLines(Charsets.UTF_8).dropLastWhile(String::isEmpty)

    override suspend fun recognizeText(image: Bitmap): String {
        ensureInitialized()
        return mutex.withLock {
            require(!image.isRecycled) { "Input bitmap is recycled" }
            val boxes = detectTextBoxes(image)
            if (boxes.isEmpty()) return@withLock ""

            val recognized = boxes.mapNotNull { box ->
                val padded = pad(box.rect, image.width, image.height)
                if (padded.width() < 4 || padded.height() < 4) return@mapNotNull null
                val crop = Bitmap.createBitmap(image, padded.left, padded.top, padded.width(), padded.height())
                try {
                    val result = recognizeCrop(crop)
                    result.text.takeIf(String::isNotBlank)?.let { box to it }
                } finally {
                    crop.recycle()
                }
            }
            if (recognized.isEmpty()) return@withLock ""

            val rows = mutableListOf<MutableList<Pair<TextBox, String>>>()
            recognized.forEach { item ->
                val row = rows.firstOrNull { existing ->
                    val center = existing.map { it.first.centerY }.average().toFloat()
                    val height = existing.map { it.first.height }.average().toFloat()
                    abs(item.first.centerY - center) <= max(item.first.height, height) * 0.60f
                }
                if (row != null) row += item else rows += mutableListOf(item)
            }
            rows.sortBy { row -> row.minOf { it.first.rect.top } }
            val text = rows.joinToString("\n") { row ->
                row.sortedBy { it.first.rect.left }.joinToString(" ") { it.second.trim() }
            }
            CyrillicTranslitFixer.fixLookalikes(textPostprocessor.postprocess(text)).trim()
        }
    }

    private fun detectTextBoxes(image: Bitmap): List<TextBox> {
        detectorCanvas.drawColor(Color.WHITE)
        val scale = min(DETECTOR_SIZE.toFloat() / image.width, DETECTOR_SIZE.toFloat() / image.height)
        val scaledWidth = max(1, (image.width * scale).toInt())
        val scaledHeight = max(1, (image.height * scale).toInt())
        val offsetX = (DETECTOR_SIZE - scaledWidth) / 2
        val offsetY = (DETECTOR_SIZE - scaledHeight) / 2
        detectorCanvas.drawBitmap(
            image,
            null,
            Rect(offsetX, offsetY, offsetX + scaledWidth, offsetY + scaledHeight),
            detectorPaint,
        )
        detectorBitmap.getPixels(detectorPixels, 0, DETECTOR_SIZE, 0, 0, DETECTOR_SIZE, DETECTOR_SIZE)

        var out = 0
        detectorPixels.forEach { pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            detectorFloats[out++] = (r / 255f - 0.485f) / 0.229f
            detectorFloats[out++] = (g / 255f - 0.456f) / 0.224f
            detectorFloats[out++] = (b / 255f - 0.406f) / 0.225f
        }
        detectorInput.writeFloat(detectorFloats)
        detector.run(listOf(detectorInput), listOf(detectorOutput))
        val probability = detectorOutput.readFloat()
        if (probability.size < DETECTOR_SIZE * DETECTOR_SIZE) return emptyList()

        visited.fill(false)
        val boxes = mutableListOf<Rect>()
        val limit = DETECTOR_SIZE * DETECTOR_SIZE
        for (start in 0 until limit) {
            if (visited[start] || probability[start] < DETECTOR_THRESHOLD) continue
            var head = 0
            var tail = 0
            componentQueue[tail++] = start
            visited[start] = true
            var minX = DETECTOR_SIZE
            var minY = DETECTOR_SIZE
            var maxX = 0
            var maxY = 0
            var area = 0
            while (head < tail) {
                val index = componentQueue[head++]
                val x = index % DETECTOR_SIZE
                val y = index / DETECTOR_SIZE
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
                area++
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until DETECTOR_SIZE || ny !in 0 until DETECTOR_SIZE) continue
                    val next = ny * DETECTOR_SIZE + nx
                    if (!visited[next] && probability[next] >= DETECTOR_THRESHOLD) {
                        visited[next] = true
                        componentQueue[tail++] = next
                    }
                }
            }
            if (area < MIN_COMPONENT_AREA || maxX - minX < 3 || maxY - minY < 3) continue
            val expandX = max(3, ((maxX - minX) * 0.16f).toInt())
            val expandY = max(2, ((maxY - minY) * 0.20f).toInt())
            val left = (((minX - expandX - offsetX) / scale).toInt()).coerceIn(0, image.width - 1)
            val top = (((minY - expandY - offsetY) / scale).toInt()).coerceIn(0, image.height - 1)
            val right = (ceil((maxX + expandX - offsetX) / scale).toInt()).coerceIn(left + 1, image.width)
            val bottom = (ceil((maxY + expandY - offsetY) / scale).toInt()).coerceIn(top + 1, image.height)
            if (right - left >= 6 && bottom - top >= 6) boxes += Rect(left, top, right, bottom)
        }
        return mergeBoxes(boxes).take(MAX_TEXT_BOXES).map(::TextBox)
    }

    private fun mergeBoxes(source: List<Rect>): List<Rect> {
        val boxes = source.sortedWith(compareBy({ it.top }, { it.left })).map(::Rect).toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in boxes.indices) {
                for (j in i + 1 until boxes.size) {
                    val a = boxes[i]
                    val b = boxes[j]
                    val overlapY = max(0, min(a.bottom, b.bottom) - max(a.top, b.top))
                    val minHeight = min(a.height(), b.height()).coerceAtLeast(1)
                    val gapX = max(0, max(a.left, b.left) - min(a.right, b.right))
                    if (overlapY >= minHeight * 0.55f && gapX <= max(a.height(), b.height()) * 0.55f) {
                        a.union(b)
                        boxes.removeAt(j)
                        changed = true
                        break@outer
                    }
                }
            }
        }
        return boxes.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun pad(rect: Rect, width: Int, height: Int): Rect {
        val px = max(2, (rect.width() * 0.04f).toInt())
        val py = max(2, (rect.height() * 0.12f).toInt())
        return Rect(
            (rect.left - px).coerceAtLeast(0),
            (rect.top - py).coerceAtLeast(0),
            (rect.right + px).coerceAtMost(width),
            (rect.bottom + py).coerceAtMost(height),
        )
    }

    private fun recognizeCrop(crop: Bitmap): Recognition {
        var best = runRecognizer(crop, primary, primaryInput, primaryOutput, primaryChars)
        if (best.confidence < PRIMARY_CONFIDENCE) {
            val contrast = createHighContrast(crop)
            try {
                val alternate = runRecognizer(contrast, primary, primaryInput, primaryOutput, primaryChars)
                if (alternate.confidence > best.confidence) best = alternate
            } finally {
                contrast.recycle()
            }
        }
        val secondModel = verifier
        val secondInput = verifierInput
        val secondOutput = verifierOutput
        val secondChars = verifierChars
        if (
            best.confidence < VERIFIER_THRESHOLD && secondModel != null &&
            secondInput != null && secondOutput != null && secondChars != null
        ) {
            val second = runRecognizer(crop, secondModel, secondInput, secondOutput, secondChars)
            // The verifier may disagree because its much larger multilingual
            // alphabet is less calibrated for Russian. Replace only on a clear
            // confidence win; otherwise preserve the primary visual result.
            if (second.text.isNotBlank() && second.confidence > best.confidence + 0.08f) best = second
        }
        return best
    }

    private fun createHighContrast(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val matrix = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        1.5f, 0f, 0f, 0f, -45f,
                        0f, 1.5f, 0f, 0f, -45f,
                        0f, 0f, 1.5f, 0f, -45f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        Canvas(result).drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        })
        return result
    }

    private fun runRecognizer(
        crop: Bitmap,
        model: CompiledModel,
        input: TensorBuffer,
        output: TensorBuffer,
        chars: List<String>,
    ): Recognition {
        recognizerCanvas.drawColor(Color.rgb(128, 128, 128))
        val scale = RECOGNIZER_HEIGHT.toFloat() / crop.height.coerceAtLeast(1)
        val targetWidth = (crop.width * scale).toInt().coerceIn(1, RECOGNIZER_WIDTH)
        recognizerCanvas.drawBitmap(
            crop,
            null,
            Rect(0, 0, targetWidth, RECOGNIZER_HEIGHT),
            recognizerPaint,
        )
        recognizerBitmap.getPixels(
            recognizerPixels,
            0,
            RECOGNIZER_WIDTH,
            0,
            0,
            RECOGNIZER_WIDTH,
            RECOGNIZER_HEIGHT,
        )
        var out = 0
        recognizerPixels.forEach { pixel ->
            recognizerFloats[out++] = (((pixel shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
            recognizerFloats[out++] = (((pixel shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
            recognizerFloats[out++] = ((pixel and 0xFF) / 255f - 0.5f) / 0.5f
        }
        input.writeFloat(recognizerFloats)
        model.run(listOf(input), listOf(output))
        return decodeCtc(output.readFloat(), chars)
    }

    private fun decodeCtc(values: FloatArray, chars: List<String>): Recognition {
        val classes = chars.size + 1
        if (classes <= 1 || values.size < classes) return Recognition("", 0f)
        val steps = values.size / classes
        val text = StringBuilder(steps)
        var previous = -1
        var confidenceSum = 0f
        var confidenceCount = 0
        for (step in 0 until steps) {
            val base = step * classes
            var bestIndex = 0
            var bestScore = values[base]
            for (index in 1 until classes) {
                val char = chars.getOrNull(index - 1).orEmpty()
                if (!allowed(char)) continue
                val score = values[base + index]
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = index
                }
            }
            if (bestIndex != 0 && bestIndex != previous) {
                chars.getOrNull(bestIndex - 1)?.let(text::append)
                confidenceSum += bestScore
                confidenceCount++
            }
            previous = bestIndex
        }
        return Recognition(
            text = text.toString().trim(),
            confidence = if (confidenceCount == 0) 0f else confidenceSum / confidenceCount,
        )
    }

    private fun allowed(value: String): Boolean {
        if (value.length != 1) return false
        val char = value[0]
        return char in '\u0400'..'\u052F' || char.isDigit() || char.isWhitespace() || char in ALLOWED_PUNCTUATION
    }

    override fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        runCatching { if (::detectorInput.isInitialized) detectorInput.close() }
        runCatching { if (::detectorOutput.isInitialized) detectorOutput.close() }
        runCatching { if (::primaryInput.isInitialized) primaryInput.close() }
        runCatching { if (::primaryOutput.isInitialized) primaryOutput.close() }
        runCatching { verifierInput?.close() }
        runCatching { verifierOutput?.close() }
        verifierInput = null
        verifierOutput = null
        runCatching { if (::detector.isInitialized) detector.close() }
        runCatching { if (::primary.isInitialized) primary.close() }
        runCatching { verifier?.close() }
        verifier = null
        if (::detectorBitmap.isInitialized && !detectorBitmap.isRecycled) detectorBitmap.recycle()
        if (::recognizerBitmap.isInitialized && !recognizerBitmap.isRecycled) recognizerBitmap.recycle()
        initialized = false
    }

    companion object {
        const val PACK = "cyrillic_ocr"
        const val DETECTOR_PATH = "cyrillic_ocr/detector.tflite"
        const val PRIMARY_PATH = "cyrillic_ocr/recognizer_v3.tflite"
        const val VERIFIER_PATH = "cyrillic_ocr/recognizer_v5.tflite"
        const val PRIMARY_DICT_PATH = "cyrillic_ocr/dict_v3.txt"
        const val VERIFIER_DICT_PATH = "cyrillic_ocr/dict_v5.txt"

        private const val DETECTOR_SIZE = 736
        private const val RECOGNIZER_WIDTH = 320
        private const val RECOGNIZER_HEIGHT = 48
        private const val DETECTOR_THRESHOLD = 0.28f
        private const val MIN_COMPONENT_AREA = 28
        private const val MAX_TEXT_BOXES = 96
        private const val PRIMARY_CONFIDENCE = 0.90f
        private const val VERIFIER_THRESHOLD = 0.82f
        private const val ALLOWED_PUNCTUATION = " .,!?;:-()[]{}\"'«»„“”%№+/=…—–"
    }
}
