package eu.kanade.tachiyomi.ui.locallibrary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Локальная библиотека — нативная вкладка на базе локального источника.
 *
 * Сканируются и корень выбранного хранилища, и подпапка local/ (как CDisplayEx):
 * папка = манга (внутри CBZ/CBR/ZIP-главы или папки-главы), одиночный архив =
 * манга с одной главой, обложка берётся из первого изображения архива.
 * Сверху — живая индикация скана: найдено папок-манг и одиночных архивов.
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
        val storageManager = remember { Injekt.get<StorageManager>() }
        var scanning by remember { mutableStateOf(true) }
        var stats by remember { mutableStateOf<ScanStats?>(null) }

        // Быстрый скан (только имена файлов) + автоперескан при смене папки хранилища
        LaunchedEffect(Unit) {
            storageManager.changes
                .onStart { emit(Unit) }
                .collectLatest {
                    scanning = true
                    stats = withIOContext { scanStorage(storageManager) }
                    scanning = false
                }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "  Сканирование хранилища…",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    } else {
                        val s = stats
                        Text(
                            text = if (s == null || (s.mangaDirs == 0 && s.archives == 0)) {
                                "📂 Манга не найдена — выберите папку хранилища ниже"
                            } else {
                                "📚 Папок-манг: ${s.mangaDirs} • Одиночных архивов: ${s.archives}"
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
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

    private fun scanStorage(storageManager: StorageManager): ScanStats {
        val reserved = setOf("autobackup", "downloads", "local", ".covers", ".nomedia")
        val archiveExts = setOf("cbz", "zip", "cbr", "rar", "epub")

        val root = storageManager.getBaseDirectory()?.listFiles().orEmpty()
            .filterNot {
                val n = it.name.orEmpty()
                n.startsWith('.') || n.lowercase() in reserved
            }
        val local = storageManager.getLocalSourceDirectory()?.listFiles().orEmpty()
            .filterNot { it.name.orEmpty().startsWith('.') }

        val all = (root + local).distinctBy { it.name }
        val dirs = all.count { it.isDirectory }
        val archives = all.count { file ->
            !file.isDirectory && file.extension.orEmpty().lowercase() in archiveExts
        }
        return ScanStats(dirs, archives)
    }
}
