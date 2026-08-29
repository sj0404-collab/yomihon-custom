package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * OCR voice actions intentionally live outside the result card. The reader keeps
 * the recognized text uncluttered while both speech and voice selection remain
 * available next to the active OCR dialog.
 */
@Composable
fun OcrVoiceFloatingControls(
    enabled: Boolean,
    onSpeak: () -> Unit,
    onChooseVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ExtendedFloatingActionButton(
            onClick = { if (enabled) onSpeak() },
            icon = { Icon(Icons.Outlined.RecordVoiceOver, contentDescription = null) },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            text = { Text("Голос") },
        )
        ExtendedFloatingActionButton(
            onClick = onChooseVoice,
            icon = { Icon(Icons.Outlined.Tune, contentDescription = null) },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            text = { Text("Выбрать голос") },
        )
    }
}
