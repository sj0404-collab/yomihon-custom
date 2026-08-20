package eu.kanade.presentation.reader.appbars

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import mihon.domain.ocr.model.OcrModel
import mihon.domain.ocr.service.OcrPreferences
import mihon.feature.ocr.titleRes
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun ReaderTopBar(
    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onOpenOcrSettings: (() -> Unit)? = null,
    onOpenInWebView: (() -> Unit)? = null,
    onOpenInBrowser: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    AppBar(
        modifier = modifier,
        backgroundColor = Color.Transparent,
        title = mangaTitle,
        subtitle = chapterTitle,
        navigateUp = navigateUp,
        actions = {
            // Быстрая смена OCR-движка прямо в читалке (по требованию
            // пользователя): иконка сканера → выпадающий список всех моделей,
            // включая локальные. Выбор сохраняется в те же преференсы, что
            // и настройки Text Recognition.
            OcrModelQuickSwitcher()
            AppBarActions(
                actions = buildList {
                    onOpenOcrSettings?.let {
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.pref_category_ocr),
                                icon = Icons.Outlined.Psychology,
                                onClick = it,
                            ),
                        )
                    }
                    add(
                        AppBar.Action(
                            title = stringResource(
                                if (bookmarked) {
                                    MR.strings.action_remove_bookmark
                                } else {
                                    MR.strings.action_bookmark
                                },
                            ),
                            icon = if (bookmarked) {
                                Icons.Outlined.Bookmark
                            } else {
                                Icons.Outlined.BookmarkBorder
                            },
                            onClick = onToggleBookmarked,
                        ),
                    )
                    onOpenInWebView?.let {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_open_in_web_view),
                                onClick = it,
                            ),
                        )
                    }
                    onOpenInBrowser?.let {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_open_in_browser),
                                onClick = it,
                            ),
                        )
                    }
                    onShare?.let {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_share),
                                onClick = it,
                            ),
                        )
                    }
                },
            )
        },
    )
}

@Composable
private fun OcrModelQuickSwitcher() {
    val prefs = remember { Injekt.get<OcrPreferences>() }
    val modelPref = remember { prefs.ocrModel() }
    val current by modelPref.changes().collectAsState(initial = modelPref.get())
    var open by remember { mutableStateOf(false) }

    IconButton(onClick = { open = true }) {
        Icon(
            Icons.Outlined.DocumentScanner,
            contentDescription = "OCR-движок",
        )
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        listOf(
            OcrModel.ZEN_FREE,
            OcrModel.GLENS,
            OcrModel.TESSERACT,
            OcrModel.FAST,
            OcrModel.LEGACY,
            OcrModel.OWOCR,
            OcrModel.OPENROUTER,
            OcrModel.GOOGLE,
        ).forEach { model ->
            DropdownMenuItem(
                text = { Text(stringResource(model.titleRes)) },
                leadingIcon = {
                    RadioButton(selected = current == model, onClick = null)
                },
                onClick = {
                    modelPref.set(model)
                    open = false
                },
            )
        }
    }
}
