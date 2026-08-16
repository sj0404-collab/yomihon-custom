package eu.kanade.tachiyomi.data.ocr

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import eu.kanade.tachiyomi.util.system.toast
import java.io.File

object OcrModelDownloader {

    fun downloadModel(context: Context, modelName: String, url: String) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val ocrDir = File(Environment.getExternalStorageDirectory(), "Yomihon/OCR")
            if (!ocrDir.exists()) {
                ocrDir.mkdirs()
            }

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("Загрузка OCR модели: $modelName")
                setDescription("Скачивание файла модели в Yomihon/OCR/")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Yomihon/OCR/$modelName")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            downloadManager.enqueue(request)
            context.toast("Загрузка $modelName запущена в шторке уведомлений")
        } catch (e: Exception) {
            context.toast("Ошибка запуска скачивания: ${e.message}")
        }
    }
}
