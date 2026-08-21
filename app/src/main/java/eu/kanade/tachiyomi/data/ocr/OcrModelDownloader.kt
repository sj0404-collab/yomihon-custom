package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.data.ocr.OcrModelFiles
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads optional local OCR/vision models into the app's external files dir
 * (Android/data/<package>/files/ocr_models/). Models are NOT bundled in the APK:
 * out of the box the app uses online engines only, and local models are an
 * opt-in download. Files can also be installed manually into Yomihon/OCR/ on
 * shared storage (see OcrModelFiles for the full search order).
 */
object OcrModelDownloader {

    /** Model packs: pack name -> list of (url, flat file name). */
    val PACKS: Map<String, List<Pair<String, String>>> = mapOf(
        "manga_ocr" to listOf(
            hf("bluolightning/manga-ocr-tflite", "mocr_2025_encoder_fp32.tflite") to "encoder.tflite",
            hf("bluolightning/manga-ocr-tflite", "mocr_2025_decoder_float32.tflite") to "decoder.tflite",
            hf("bluolightning/manga-ocr-tflite", "mocr_2025_embeddings_float32.bin") to "embeddings.bin",
        ),
        "manga_ocr_fast" to listOf(
            hf("bluolightning/manga-ocr-mobile", "v1_fp16/encoder.tflite") to "encoder_fast.tflite",
            hf("bluolightning/manga-ocr-mobile", "v1_fp16/decoder.tflite") to "decoder_fast.tflite",
        ),
        "panel_detector" to listOf(
            hf("leoxs22/manga-panel-detector-yolo26n", "manga_panel_detector_int8.tflite") to "panel_detector.tflite",
        ),
        // ---- Языковые паки Tesseract (tessdata_fast, официальный репозиторий).
        // Скачиваются один раз, удаляются одной кнопкой; после установки язык
        // появляется в офлайн-движке (TesseractOcrEngine подключает их из
        // ocr_models/tessdata). eng+rus уже в APK — их качать не нужно.
        // ---- ТОЧНЫЙ русский (tessdata_best): заметно точнее встроенного
        // fast-пака на мелком тексте манги; медленнее ~1.5-2x. Кладётся в
        // ocr_models/tessdata/rus.traineddata и ПЕРЕКРЫВАЕТ встроенный.
        "tess_rus_best" to listOf(
            "https://github.com/tesseract-ocr/tessdata_best/raw/main/rus.traineddata" to "tessdata/rus.traineddata",
        ),
        // Скрипт «вся кириллица» — рус/укр/срб/болг одним паком
        "tess_cyrillic" to listOf(
            tess("script/Cyrillic.traineddata") to "tessdata/Cyrillic.traineddata",
        ),
        "tess_ukr_best" to listOf(
            "https://github.com/tesseract-ocr/tessdata_best/raw/main/ukr.traineddata" to "tessdata/ukr.traineddata",
        ),
        "tess_jpn" to listOf(
            tess("jpn.traineddata") to "tessdata/jpn.traineddata",
            tess("jpn_vert.traineddata") to "tessdata/jpn_vert.traineddata",
        ),
        "tess_kor" to listOf(
            tess("kor.traineddata") to "tessdata/kor.traineddata",
        ),
        "tess_chi" to listOf(
            tess("chi_sim.traineddata") to "tessdata/chi_sim.traineddata",
        ),
        "tess_ukr" to listOf(
            tess("ukr.traineddata") to "tessdata/ukr.traineddata",
        ),
        "tess_deu" to listOf(
            tess("deu.traineddata") to "tessdata/deu.traineddata",
        ),
        "tess_fra" to listOf(
            tess("fra.traineddata") to "tessdata/fra.traineddata",
        ),
        "tess_spa" to listOf(
            tess("spa.traineddata") to "tessdata/spa.traineddata",
        ),
    )

