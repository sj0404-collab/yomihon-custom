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

    private const val ONNX_CHANNEL = "onnx_download"
    private const val ONNX_NOTIF_ID = 77002

    private fun ensureOnnxChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (nm.getNotificationChannel(ONNX_CHANNEL) == null) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(ONNX_CHANNEL, "Загрузка голосов", android.app.NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Прогресс загрузки нейроголосов"
                    }
                )
            }
        }
    }

    private fun showOnnxNotif(context: Context, title: String, text: String, progress: Int) {
        ensureOnnxChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pi = android.app.PendingIntent.getActivity(context, 0, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val notif = androidx.core.app.NotificationCompat.Builder(context, ONNX_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, progress, progress < 0)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
        nm.notify(ONNX_NOTIF_ID, notif)
    }

    private fun cancelOnnxNotif(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        nm?.cancel(ONNX_NOTIF_ID)
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1048576 -> "${"%.1f".format(bytes / 1024.0)} КБ"
        else -> "${"%.1f".format(bytes / 1048576.0)} МБ"
    }


    // ---- СКАЧИВАЕМЫЙ НАТИВНЫЙ РАНТАЙМ (вынесен из APK ради веса -55МБ) ----
    // Java-API вкомпилирован (238КБ), а .so-библиотеки качаются один раз:
    // AAR с официального релиза -> извлекаются только .so нужного ABI
    // (~30МБ arm64) -> System.load. Прогресс и тест — как у моделей.

    private const val RUNTIME_AAR_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.6/sherpa-onnx-1.13.6.aar"
    const val RUNTIME_PACK_ID = "onnx_runtime"

    /**
     * ВНУТРЕННЯЯ память (filesDir), не external! External storage смонтирован
     * noexec — System.load оттуда падает с «рантайм не загрузился»
     * (баг со скриншота пользователя). Из filesDir загрузка разрешена.
     */
    private fun runtimeDir(context: Context): File =
        File(context.filesDir, "onnx_runtime").apply { mkdirs() }

    /** Миграция: старые .so с external стираем (оттуда не загрузить). */
    private fun cleanLegacyRuntime(context: Context) {
        runCatching {
            File(context.getExternalFilesDir(null), "onnx_runtime").deleteRecursively()
        }
    }

    fun isRuntimeInstalled(context: Context): Boolean =
        File(runtimeDir(context), "libsherpa-onnx-jni.so").length() > 1_000_000L

    @Volatile
    private var runtimeLoaded = false

    /** Загружает скачанные .so; после этого JNI-классы работоспособны. */
    @Synchronized
    private fun loadRuntime(context: Context): Boolean {
        if (runtimeLoaded) return true
        if (!isRuntimeInstalled(context)) return false
        return runCatching {
            val d = runtimeDir(context)
            // Порядок важен: зависимости первыми
            listOf("libonnxruntime.so", "libsherpa-onnx-c-api.so", "libsherpa-onnx-jni.so").forEach {
                val f = File(d, it)
                if (f.isFile) System.load(f.absolutePath)
            }
            runtimeLoaded = true
            cancelOnnxNotif(context)
            showOnnxNotif(context, "TTS-рантайм готов", "Голоса можно скачивать", 100)
            true
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "sherpa-onnx runtime load failed" }
        }.getOrDefault(false)
    }

    /**
     * Скачивает рантайм (AAR ~46МБ, из него извлекаются .so своего ABI
     * ~30МБ, AAR удаляется). Прогресс: 0..0.85 загрузка, 0.85..1 распаковка.
     */
    suspend fun downloadRuntime(context: Context): Boolean = withContext(Dispatchers.IO) {
        cleanLegacyRuntime(context)
        if (isRuntimeInstalled(context)) return@withContext true
        if (!activeDownloads.add(RUNTIME_PACK_ID)) return@withContext false
        val aar = File(context.cacheDir, "sherpa-onnx.aar")
        var conn: HttpURLConnection? = null
        try {
            conn = URL(RUNTIME_AAR_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            if (conn.responseCode !in 200..299) return@withContext false
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (46L * 1048576)
            conn.inputStream.use { input ->
                aar.outputStream().use { out ->
                    val buf = ByteArray(512 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val pct = (read * 85 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            _progress.value = _progress.value + (RUNTIME_PACK_ID to pct / 100f)
                            showOnnxNotif(context, "Загрузка TTS-рантайма", "${pct}% (~46 МБ)", pct)
                        }
                    }
                }
            }
            // Извлечение .so только своего ABI
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            _progress.value = _progress.value + (RUNTIME_PACK_ID to 0.9f)
            showOnnxNotif(context, "Распаковка TTS-рантайма", "…", 90)
            java.util.zip.ZipInputStream(aar.inputStream().buffered()).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.startsWith("jni/$abi/") && e.name.endsWith(".so")) {
                        val out = File(runtimeDir(context), File(e.name).name)
                        out.outputStream().use { zis.copyTo(it) }
                    }
                    e = zis.nextEntry
                }
            }
            _progress.value = _progress.value + (RUNTIME_PACK_ID to 1f)
            aar.delete()
            val ok = isRuntimeInstalled(context)
            if (ok) bumpInstalled()
            ok
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "ONNX runtime download failed" }
            aar.delete()
            false
        } finally {
            conn?.disconnect()
            activeDownloads.remove(RUNTIME_PACK_ID)
            _progress.value = _progress.value - RUNTIME_PACK_ID
        }
    }

    fun deleteRuntime(context: Context) {
        unload()
        runtimeDir(context).deleteRecursively()
        bumpInstalled()
        // runtimeLoaded остаётся true до перезапуска процесса — честно скажем
    }

    /**
     * Тест рантайма+голоса: загрузка .so и probe-синтез короткой фразы.
     * Возвращает (успех, сообщение).
     */
    suspend fun probe(context: Context, v: Voice): Pair<Boolean, String> = withContext(Dispatchers.Default) {
        if (!isRuntimeInstalled(context)) return@withContext false to "Рантайм не скачан"
        if (!loadRuntime(context)) return@withContext false to "Рантайм не загрузился (несовместимая архитектура?)"
        if (!isInstalled(context, v)) return@withContext false to "Голос не установлен"
        val started = System.currentTimeMillis()
        val wav = synthesizeToFile(context, v, "Проверка голоса")
        val took = System.currentTimeMillis() - started
        if (wav != null) {
            wav.delete()
            true to "Тест пройден за ${took / 1000.0}с"
        } else {
            false to "Синтез не удался — смотрите логи"
        }
    }

    /** Доступен ли движок: рантайм скачан И его удалось загрузить. */
    fun isAvailable(context: Context): Boolean =
        isRuntimeInstalled(context) && loadRuntime(context)

    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress

    /**
     * Счётчик изменений установленных пакетов (рантайм, голоса). UI собирает
     * его вместе с [progress]: после скачивания/удаления флаги вроде
     * isInstalled мгновенно пересчитываются в Compose (раньше они были
     * заморожены в remember{} и кнопки не появлялись после загрузки).
     */
    private val _installedVersion = MutableStateFlow(0)
    val installedVersion: StateFlow<Int> = _installedVersion

    private fun bumpInstalled() {
        _installedVersion.value += 1
    }

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
                            _progress.value = _progress.value + (v.id to (pct / 100f * 0.85f).coerceIn(0f, 0.85f))
                        }
                    }
                }
            }
            // Распаковка tar.bz2. Раньше индикатор «застревал на 95%»:
            // bzip2-декомпрессия 64МБ на телефоне идёт десятки секунд без
            // обновления. Теперь прогресс 0.86..1.0 тикает ПО БАЙТАМ
            // распакованных файлов (у Piper-голосов ~66МБ внутри).
            val approxUnpacked = 70L * 1048576
            var unpacked = 0L
            BZip2CompressorInputStream(tarball.inputStream().buffered()).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (entry.isFile) {
                            val out = File(dir(context), entry.name)
                            if (out.canonicalPath.startsWith(dir(context).canonicalPath)) {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { os ->
                                    val buf = ByteArray(512 * 1024)
                                    while (true) {
                                        val n = tar.read(buf)
                                        if (n < 0) break
                                        os.write(buf, 0, n)
                                        unpacked += n
                                        val frac = 0.86f + 0.14f * (unpacked.toFloat() / approxUnpacked).coerceAtMost(1f)
                                        _progress.value = _progress.value + (v.id to frac)
                                    }
                                }
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
            tarball.delete()
            val ok = isInstalled(context, v)
            if (ok) bumpInstalled()
            ok
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
        bumpInstalled()
    }

    // ---- Инференс через рефлексию (AAR может отсутствовать в сборке) ----
    private var engine: Any? = null
    private var engineVoiceId: String? = null

    @Synchronized
    private fun ensureEngine(context: Context, v: Voice): Any? {
        if (!loadRuntime(context)) return null
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
     * ЖИВАЯ ПРОСОДИЯ (по запросу пользователя: «интонации, тона, тембры,
     * эмоции»): у VITS/Piper управляемые параметры — скорость и вариативность.
     * Раньше все фразы синтезировались монотонно с одной скоростью. Теперь:
     *  • вопрос «?»  — медленнее на 8% (вопросительная интонация читается
     *    отчётливее) ;
     *  • восклицание «!» — быстрее на 10% (энергичнее);
     *  • многоточие «…» — медленнее на 15% (задумчиво);
     *  • КАПС (крик) — быстрее на 15%;
     *  • обычные фразы — лёгкая случайная вариация ±3%, чтобы серия реплик
     *    не звучала конвейером.
     * Плюс паузы между предложениями добавляет TtsSpeaker (тишина в WAV не
     * нужна — плеер сам делает паузу между файлами).
     */
    fun prosodySpeed(sentence: String, base: Float): Float {
        val t = sentence.trim()
        val letters = t.count { it.isLetter() }
        val upper = t.count { it.isUpperCase() }
        val isShout = letters >= 4 && upper.toFloat() / letters > 0.7f
        val mod = when {
            isShout -> 1.15f
            t.endsWith("?") || t.endsWith("?!") -> 0.92f
            t.endsWith("!") -> 1.10f
            t.endsWith("…") || t.endsWith("...") -> 0.85f
            else -> 1f + (kotlin.random.Random.nextFloat() - 0.5f) * 0.06f
        }
        return (base * mod).coerceIn(0.5f, 2f)
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
