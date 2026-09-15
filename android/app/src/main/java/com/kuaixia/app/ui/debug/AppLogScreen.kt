package com.kuaixia.app.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kuaixia.app.R
import com.kuaixia.app.core.log.AppLog
import com.kuaixia.app.core.log.AppLogRepository
import com.kuaixia.app.core.log.LogExport
import com.kuaixia.app.core.log.LogLevel
import com.kuaixia.app.core.log.LogTags
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 日志级别快捷筛选（null=全部）。 */
private val ALL_TAGS = listOf(
    LogTags.KUAIXIA,
    LogTags.PARSER,
    LogTags.YTDLP,
    LogTags.SERVER,
    LogTags.DOWNLOAD,
    LogTags.HTTP,
    LogTags.M3U8,
    LogTags.FFMPEG,
    LogTags.MEDIASTORE,
    LogTags.CRASH,
    LogTags.WEBVIEW,
    LogTags.DOUYIN,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLogScreen(
    viewModel: AppLogViewModel,
    onBack: () -> Unit,
) {
    val logs by viewModel.logs.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val lastCrash by viewModel.lastCrash.collectAsState()
    val exportRange by viewModel.exportRange.collectAsState()
    val context = LocalContext.current

    // 日志 V2：系统 SAF 选择保存位置（不写死目录）
    val txtSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        uri?.let {
            val msg = if (viewModel.writeTextTo(it)) context.getString(R.string.log_exported)
            else context.getString(R.string.log_export_failed)
            toast(context, msg)
        }
    }
    val zipSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        uri?.let {
            val msg = if (viewModel.writeZipTo(it)) context.getString(R.string.log_zip_exported)
            else context.getString(R.string.log_export_failed)
            toast(context, msg)
        }
    }

    var selectedLog by remember { mutableStateOf<AppLog?>(null) }
    var showCrash by remember { mutableStateOf(false) }

    // ---- 筛选状态 ----
    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    var keyword by remember { mutableStateOf("") }

    val filtered = remember(logs, levelFilter, tagFilter, keyword) {
        logs.filter { log ->
            (levelFilter == null || log.level == levelFilter) &&
                (tagFilter == null || log.tag == tagFilter) &&
                (keyword.isBlank() || log.message.contains(keyword.trim(), ignoreCase = true) ||
                    !log.throwable.isNullOrBlank() &&
                    log.throwable.contains(keyword.trim(), ignoreCase = true))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.log_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.log_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            deviceInfo?.let { DeviceInfoCard(it) }

            lastCrash?.let { crash ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.log_crash_detected),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Row {
                            TextButton(onClick = { showCrash = true }) { Text(stringResource(R.string.log_view)) }
                            TextButton(onClick = {
                                copyToClipboard(context, crash)
                                toast(context, context.getString(R.string.log_crash_copied))
                            }) { Text(stringResource(R.string.log_copy)) }
                            TextButton(onClick = {
                                viewModel.clearCrash()
                                viewModel.refresh()
                            }) { Text(stringResource(R.string.log_clear_crash)) }
                        }
                    }
                }
            }

            // ---- 导出范围（默认最近 24 小时；测试用户无需上传数十天日志） ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogExport.Range.entries.forEach { r ->
                    FilterChip(
                        selected = exportRange == r,
                        onClick = { viewModel.setExportRange(r) },
                        label = { Text(stringResource(r.labelRes)) },
                    )
                }
            }

            val exportRangeLabel = stringResource(exportRange.labelRes)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        copyToClipboard(context, viewModel.exportText())
                        toast(context, context.getString(R.string.log_copied_range_logs, exportRangeLabel))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.log_copy)) }

                OutlinedButton(
                    onClick = { txtSaver.launch(viewModel.textFileName()) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.log_export_txt)) }

                OutlinedButton(
                    onClick = { zipSaver.launch(viewModel.zipFileName()) },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.log_export_zip)) }

                OutlinedButton(
                    onClick = {
                        viewModel.clearLogs()
                        toast(context, context.getString(R.string.log_cleared))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.log_clear)) }
            }

            // ---- 筛选区：级别 + Tag ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LevelChip(null, stringResource(R.string.log_filter_all), levelFilter == null) { levelFilter = null }
                LogLevel.entries.forEach { lv ->
                    LevelChip(lv, lv.name, levelFilter == lv) { levelFilter = lv }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TagChip(null, stringResource(R.string.log_filter_all_tag), tagFilter == null) { tagFilter = null }
                ALL_TAGS.forEach { t ->
                    TagChip(t, t, tagFilter == t) { tagFilter = t }
                }
            }
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text(stringResource(R.string.log_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
            )

            Text(
                text = stringResource(R.string.log_showing_count, filtered.size, logs.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )

            // 日志列表：SelectionContainer 保证可长按选择复制
            SelectionContainer(modifier = Modifier.fillMaxSize()) {
                if (filtered.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.log_no_match),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filtered, key = { it.id }) { log ->
                            LogRow(
                                log = log,
                                onClick = { selectedLog = log },
                                onLongClick = {
                                    copyToClipboard(context, fullText(log))
                                    toast(context, context.getString(R.string.log_copied_entry))
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    selectedLog?.let { log ->
        AlertDialog(
            onDismissRequest = { selectedLog = null },
            title = { Text("${log.level.name} / ${log.tag}") },
            text = {
                SelectionContainer {
                    Column {
                        Text(
                            text = fullText(log),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    copyToClipboard(context, fullText(log))
                    toast(context, context.getString(R.string.log_copied))
                    selectedLog = null
                }) { Text(stringResource(R.string.log_copy)) }
            },
            dismissButton = {
                TextButton(onClick = { selectedLog = null }) { Text(stringResource(R.string.log_close)) }
            },
        )
    }

    if (showCrash) {
        AlertDialog(
            onDismissRequest = { showCrash = false },
            title = { Text(stringResource(R.string.log_last_crash)) },
            text = {
                SelectionContainer {
                    Text(
                        text = lastCrash.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showCrash = false }) { Text(stringResource(R.string.log_close)) }
            },
        )
    }
}

@Composable
private fun LevelChip(level: LogLevel?, label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun TagChip(tag: String?, label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun DeviceInfoCard(info: DeviceInfo) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(stringResource(R.string.log_device_info), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            DebugLine(stringResource(R.string.log_label_app_version), info.appVersion)
            DebugLine(stringResource(R.string.log_label_system), info.androidVersion)
            DebugLine(stringResource(R.string.log_label_device), info.deviceModel)
            DebugLine(stringResource(R.string.log_label_cpu_abi), info.cpuAbi)
            DebugLine(stringResource(R.string.log_label_ytdlp), info.ytdlpVersion ?: stringResource(R.string.log_not_available))
            DebugLine(stringResource(R.string.log_label_ffmpeg), info.ffmpegVersion ?: stringResource(R.string.log_not_available))
            DebugLine(stringResource(R.string.log_label_parse_mode), info.parseMode)
            DebugLine(stringResource(R.string.log_label_downloader), info.downloaderVersion)
        }
    }
}

@Composable
private fun DebugLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(log: AppLog, onClick: () -> Unit, onLongClick: () -> Unit) {
    val color = when (log.level) {
        LogLevel.DEBUG -> Color(0xFF6B7280)
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
        LogLevel.WARN -> Color(0xFFB45309)
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    Text(
        text = "[${formatTime(log.timestamp)}] ${log.level.name} ${log.tag} ${log.message}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 3.dp),
    )
}

private fun fullText(log: AppLog): String = buildString {
    append(AppLogRepository.format(log))
    if (!log.throwable.isNullOrBlank()) {
        appendLine()
        append(log.throwable)
    }
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA).format(Date(ts))

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("kuaixia-log", text))
    }
}

private fun toast(context: Context, message: String) {
    runCatching { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
}
