package com.xenonware.notes.data

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xenonware.notes.util.audio.HardwareAccelerator
import com.xenonware.notes.util.audio.NpuHardwareManager
import com.xenonware.notes.util.audio.SpeechDownloadState
import com.xenonware.notes.util.audio.SpeechEngine
import com.xenonware.notes.util.audio.SpeechModelDownloadManager
import com.xenonware.notes.util.audio.SpeechModelSpec
import com.xenonware.notes.util.audio.SpeechModelStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings card for managing the on-device `Voz` speech recognition model and hardware NPU / GPU / CPU acceleration.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeechModelSection(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceBright,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    shape: Shape = RoundedCornerShape(24.dp),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { SharedPreferenceManager(context) }

    val downloadState by SpeechModelDownloadManager.state.collectAsState()
    var refreshTick by remember { mutableIntStateOf(0) }
    var isDownloaded by remember { mutableStateOf(false) }
    var selectedAccelerator by remember { mutableStateOf(prefs.speechHardwareAccelerator) }
    var hardwareSummary by remember { mutableStateOf(NpuHardwareManager.cachedHardwareSummaryOrQuick()) }

    LaunchedEffect(refreshTick, downloadState) {
        val (downloaded, profileSummary) = withContext(Dispatchers.IO) {
            SpeechModelDownloadManager.syncFromDiskIfIdle(context)
            SpeechModelStorage.isModelDownloaded(context) to
                NpuHardwareManager.detectHardwareProfile().summaryLabel
        }
        isDownloaded = downloaded
        hardwareSummary = profileSummary
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        SpeechModelDownloadManager.start(context)
    }

    val startOrResumeDownload: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SpeechModelDownloadManager.start(context)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = backgroundColor,
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Speech Recognition (${SpeechModelSpec.DISPLAY_NAME})",
                        style = MaterialTheme.typography.titleMedium,
                        color = contentColor,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Parakeet TDT 0.6B v3 · 25 European languages · ${SpeechModelSpec.SIZE_LABEL}",
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Memory,
                    contentDescription = null,
                    tint = subtitleColor,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Detected: $hardwareSummary",
                    style = MaterialTheme.typography.labelMedium,
                    color = subtitleColor,
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Download / Installed Status ─────────────────────────────
            when (val dl = downloadState) {
                is SpeechDownloadState.Downloading -> {
                    val pct = (dl.clampedProgress * 100f).toInt()
                    Text(
                        text = "Downloading ($pct%) · ${dl.formattedBytes}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { dl.clampedProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = dl.statusNote ?: "${dl.formattedSpeed} • ${dl.formattedEta}",
                            style = MaterialTheme.typography.labelSmall,
                            color = subtitleColor,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { SpeechModelDownloadManager.pause() },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Pause,
                                contentDescription = "Pause download",
                                tint = contentColor,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(
                            onClick = { SpeechModelDownloadManager.cancel(context) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Cancel download",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                is SpeechDownloadState.Paused -> {
                    val pct = (dl.progress.coerceIn(0f, 1f) * 100f).toInt()
                    Text(
                        text = "Paused ($pct%) · ${dl.formattedBytes}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { dl.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = dl.statusNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = subtitleColor,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = startOrResumeDownload) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp).size(16.dp),
                            )
                            Text("Resume")
                        }
                        OutlinedButton(onClick = {
                            SpeechModelDownloadManager.cancel(context)
                            refreshTick++
                        }) {
                            Text("Cancel")
                        }
                    }
                }

                is SpeechDownloadState.Failed -> {
                    Text(
                        text = dl.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = startOrResumeDownload) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp).size(16.dp),
                            )
                            Text("Retry")
                        }
                        OutlinedButton(onClick = {
                            SpeechModelDownloadManager.acknowledgeTerminalState()
                            SpeechModelDownloadManager.cancel(context)
                            refreshTick++
                        }) {
                            Text("Discard")
                        }
                    }
                }

                is SpeechDownloadState.Completed, SpeechDownloadState.Idle -> {
                    if (isDownloaded) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Model ready (${SpeechModelSpec.SIZE_LABEL} · SHA-256 verified)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 6.dp),
                            )
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        SpeechModelDownloadManager.cancelForDeletionAndWait(context)
                                        SpeechEngine.getInstance(context).unloadModel()
                                        withContext(Dispatchers.IO) {
                                            SpeechModelStorage.deleteModel(context)
                                        }
                                        SpeechModelDownloadManager.acknowledgeTerminalState()
                                        refreshTick++
                                    }
                                },
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = "Delete speech model",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    } else {
                        FilledTonalButton(onClick = startOrResumeDownload) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp).size(16.dp),
                            )
                            Text("Download Voz (${SpeechModelSpec.SIZE_LABEL})")
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── Hardware Acceleration Selector ──────────────────────────
            Text(
                text = "Hardware Acceleration",
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HardwareAccelerator.entries.forEach { accel ->
                    FilterChip(
                        selected = selectedAccelerator == accel,
                        onClick = {
                            selectedAccelerator = accel
                            prefs.speechHardwareAccelerator = accel
                        },
                        label = { Text(accel.shortLabel) },
                    )
                }
            }
            AnimatedVisibility(visible = true) {
                Text(
                    text = selectedAccelerator.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = subtitleColor,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
