package com.kuaixia.app.ui.download

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kuaixia.app.R
import com.kuaixia.app.data.download.DownloadProgress
import com.kuaixia.app.data.download.DownloadStage
import com.kuaixia.app.data.download.DownloadState
import com.kuaixia.app.data.download.DownloadTask
import java.util.Locale

private enum class TaskFilter(@StringRes val labelRes: Int) {
    ALL(R.string.download_filter_all),
    ACTIVE(R.string.download_filter_active),
    COMPLETED(R.string.download_filter_completed),
    FAILED(R.string.download_filter_failed),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadListScreen(
    viewModel: DownloadViewModel,
    onBack: () -> Unit,
) {
    val tasks by viewModel.tasks.collectAsState()
    var filter by remember { mutableStateOf(TaskFilter.ALL) }
    var pendingDelete by remember { mutableStateOf<DownloadTask?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingBatchDelete by remember { mutableStateOf(false) }

    val filtered = tasks.filter { t ->
        when (filter) {
            TaskFilter.ALL -> true
            TaskFilter.ACTIVE ->
                t.state == DownloadState.QUEUED || t.state == DownloadState.DOWNLOADING ||
                    t.state == DownloadState.PAUSED
            TaskFilter.COMPLETED -> t.state == DownloadState.COMPLETED
            TaskFilter.FAILED ->
                t.state == DownloadState.FAILED || t.state == DownloadState.CANCELLED
        }
    }
    val allSelected = filtered.isNotEmpty() && selectedIds.containsAll(filtered.map { it.id })

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (selectionMode) {
                            stringResource(R.string.download_selected_count, selectedIds.size)
                        } else {
                            stringResource(R.string.download_title)
                        },
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = {
                            selectionMode = false
                            selectedIds = emptySet()
                        }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.download_exit_selection),
                            )
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        TextButton(onClick = {
                            selectedIds =
                                if (allSelected) emptySet() else filtered.map { it.id }.toSet()
                        }) {
                            Text(
                                stringResource(
                                    if (allSelected) R.string.download_deselect_all
                                    else R.string.download_select_all,
                                ),
                            )
                        }
                        TextButton(
                            onClick = { pendingBatchDelete = true },
                            enabled = selectedIds.isNotEmpty(),
                        ) {
                            Text(
                                stringResource(R.string.download_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        IconButton(onClick = { selectionMode = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.download_delete_task),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 筛选栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TaskFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(stringResource(f.labelRes)) },
                    )
                }
            }

