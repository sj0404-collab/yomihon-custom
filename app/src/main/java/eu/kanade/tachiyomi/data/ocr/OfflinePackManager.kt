package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.core.archive.ArchiveReader
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Офлайн-паки моделей из APK: файлы tar.xz в assets/ocr_packs.
 *
 * Реализация идеи пользователя:
 * • В ПОКОЕ модели живут ТОЛЬКО как tar.xz внутри APK (Tesseract eng+rus
 *   8.0МБ → 2.9МБ, YOLO-детектор баллонов Seeneva 11.9МБ → 10.6МБ) — ни
 *   байта распакованного на диске.
 * • «Читать напрямую без распаковки»: tar.xz читается ПОТОКОМ через
 *   libarchive (нативный декодер, mmap + инкрементальная xz-декомпрессия —
 *   тот же ArchiveReader, которым приложение открывает CBZ/CBR); архив
 *   никогда не разворачивается на диск как архив.
 * • «Включать только когда нужно»: при включении движка файлы модели
 *   выпотрашиваются из потока в приватный кэш — Tesseract (C API, требует
 *   путь к tessdata) и LiteRT (mmap файла) не умеют работать из потока,
 *   это ограничение их нативных API. При выключении движка кэш стирается —
 *   место снова занимает только tar.xz внутри APK.
 */
object OfflinePackManager {

    private const val ASSETS_DIR = "ocr_packs"
    private const val CACHE_DIR = "offline_packs"

    const val PACK_TESSERACT = "tess_eng_rus.tar.xz"
    const val PACK_YOLO = "yolo_seeneva.tar.xz"

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun packDir(context: Context, pack: String): File =
        File(File(context.cacheDir, CACHE_DIR), pack.removeSuffix(".tar.xz"))

    /** Активен ли пак (файлы извлечены и готовы к использованию). */
    fun isActive(context: Context, pack: String): Boolean {
        val dir = packDir(context, pack)
        return dir.isDirectory && dir.listFiles()?.any { it.length() > 0 } == true
    }

    /** Папка активного пака (null если не активен). */
    fun dirOf(context: Context, pack: String): File? =
        packDir(context, pack).takeIf { isActive(context, pack) }

    /**
     * Включает пак: потоковое чтение tar.xz из APK, извлечение только в
     * момент активации. Возвращает папку с файлами или null при ошибке.
     */
    suspend fun activate(context: Context, pack: String): File? = mutex.withLock {
        val target = packDir(context, pack)
        if (isActive(context, pack)) return@withLock target

        runCatching {
            target.mkdirs()
            // ArchiveReader работает от файлового дескриптора (mmap).
            // Asset внутри APK может быть сжат самим APK и не иметь прямого
            // fd — копируем tar.xz во временный файл единожды (маленький,
            // это сжатый архив), затем стримим из него libarchive'ом.
            val tmp = File(context.cacheDir, "$CACHE_DIR-tmp-$pack")
            context.assets.open("$ASSETS_DIR/$pack").use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            try {
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    ArchiveReader(pfd).use { reader ->
                        reader.useEntriesAndStreams { entry, stream ->
                            if (!entry.isFile) return@useEntriesAndStreams
                            val out = File(target, File(entry.name).name)
                            out.outputStream().use { stream.copyTo(it) }
                            logcat(LogPriority.INFO) {
                                "OfflinePack: extracted ${out.name} (${out.length() / 1024} KB)"
                            }
                        }
                    }
                }
            } finally {
                tmp.delete()
            }
            target
        }.onFailure {
            logcat(LogPriority.ERROR, it) { "OfflinePack: activate failed for $pack" }
            target.deleteRecursively()
        }.getOrNull()
    }

    /** Выключает пак: стирает извлечённые файлы, остаётся только tar.xz в APK. */
    suspend fun deactivate(context: Context, pack: String) = mutex.withLock {
        packDir(context, pack).deleteRecursively()
        logcat(LogPriority.INFO) { "OfflinePack: deactivated $pack" }
    }

    fun deactivateAsync(context: Context, pack: String) {
        scope.launch { deactivate(context, pack) }
    }
}
