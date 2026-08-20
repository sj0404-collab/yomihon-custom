package eu.kanade.tachiyomi.data.ai

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Workspace встроенного AI-ассистента: реальная папка на диске, куда
 * ассистент складывает результаты (файлы, картинки, архивы), а пользователь
 * может забрать их в любой момент — файловым менеджером или из UI чата.
 *
 * Расположение: /sdcard/Yomikai/AI (создаётся автоматически). Если внешнее
 * хранилище недоступно — приватная папка приложения (files/ai_workspace).
 */
object AiWorkspace {

    private const val DIR_NAME = "AI"

    fun root(context: Context): File {
        val external = File(Environment.getExternalStorageDirectory(), "Yomikai/$DIR_NAME")
        val dir = if (external.parentFile?.exists() == true || external.mkdirs() || external.exists()) {
            external
        } else {
            File(context.filesDir, "ai_workspace")
        }
        dir.mkdirs()
        File(dir, "images").mkdirs()
        File(dir, "inbox").mkdirs()
        return dir
    }

    /** Все файлы workspace (рекурсивно), отсортированы: папки → новые файлы. */
    fun listAll(context: Context): List<File> {
        val r = root(context)
        return r.walkTopDown()
            .filter { it != r }
            .sortedWith(compareBy({ !it.isDirectory }, { -it.lastModified() }))
            .toList()
    }

    fun relPath(context: Context, f: File): String =
        f.absolutePath.removePrefix(root(context).absolutePath).trimStart('/')

    /** Безопасное разрешение относительного пути (без выхода из workspace). */
    fun resolve(context: Context, rel: String): File? {
        val r = root(context)
        val f = File(r, rel.trim().trimStart('/'))
        return if (f.canonicalPath.startsWith(r.canonicalPath)) f else null
    }

    /** Сохранить текстовый файл; подпапки в имени создаются автоматически. */
    fun writeText(context: Context, name: String, content: String): File? {
        val f = resolve(context, sanitize(name)) ?: return null
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    fun newImageFile(context: Context, hint: String): File {
        val safe = sanitize(hint).take(40).ifBlank { "image" }
        return File(File(root(context), "images"), "${safe}_${System.currentTimeMillis() % 100000}.jpg")
    }

    /** Копия вложения пользователя в workspace/inbox. */
    fun importAttachment(context: Context, displayName: String, bytes: ByteArray): File {
        val f = File(File(root(context), "inbox"), sanitize(displayName).ifBlank { "file_${System.currentTimeMillis()}" })
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return f
    }

    /** Упаковать весь workspace (кроме прежних архивов) в zip. Возвращает файл архива. */
    fun zipAll(context: Context): File {
        val r = root(context)
        val out = File(r, "workspace_${System.currentTimeMillis() / 1000}.zip")
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            r.walkTopDown()
                .filter { it.isFile && it != out && !it.name.endsWith(".zip") }
                .forEach { f ->
                    zos.putNextEntry(ZipEntry(relPath(context, f)))
                    FileInputStream(f).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
        }
        return out
    }

    fun delete(context: Context, rel: String): Boolean {
        val f = resolve(context, rel) ?: return false
        return f.deleteRecursively()
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\:*?\"<>|]"), "_").replace("..", "_").trim()
}