            if (filtered.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (tasks.isEmpty()) {
                            stringResource(R.string.download_empty)
                        } else {
                            stringResource(R.string.download_empty_category)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(filtered, key = { it.id }) { task ->
                        DownloadTaskCard(
                            task = task,
                            selectionMode = selectionMode,
                            selected = task.id in selectedIds,
                            onToggleSelect = {
                                selectedIds = if (task.id in selectedIds) {
                                    selectedIds - task.id
                                } else {
                                    selectedIds + task.id
                                }
                            },
                            onPause = { viewModel.pause(task.id) },
                            onResume = { viewModel.resume(task.id) },
                            onCancel = { viewModel.cancel(task.id) },
                            onRetry = { viewModel.retry(task.id) },
                            onReparse = { viewModel.reparseAndResume(task.id) },
                            onDelete = { pendingDelete = task },
                        )
                    }
                }
            }
        }
    }

    // 单任务删除确认：只删除任务记录，不删除已下载到手机的媒体文件
    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.download_delete_dialog_title)) },
            text = {
                Column {
                    Text(
                        text = task.title ?: task.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.padding(top = 4.dp))
                    Text(
                        text = stringResource(R.string.download_delete_dialog_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(task.id)
                    pendingDelete = null
                }) {
                    Text(
                        stringResource(R.string.download_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.download_cancel))
                }
            },
        )
    }

    // 批量删除确认：只删除任务记录，不删除已下载到手机的媒体文件
    if (pendingBatchDelete) {
        AlertDialog(
            onDismissRequest = { pendingBatchDelete = false },
            title = { Text(stringResource(R.string.download_delete_dialog_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            R.string.download_selected_tasks_count,
                            selectedIds.size,
                        ),
                    )
                    Spacer(Modifier.padding(top = 4.dp))
                    Text(
                        text = stringResource(R.string.download_delete_dialog_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBatch(selectedIds.toList())
                    pendingBatchDelete = false
                    selectionMode = false
                    selectedIds = emptySet()
                }) {
                    Text(
                        stringResource(R.string.download_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingBatchDelete = false }) {
                    Text(stringResource(R.string.download_cancel))
                }
            },
        )
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onReparse: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selectionMode) Modifier.clickable(onClick = onToggleSelect) else Modifier),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
                    Spacer(Modifier.width(8.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title ?: task.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(
                        task.quality,
                        task.audioCodec?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT),
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = displayLabel(task),
                    style = MaterialTheme.typography.labelMedium,
                    color = stateColor(task),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // 进度区
            when {
                task.state == DownloadState.DOWNLOADING && task.stage == DownloadStage.MERGING -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.download_merging_video_audio),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                task.state == DownloadState.DOWNLOADING || task.state == DownloadState.PAUSED -> {
                    LinearProgressIndicator(
                        progress = { task.progress.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row {
                        Text(
                            text = "${task.progress.percent}%",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatSpeed(task.progress),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val unknownSize = stringResource(R.string.download_size_unknown)
                    Text(
                        text = "${formatSize(task.progress.downloadedBytes, unknownSize)} / " +
                            formatSize(task.progress.totalBytes, unknownSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                task.state == DownloadState.COMPLETED -> {
                    Text(
                        text = stringResource(R.string.download_saved_to_gallery),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            task.errorMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // 操作按钮（多选模式下隐藏，避免与选择点击冲突）
            if (!selectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.End,
                ) {
                    when (task.state) {
                        DownloadState.DOWNLOADING -> {
                            TextButton(onClick = onPause) {
                                Text(stringResource(R.string.download_pause))
                            }
                            TextButton(onClick = onCancel) {
                                Text(stringResource(R.string.download_cancel))
                            }
                        }
                        DownloadState.PAUSED -> {
                            TextButton(onClick = onResume) {
                                Text(stringResource(R.string.download_resume))
                            }
                            TextButton(onClick = onCancel) {
                                Text(stringResource(R.string.download_cancel))
                            }
                        }
                        DownloadState.QUEUED -> {
                            TextButton(onClick = onCancel) {
                                Text(stringResource(R.string.download_cancel))
                            }
                        }
                        DownloadState.FAILED -> {
                            TextButton(onClick = onRetry) {
                                Text(stringResource(R.string.download_retry))
                            }
                            if (task.originalUrl != null) {
                                TextButton(onClick = onReparse) {
                                    Text(stringResource(R.string.download_refresh_link))
                                }
                            }
                            TextButton(onClick = onDelete) {
                                Text(stringResource(R.string.download_delete))
                            }
                        }
                        DownloadState.CANCELLED -> {
                            TextButton(onClick = onRetry) {
                                Text(stringResource(R.string.download_retry))
                            }
                            TextButton(onClick = onDelete) {
                                Text(stringResource(R.string.download_delete))
                            }
                        }
                        DownloadState.COMPLETED -> {
                            TextButton(onClick = onDelete) {
                                Text(stringResource(R.string.download_delete))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun displayLabel(task: DownloadTask): String {
    if (task.state == DownloadState.DOWNLOADING) {
        return when (task.stage) {
            DownloadStage.DOWNLOADING_VIDEO -> stringResource(R.string.download_state_downloading_video)
            DownloadStage.DOWNLOADING_AUDIO -> stringResource(R.string.download_state_downloading_audio)
            DownloadStage.MERGING -> stringResource(R.string.download_state_merging)
            else -> stringResource(R.string.download_state_downloading)
        }
    }
    return when (task.state) {
        DownloadState.QUEUED -> stringResource(R.string.download_state_queued)
        DownloadState.PAUSED -> stringResource(R.string.download_state_paused)
        DownloadState.COMPLETED -> stringResource(R.string.download_state_completed)
        DownloadState.FAILED -> stringResource(R.string.download_state_failed)
        DownloadState.CANCELLED -> stringResource(R.string.download_state_cancelled)
        else -> stringResource(R.string.download_state_downloading)
    }
}

@Composable
private fun stateColor(task: DownloadTask) = when (task.state) {
    DownloadState.DOWNLOADING, DownloadState.QUEUED -> MaterialTheme.colorScheme.primary
    DownloadState.COMPLETED -> MaterialTheme.colorScheme.tertiary
    DownloadState.PAUSED -> MaterialTheme.colorScheme.onSurfaceVariant
    DownloadState.FAILED, DownloadState.CANCELLED -> MaterialTheme.colorScheme.error
}

private fun formatSpeed(p: DownloadProgress): String {
    if (p.speed <= 0) return ""
    return if (p.speed >= 1024 * 1024) {
        String.format(Locale.ROOT, "%.1f MB/s", p.speed / (1024.0 * 1024.0))
    } else {
        String.format(Locale.ROOT, "%.0f KB/s", p.speed / 1024.0)
    }
}

private fun formatSize(bytes: Long, unknown: String): String {
    if (bytes <= 0) return unknown
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1 -> String.format(Locale.ROOT, "%.1f MB", mb)
        else -> String.format(Locale.ROOT, "%.0f KB", kb)
    }
}
