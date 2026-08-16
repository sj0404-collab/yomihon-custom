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
    )

    /** Asset-style paths per pack, used to check installation and delete files. */
    val PACK_ASSET_PATHS: Map<String, List<String>> = mapOf(
        "manga_ocr" to listOf("ocr/encoder.tflite", "ocr/decoder.tflite", "ocr/embeddings.bin"),
        "manga_ocr_fast" to listOf("ocr_fast/encoder.tflite", "ocr_fast/decoder.tflite"),
        "panel_detector" to listOf("panel_detector/model.tflite"),
    )

    private fun hf(repo: String, path: String): String {
        return "https://huggingface.co/$repo/resolve/main/$path"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadMutex = Mutex()
    private val activePacks = mutableSetOf<String>()

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

            val ok = try {
                val baseDir = context.getExternalFilesDir(null)
                    ?.let { File(it, OcrModelFiles.MODELS_DIR) }
                    ?.apply { mkdirs() }

                if (baseDir == null) {
                    false
                } else {
                    files.all { (url, name) -> downloadFile(url, File(baseDir, name)) }
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "OCR model pack download failed: $pack" }
                false
            } finally {
                downloadMutex.withLock { activePacks.remove(pack) }
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

    private fun downloadFile(url: String, destination: File): Boolean {
        if (destination.isFile && destination.length() > 0) {
            return true
        }

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
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 256 * 1024)
                }
            }
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
