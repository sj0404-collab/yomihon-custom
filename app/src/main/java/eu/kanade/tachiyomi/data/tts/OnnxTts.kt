package eu.kanade.tachiyomi.data.tts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import logcat.LogPriority
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * ONNX-ГОЛОСА (sherpa-onnx + VITS/Piper): нейросетевой офлайн-TTS,
 * заметно живее системных голосов. Модели скачиваются один раз
 * (файлы .tar.bz2 с официального релиза sherpa-onnx, ссылки проверены
 * живьём — HTTP 200, ~64МБ каждый), распаковываются в files/onnx_tts/.
 *
 * Движок вызывается ЧЕРЕЗ РЕФЛЕКСИЮ: AAR sherpa-onnx подтягивается CI-шагом
 * (в Maven Central его нет); если библиотека не попала в сборку — isAvailable
 * вернёт false и UI честно скажет, что движок недоступен, вместо краша.
 */
object OnnxTts {

    data class Voice(
        val id: String,
        val name: String,
        val gender: String, // female | male
        val url: String,
        val sizeMb: Int,
        /** Подпапка внутри архива. */
        val dirName: String,
    )

    /** Проверенные русские Piper-голоса (medium, 22кГц). */
    val CATALOG = listOf(
        Voice(
            id = "irina",
            name = "Ирина (женский, мягкий)",
            gender = "female",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-irina-medium.tar.bz2",
            sizeMb = 64,
            dirName = "vits-piper-ru_RU-irina-medium",
        ),
        Voice(
            id = "dmitri",
            name = "Дмитрий (мужской)",
            gender = "male",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-dmitri-medium.tar.bz2",
            sizeMb = 64,
            dirName = "vits-piper-ru_RU-dmitri-medium",
        ),
        Voice(
            id = "ruslan",
            name = "Руслан (мужской, низкий)",
            gender = "male",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-ruslan-medium.tar.bz2",
            sizeMb = 64,
            dirName = "vits-piper-ru_RU-ruslan-medium",
        ),
    )

    private fun dir(context: Context): File =
        File(context.getExternalFilesDir(null), "onnx_tts").apply { mkdirs() }

    private fun voiceDir(context: Context, v: Voice): File = File(dir(context), v.dirName)

    fun isInstalled(context: Context, v: Voice): Boolean {
        val d = voiceDir(context, v)
        return d.isDirectory && d.walkTopDown().any { it.extension == "onnx" }
    }

    /** Есть ли нативная библиотека sherpa-onnx в сборке. */
    val isAvailable: Boolean by lazy {
        runCatching { Class.forName("com.k2fsa.sherpa.onnx.OfflineTts") }.isSuccess
    }

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress

    private val activeDownloads = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    suspend fun download(context: Context, v: Voice): Boolean = withContext(Dispatchers.IO) {
        if (isInstalled(context, v)) return@withContext true
        if (!activeDownloads.add(v.id)) return@withContext false
        val tarball = File(dir(context), "${v.id}.tar.bz2")
        var conn: HttpURLConnection? = null
        try {
            conn = URL(v.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return@withContext false
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (v.sizeMb * 1048576L)
            conn.inputStream.use { input ->
                tarball.outputStream().use { out ->
                    val buf = ByteArray(512 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val pct = (read * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            _progress.value = _progress.value + (v.id to (pct / 100f).coerceIn(0f, 0.9f))
                        }
                    }
                }
            }
            // Распаковка tar.bz2 (commons-compress)
            _progress.value = _progress.value + (v.id to 0.95f)
            BZip2CompressorInputStream(tarball.inputStream().buffered()).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (entry.isFile) {
                            val out = File(dir(context), entry.name)
                            if (out.canonicalPath.startsWith(dir(context).canonicalPath)) {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { tar.copyTo(it) }
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
            tarball.delete()
            isInstalled(context, v)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "ONNX voice download failed: ${v.id}" }
            tarball.delete()
            false
        } finally {
            conn?.disconnect()
            activeDownloads.remove(v.id)
            _progress.value = _progress.value - v.id
        }
    }

    fun delete(context: Context, v: Voice) {
        unload()
        voiceDir(context, v).deleteRecursively()
    }

    // ---- Инференс через рефлексию (AAR может отсутствовать в сборке) ----
    private var engine: Any? = null
    private var engineVoiceId: String? = null

    @Synchronized
    private fun ensureEngine(context: Context, v: Voice): Any? {
        if (!isAvailable) return null
        if (engineVoiceId == v.id) return engine
        unload()
        return runCatching {
            val d = voiceDir(context, v)
            val model = d.walkTopDown().firstOrNull { it.extension == "onnx" } ?: return null
            val tokens = d.walkTopDown().firstOrNull { it.name == "tokens.txt" } ?: return null
            val dataDir = d.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }

            val cls = { n: String -> Class.forName("com.k2fsa.sherpa.onnx.$n") }
            val vitsCfg = cls("OfflineTtsVitsModelConfig").getDeclaredConstructor(
                String::class.java, String::class.java, String::class.java, String::class.java,
                Float::class.java, Float::class.java, Float::class.java,
            ).let { ctor ->
                // (model, lexicon, tokens, dataDir, noiseScale, noiseScaleW, lengthScale)
                ctor.newInstance(
                    model.absolutePath, "", tokens.absolutePath,
                    dataDir?.absolutePath ?: "", 0.667f, 0.8f, 1.0f,
                )
            }
            val modelCfgCls = cls("OfflineTtsModelConfig")
            val modelCfg = modelCfgCls.getDeclaredConstructor().newInstance()
            modelCfgCls.getMethod("setVits", cls("OfflineTtsVitsModelConfig")).invoke(modelCfg, vitsCfg)
            modelCfgCls.getMethod("setNumThreads", Int::class.java).invoke(modelCfg, 2)

            val cfgCls = cls("OfflineTtsConfig")
            val cfg = cfgCls.getDeclaredConstructor().newInstance()
            cfgCls.getMethod("setModel", modelCfgCls).invoke(cfg, modelCfg)

            val ttsCls = cls("OfflineTts")
            val tts = ttsCls.getDeclaredConstructor(
                Class.forName("android.content.res.AssetManager"), cfgCls,
            ).newInstance(null, cfg)
            engine = tts
            engineVoiceId = v.id
            tts
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "ONNX TTS init failed: ${v.id}" }
        }.getOrNull()
    }

    /**
     * Синтез в WAV-файл. Возвращает файл или null (движок недоступен/ошибка).
     */
    suspend fun synthesizeToFile(context: Context, v: Voice, text: String, speed: Float = 1f): File? =
        withContext(Dispatchers.Default) {
            runCatching {
                val tts = ensureEngine(context, v) ?: return@withContext null
                val gen = tts.javaClass.getMethod(
                    "generate", String::class.java, Int::class.java, Float::class.java,
                ).invoke(tts, text, 0, speed) ?: return@withContext null
                val out = File(context.cacheDir, "onnx_tts_${System.nanoTime()}.wav")
                gen.javaClass.getMethod("save", String::class.java).invoke(gen, out.absolutePath)
                out.takeIf { it.isFile && it.length() > 44 }
            }.onFailure {
                logcat(LogPriority.WARN, it) { "ONNX synth failed" }
            }.getOrNull()
        }

    @Synchronized
    fun unload() {
        runCatching {
            engine?.let { it.javaClass.getMethod("release").invoke(it) }
        }
        engine = null
        engineVoiceId = null
    }
}
