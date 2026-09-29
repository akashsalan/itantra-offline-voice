package org.itantra.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.itantra.app.core.ImportStage
import org.itantra.app.core.PackImportProgress
import java.util.Locale

@Composable internal fun ImportProgressDialog(progress: PackImportProgress, busy: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        title = { Text(when (progress.stage) {
            ImportStage.READY -> "Model imported"
            ImportStage.FAILED -> "Model setup needs attention"
            else -> "Importing language pack"
        }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(progress.language?.displayName ?: "Checking selected file", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    progress.percent?.let { Text("$it%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                }
                if (progress.percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { progress.percent / 100f }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (progress.stage == ImportStage.LOADING) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(progress.stage.label, style = MaterialTheme.typography.bodyMedium)
                }
                progress.file?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                if (progress.modelBytes > 0) Text(
                    String.format(Locale.US, "%.1f / %.1f MB copied", progress.copiedBytes / 1_000_000.0, progress.modelBytes / 1_000_000.0),
                    style = MaterialTheme.typography.bodySmall
                )
                progress.error?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                if (progress.stage == ImportStage.LOADING) Text("Files are imported (100%). Starting the offline engine…", style = MaterialTheme.typography.bodySmall)
                else if (progress.stage == ImportStage.READY) Text("Available on this phone without internet.", style = MaterialTheme.typography.bodySmall)
                else if (progress.stage != ImportStage.FAILED) Text("Percentage includes copying and checksum verification. Please keep the app open.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { if (!busy) TextButton(onDismiss) { Text("Done") } }
    )
}