    /** Asset-style paths per pack, used to check installation and delete files. */
    val PACK_ASSET_PATHS: Map<String, List<String>> = mapOf(
        "manga_ocr" to listOf("ocr/encoder.tflite", "ocr/decoder.tflite", "ocr/embeddings.bin"),
        "manga_ocr_fast" to listOf("ocr_fast/encoder.tflite", "ocr_fast/decoder.tflite"),
        "panel_detector" to listOf("panel_detector/model.tflite"),
        // ---- ТОЧНЫЙ русский (tessdata_best): заметно точнее встроенного
        // fast-пака на мелком тексте манги; медленнее ~1.5-2x. Кладётся в
        // ocr_models/tessdata/rus.traineddata и ПЕРЕКРЫВАЕТ встроенный.
        "tess_rus_best" to listOf(
            "https://github.com/tesseract-ocr/tessdata_best/raw/main/rus.traineddata" to "tessdata/rus.traineddata",
        ),
        // Скрипт «вся кириллица» — рус/укр/срб/болг одним паком
        "tess_cyrillic" to listOf(
            tess("script/Cyrillic.traineddata") to "tessdata/Cyrillic.traineddata",
        ),
        "tess_ukr_best" to listOf(
            "https://github.com/tesseract-ocr/tessdata_best/raw/main/ukr.traineddata" to "tessdata/ukr.traineddata",
        ),
        "tess_rus_best" to listOf("tessdata/rus.traineddata"),
        "tess_cyrillic" to listOf("tessdata/Cyrillic.traineddata"),
        "tess_ukr_best" to listOf("tessdata/ukr.traineddata"),
        "tess_jpn" to listOf("tessdata/jpn.traineddata", "tessdata/jpn_vert.traineddata"),
        "tess_kor" to listOf("tessdata/kor.traineddata"),
        "tess_chi" to listOf("tessdata/chi_sim.traineddata"),
        "tess_ukr" to listOf("tessdata/ukr.traineddata"),
        "tess_deu" to listOf("tessdata/deu.traineddata"),
        "tess_fra" to listOf("tessdata/fra.traineddata"),
        "tess_spa" to listOf("tessdata/spa.traineddata"),
    )

    /** Метаданные языковых паков Tesseract: pack -> (код языка, название, ~размер). */
    val TESS_LANG_PACKS: List<Triple<String, String, String>> = listOf(
        Triple("tess_rus_best", "rus", "Русский ТОЧНЫЙ (tessdata_best) • 14 МБ"),
        Triple("tess_cyrillic", "Cyrillic", "Вся кириллица (рус/укр/болг/срб) • 27 МБ"),
        Triple("tess_ukr_best", "ukr", "Украинский точный (best) • 10 МБ"),
        Triple("tess_jpn", "jpn", "Японский (+вертикальный) • ~5 МБ"),
        Triple("tess_kor", "kor", "Корейский • ~2 МБ"),
        Triple("tess_chi", "chi_sim", "Китайский упрощённый • ~3 МБ"),
        Triple("tess_ukr", "ukr", "Украинский • ~4 МБ"),
        Triple("tess_deu", "deu", "Немецкий • ~2 МБ"),
        Triple("tess_fra", "fra", "Французский • ~2 МБ"),
        Triple("tess_spa", "spa", "Испанский • ~3 МБ"),
    )

    private fun tess(file: String): String {
        return "https://github.com/tesseract-ocr/tessdata_fast/raw/main/$file"
    }

