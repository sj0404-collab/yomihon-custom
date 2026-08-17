package eu.kanade.tachiyomi.ui.locallibrary

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Сторонняя (локальная) библиотека — отдельная от основного хранилища.
 *
 * Пользователь добавляет СКОЛЬКО УГОДНО папок из любых мест (включая
 * Android/data через SAF). Каждая папка сканируется: папка = манга
 * (CBZ/CBR/ZIP-главы или папки-главы), одиночный архив = манга с одной
 * главой, обложка из первого изображения архива. Загрузки из сети живут
 * в основном хранилище и здесь не показываются — никаких дублей.
 */
data object LocalLibraryTab : Tab {

    private data class ScanStats(val mangaDirs: Int, val archives: Int)

    override val options: TabOptions
        @Composable
        get() {
            return TabOptions(
                index = 6u,
                title = "Локальная",
                icon = rememberVectorPainter(Icons.Outlined.Folder),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val storageManager = remember { Injekt.get<StorageManager>() }
        val storagePreferences = remember { Injekt.get<StoragePreferences>() }

        var scanning by remember { mutableStateOf(true) }
        var stats by remember { mutableStateOf<ScanStats?>(null) }
        var roots by remember { mutableStateOf(storagePreferences.externalLibraryRoots.get()) }
        var showRoots by remember { mutableStateOf(false) }

        val addFolderLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri ->
            if (uri != null) {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
                storagePreferences.externalLibraryRoots.set(
                    storagePreferences.externalLibraryRoots.get() + uri.toString(),
                )
                roots = storagePreferences.externalLibraryRoots.get()
                context.toast("Папка добавлена в библиотеку")
            }
        }

        // Быстрый скан (только имена) + автоперескан при изменении набора папок
        LaunchedEffect(Unit) {
            storageManager.changes
                .onStart { emit(Unit) }
                .collectLatest {
                    scanning = true
                    roots = storagePreferences.externalLibraryRoots.get()
                    stats = withIOContext { scanStorage(storageManager) }
                    scanning = false
                }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                    ) {
                        if (scanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = "  Сканирование папок…",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            val s = stats
                            Text(
                                text = when {
                                    roots.isEmpty() ->
                                        "📂 Добавьте папки с мангой (можно несколько, в т.ч. Android/data)"
                                    s == null || (s.mangaDirs == 0 && s.archives == 0) ->
                                        "📂 В добавленных папках (${roots.size}) манга не найдена"
                                    else ->
                                        "📚 Папок-манг: ${s.mangaDirs} • Архивов: ${s.archives} • Источников: ${roots.size}"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { showRoots = !showRoots },
                            )
                        }
                        IconButton(onClick = { addFolderLauncher.launch(null) }) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = "Добавить папку",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (showRoots && roots.isNotEmpty()) {
                        roots.forEach { uriString ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 4.dp),
                            ) {
                                Text(
                                    text = "📁 " + prettyUri(uriString),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = {
                                        storagePreferences.externalLibraryRoots.set(
                                            storagePreferences.externalLibraryRoots.get() - uriString,
                                        )
                                        roots = storagePreferences.externalLibraryRoots.get()
                                        context.toast("Папка убрана из библиотеки")
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "Убрать папку",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Navigator(
                screen = BrowseSourceScreen(
                    sourceId = LocalSource.ID,
                    listingQuery = GetRemoteManga.QUERY_POPULAR,
                ),
            )
        }
    }

    private fun prettyUri(uriString: String): String {
        return runCatching {
            java.net.URLDecoder.decode(uriString.substringAfterLast("/"), "UTF-8")
                .substringAfterLast(':')
                .ifBlank { uriString }
        }.getOrDefault(uriString)
    }

    private fun scanStorage(storageManager: StorageManager): ScanStats {
        val archiveExts = setOf("cbz", "zip", "cbr", "rar", "epub")

        val local = storageManager.getLocalSourceDirectory()?.listFiles().orEmpty()
            .filterNot { it.name.orEmpty().startsWith('.') }
        val external = storageManager.getExternalLibraryRoots()
            .flatMap { it.listFiles().orEmpty().toList() }
            .filterNot { it.name.orEmpty().startsWith('.') }

        val all = (local + external).distinctBy { it.name }
        val dirs = all.count { it.isDirectory }
        val archives = all.count { file ->
            !file.isDirectory && file.extension.orEmpty().lowercase() in archiveExts
        }
        return ScanStats(dirs, archives)
    }
}
