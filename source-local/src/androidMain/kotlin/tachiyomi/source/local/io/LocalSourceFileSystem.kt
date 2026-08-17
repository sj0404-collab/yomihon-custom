package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import tachiyomi.domain.storage.service.StorageManager

actual class LocalSourceFileSystem(
    private val storageManager: StorageManager,
) {

    actual fun getBaseDirectory(): UniFile? {
        return storageManager.getLocalSourceDirectory()
    }

    /**
     * Сканируем и подпапку local/, и корень выбранного хранилища (как CDisplayEx):
     * пользователю не нужно перекладывать мангу в local/ — папки и одиночные
     * CBZ/CBR видны сразу. Служебные папки приложения исключаются.
     */
    actual fun getFilesInBaseDirectory(): List<UniFile> {
        val local = getBaseDirectory()?.listFiles().orEmpty().toList()
        val root = storageManager.getBaseDirectory()?.listFiles().orEmpty()
            .filterNot { it.name.orEmpty().lowercase() in RESERVED_NAMES }
        return local + root
    }

    actual fun getMangaDirectory(name: String): UniFile? {
        return findEntry(name)
            ?.takeIf { it.isDirectory }
    }

    actual fun getFilesInMangaDirectory(name: String): List<UniFile> {
        return getMangaDirectory(name)?.listFiles().orEmpty().toList()
    }

    /** Ищет запись (папку или файл) сначала в local/, затем в корне хранилища. */
    fun findEntry(name: String): UniFile? {
        return getBaseDirectory()?.findFile(name)
            ?: storageManager.getBaseDirectory()?.findFile(name)
                ?.takeIf { it.name.orEmpty().lowercase() !in RESERVED_NAMES }
    }

    private companion object {
        val RESERVED_NAMES = setOf("autobackup", "downloads", "local", ".covers", ".nomedia")
    }
}
