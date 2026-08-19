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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
 * Неограниченное число папок из любых мест (включая Android/data через SAF).
 * Каждая папка — категория: чипы под шапкой переключают «Все / конкретная
 * папка». Папка = манга, одиночный CBZ/CBR = манга с одной главой,
 * обложка из первого изображения. Статус-бар не перекрывается.
 */
data object LocalLibraryTab : Tab {

    private data class ScanStats(val mangaDirs: Int, val archives: Int)

    /**
     * Кэш статистики между заходами на вкладку. Раньше scanStorage()
     * перечитывал ВСЕ папки при каждом входе (и на каждый сигнал
     * storageManager.changes) — на большой библиотеке это фризило UI.
     * Теперь вход на вкладку мгновенный: показывается кэш, пересчёт
     * идёт в фоне только если изменился набор корневых папок.
     */
    @Volatile
    private var cachedStats: ScanStats? = null

    @Volatile
    private var cachedRootsKey: String = ""

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

        var scanning by remember { mutableStateOf(cachedStats == null) }
        var stats by remember { mutableStateOf(cachedStats) }
        var roots by remember { mutableStateOf(storagePreferences.externalLibraryRoots.get().toList()) }
        var activeRoot by remember { mutableStateOf(storagePreferences.externalLibraryActiveRoot.get()) }
        var manageMode by remember { mutableStateOf(false) }

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
                roots = storagePreferences.externalLibraryRoots.get().toList()
                context.toast("Папка добавлена")
            }
        }

        LaunchedEffect(Unit) {
            storageManager.changes
                .onStart { emit(Unit) }
                .collectLatest {
                    roots = storagePreferences.externalLibraryRoots.get().toList()
                    activeRoot = storagePreferences.externalLibraryActiveRoot.get()
                    // Пересканируем ТОЛЬКО если изменился набор папок —
                    // обычный вход на вкладку берёт кэш и не трогает диск
                    val rootsKey = roots.sorted().joinToString("|")
                    if (cachedStats == null || rootsKey != cachedRootsKey) {
                        scanning = true
                        val fresh = withIOContext { scanStorage(storageManager) }
                        cachedStats = fresh
                        cachedRootsKey = rootsKey
                        stats = fresh
                        scanning = false
                    } else {
                        stats = cachedStats
                        scanning = false
                    }
                }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
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
                                        "📂 В выбранных папках манга не найдена"
                                    else ->
                                        "📚 Папок-манг: ${s.mangaDirs} • Архивов: ${s.archives}"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { manageMode = !manageMode },
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
                    if (roots.isNotEmpty()) {
                        // Категории: Все + чип на каждую папку-источник
                        LazyRow(
                            modifier = Modifier.padding(horizontal = 12.dp),
                        ) {
                            item(key = "__all__") {
                                FilterChip(
                                    selected = activeRoot.isBlank(),
                                    onClick = {
                                        storagePreferences.externalLibraryActiveRoot.set("")
                                        activeRoot = ""
                                    },
                                    label = { Text("Все") },
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            }
                            items(roots, key = { it }) { uriString ->
                                FilterChip(
                                    selected = activeRoot == uriString,
                                    onClick = {
                                        val next = if (activeRoot == uriString) "" else uriString
                                        storagePreferences.externalLibraryActiveRoot.set(next)
                                        activeRoot = next
                                    },
                                    label = {
                                        Text(
                                            prettyUri(uriString),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    trailingIcon = if (manageMode) {
                                        {
                                            Icon(
                                                Icons.Outlined.Close,
                                                contentDescription = "Убрать",
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clickable {
                                                        storagePreferences.externalLibraryRoots.set(
                                                            storagePreferences.externalLibraryRoots.get() - uriString,
                                                        )
                                                        if (activeRoot == uriString) {
                                                            storagePreferences.externalLibraryActiveRoot.set("")
                                                            activeRoot = ""
                                                        }
                                                        roots = storagePreferences.externalLibraryRoots.get().toList()
                                                        context.toast("Папка убрана")
                                                    },
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    modifier = Modifier.padding(end = 6.dp),
                                )
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
                .substringAfterLast('/')
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