    private fun hf(repo: String, path: String): String {
        return "https://huggingface.co/$repo/resolve/main/$path"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadMutex = Mutex()
    private val activePacks = mutableSetOf<String>()

    /**
     * ЖИВОЙ ИНДИКАТОР загрузки (по требованию пользователя): pack -> прогресс.
     *  0f..1f  — идёт загрузка (доля скачанных байт всех файлов пака);
     *  null    — пак не качается (установлен или не тронут).
     * UI подписывается на flow и рисует LinearProgressIndicator с процентами.
     */
    private val _progress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: kotlinx.coroutines.flow.StateFlow<Map<String, Float>> = _progress

    private fun setProgress(pack: String, value: Float?) {
        _progress.value = if (value == null) {
            _progress.value - pack
        } else {
            _progress.value + (pack to value)
        }
    }

    /** Суммарный размер установленных файлов пака в байтах (0 если нет). */
    fun installedSize(context: Context, pack: String): Long {
        val paths = PACK_ASSET_PATHS[pack] ?: return 0L
        return paths.sumOf { p ->
            OcrModelFiles.resolve(context, p)?.let { File(it).length() } ?: 0L
        }
    }

    fun isPackInstalled(context: Context, pack: String): Boolean {
        val paths = PACK_ASSET_PATHS[pack] ?: return false
        return OcrModelFiles.allInstalled(context, paths)
    }

    fun deletePack(context: Context, pack: String) {
        val paths = PACK_ASSET_PATHS[pack] ?: return
        OcrModelFiles.delete(context, paths)
        context.toast("Локальные файлы модели удалены")
    }

    /**
     * Downloads all files of a pack into ocr_models/ with .part staging so the
     * engines never pick up partially written files. Reports progress via toasts
     * and [onFinished] with success flag.
     */
    fun downloadPack(
        context: Context,
        pack: String,
        onFinished: (Boolean) -> Unit = {},
    ) {
        val files = PACKS[pack]
        if (files == null) {
            context.toast("Неизвестный пакет моделей: $pack")
            onFinished(false)
            return
        }

        scope.launch {
            val shouldStart = downloadMutex.withLock { activePacks.add(pack) }
            if (!shouldStart) {
                withContext(Dispatchers.Main) { context.toast("Загрузка уже идёт…") }
                return@launch
            }

            withContext(Dispatchers.Main) {
                context.toast("Загрузка моделей началась (${files.size} файл(ов))…")
            }

            setProgress(pack, 0f)
            val ok = try {
                val baseDir = context.getExternalFilesDir(null)
                    ?.let { File(it, OcrModelFiles.MODELS_DIR) }
                    ?.apply { mkdirs() }

                if (baseDir == null) {
                    false
                } else {
                    // Прогресс по файлам: каждый файл — своя доля пака,
                    // внутри файла — по скачанным байтам (Content-Length).
                    var done = 0
                    files.all { (url, name) ->
                        val fileIndex = done
                        val r = downloadFile(url, File(baseDir, name)) { frac ->
                            setProgress(pack, (fileIndex + frac) / files.size)
                        }
                        done++
                        r
                    }
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "OCR model pack download failed: $pack" }
                false
            } finally {
                downloadMutex.withLock { activePacks.remove(pack) }
                setProgress(pack, null)
            }

            withContext(Dispatchers.Main) {
                if (ok) {
                    context.toast("Модели установлены: локальный OCR готов к работе")
                } else {
                    context.toast("Не удалось скачать модели. Проверьте интернет и повторите")
                }
                onFinished(ok)
            }
        }
    }

    private fun downloadFile(
        url: String,
        destination: File,
        onProgress: (Float) -> Unit = {},
    ): Boolean {
        if (destination.isFile && destination.length() > 0) {
            onProgress(1f)
            return true
        }

        destination.parentFile?.mkdirs() // паки с подпапками (tessdata/…)
        val part = File(destination.parentFile, destination.name + ".part")
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            if (connection.responseCode !in 200..299) {
                logcat(LogPriority.WARN) { "Model download HTTP ${connection.responseCode} for $url" }
                return false
            }
            val total = connection.contentLengthLong.takeIf { it > 0 }
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(256 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total != null) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            onProgress(1f)
            part.renameTo(destination)
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Model file download failed: $url" }
            part.delete()
            false
        } finally {
            connection?.disconnect()
        }
    }
}
