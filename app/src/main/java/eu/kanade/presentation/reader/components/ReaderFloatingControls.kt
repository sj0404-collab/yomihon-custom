package eu.kanade.presentation.reader.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mihon.domain.ocr.service.ScanRegion

@Composable
fun ReaderFloatingControls(
    onTriggerOcr: () -> Unit,
    onOpenOcrSettings: () -> Unit,
    onScanRegionChange: (ScanRegion) -> Unit,
    onAutoscrollToggle: (Boolean, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isAutoscrollActive by remember { mutableStateOf(false) }
    var autoscrollSpeed by remember { mutableFloatStateOf(2f) } // 1x to 10x
    var showRegionSelector by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Region Selector Dropdown Panel
            AnimatedVisibility(
                visible = showRegionSelector,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Область сканирования:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        SmallFloatingActionButton(
                            onClick = {
                                onScanRegionChange(ScanRegion.FULL_PAGE)
                                showRegionSelector = false
                                onTriggerOcr()
                            },
                        ) {
                            Text("100% Вся страница")
                        }
                        SmallFloatingActionButton(
                            onClick = {
                                onScanRegionChange(ScanRegion.TOP_HALF)
                                showRegionSelector = false
                                onTriggerOcr()
                            },
                        ) {
                            Text("⬆ Верхняя часть (50%)")
                        }
                        SmallFloatingActionButton(
                            onClick = {
                                onScanRegionChange(ScanRegion.BOTTOM_HALF)
                                showRegionSelector = false
                                onTriggerOcr()
                            },
                        ) {
                            Text("⬇ Нижняя часть (50%)")
                        }
                    }
                }
            }

            // Autoscroll Control Widget Bar
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        onClick = {
                            isAutoscrollActive = !isAutoscrollActive
                            onAutoscrollToggle(isAutoscrollActive, autoscrollSpeed)
                        },
                    ) {
                        Icon(
                            imageVector = if (isAutoscrollActive) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = "Автопрокрутка",
                            tint = if (isAutoscrollActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    if (isAutoscrollActive) {
                        Icon(
                            imageVector = Icons.Outlined.Speed,
                            contentDescription = null,
                        )
                        Slider(
                            value = autoscrollSpeed,
                            onValueChange = {
                                autoscrollSpeed = it
                                onAutoscrollToggle(true, autoscrollSpeed)
                            },
                            valueRange = 1f..10f,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }

                    IconButton(onClick = onOpenOcrSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Psychology,
                            contentDescription = "Настройки OCR",
                        )
                    }
                }
            }

            // Floating OCR Scan Button
            FloatingActionButton(
                onClick = {
                    showRegionSelector = !showRegionSelector
                },
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    imageVector = Icons.Outlined.DocumentScanner,
                    contentDescription = "Сканировать OCR",
                )
            }
        }
    }
}
