package eu.kanade.tachiyomi.ui.locallibrary

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.source.local.LocalSource

/**
 * Локальная библиотека — нативная вкладка на базе локального источника Mihon.
 *
 * Устройство хранилища то же, что и в оригинальном приложении:
 * - выбранная папка хранилища (Настройки → Данные и хранилище) содержит
 *   подпапку local, в ней каждая манга — своя папка;
 * - внутри папки манги: одиночные CBZ/CBR/ZIP-архивы глав, папки-главы
 *   с изображениями, cover.jpg и опциональный details.json;
 * - обложки и метаданные подхватываются автоматически, тап по обложке
 *   открывает полноценную карточку манги с главами и нативной читалкой.
 */
data object LocalLibraryTab : Tab {

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
        // Вложенный навигатор: каталог локального источника + карточки манги
        Navigator(
            screen = BrowseSourceScreen(
                sourceId = LocalSource.ID,
                listingQuery = GetRemoteManga.QUERY_POPULAR,
            ),
        )
    }
}
