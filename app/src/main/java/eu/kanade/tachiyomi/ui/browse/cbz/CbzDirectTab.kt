package eu.kanade.tachiyomi.ui.browse.cbz

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data class LocalCbzFile(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
)

@Composable
fun Screen.cbzDirectTab(): TabContent {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val recentCbzFiles = remember { mutableStateListOf<LocalCbzFile>() }

    val openCbzLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        uris.forEach { uri ->
            val uniFile = UniFile.fromUri(context, uri)
            val name = uniFile?.name ?: uri.lastPathSegment ?: "archive.cbz"
            val size = uniFile?.length() ?: 0L
            val item = LocalCbzFile(uri = uri, name = name, sizeBytes = size)
            if (recentCbzFiles.none { it.uri == uri }) {
                recentCbzFiles.add(0, item)
            }
        }
    }

    return TabContent(
        titleRes = MR.strings.cbz_direct_reader,
        content = { contentPadding, _ ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(MR.strings.cbz_direct_reader),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(MR.strings.cbz_direct_reader_summary),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = {
                                openCbzLauncher.launch(
                                    arrayOf(
                                        "application/x-cbz",
                                        "application/zip",
                                        "application/x-zip-compressed",
                                        "application/x-cbr",
                                        "application/x-rar-compressed",
                                        "application/epub+zip",
                                        "*/*",
                                    ),
                                )
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FolderOpen,
                                    contentDescription = null,
                                )
                                Text(text = stringResource(MR.strings.action_open_cbz_file))
                            }
                        }
                    }
                }

                if (recentCbzFiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(MR.strings.cbz_no_files_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(
                        text = "Файлы для прямого чтения (без кэширования):",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(recentCbzFiles, key = { it.uri.toString() }) { cbz ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // Open direct archive reader with zero caching
                                        val intent = ReaderActivity.newIntent(context, cbz.uri)
                                        context.startActivity(intent)
                                    },
                                colors = CardDefaults.outlinedCardColors(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.MenuBook,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = cbz.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                        )
                                        Text(
                                            text = "${cbz.sizeBytes / (1024 * 1024)} MB • Прямой стриминг",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}
