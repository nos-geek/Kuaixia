package com.kuaixia.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.kuaixia.app.R
import com.kuaixia.app.core.model.AppLanguage
import com.kuaixia.app.data.format.DisplayFormatGroup
import com.kuaixia.app.data.format.FormatDisplayGrouper
import com.kuaixia.app.data.model.StreamInfo
import com.kuaixia.app.data.model.VideoInfo
import com.kuaixia.app.ui.asString
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    currentLanguage: AppLanguage,
    onToggleLanguage: () -> Unit,
    onUrlChange: (String) -> Unit,
    onParse: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onDownload: (StreamInfo) -> Unit,
    onDownloadImageAt: (VideoInfo, Int) -> Unit,
    onDownloadImages: (VideoInfo) -> Unit,
    onDownloadImagesSelected: (VideoInfo, List<Int>) -> Unit,
    onPaste: () -> Unit,
    onHomeReady: () -> Unit,
    onClipboardSource: (ClipboardSource) -> Unit,
    onParseClipboard: () -> Unit,
    onIgnoreClipboard: () -> Unit,
    onOpenDouyinLogin: () -> Unit,
    onDismissDouyinPrompt: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val appContext = LocalContext.current.applicationContext
    DisposableEffect(lifecycleOwner, appContext) {
        val cm = appContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
        val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
            // 只有 Listener 注册期间（Home 在前台）才可能收到，天然避免后台触发
            onClipboardSource(ClipboardSource.CLIPBOARD_CHANGED)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // 监听仅在 Home 前台注册：切到别的 App 复制链接后回到首页也能收到变化
                    cm?.addPrimaryClipChangedListener(listener)
                    // 回到前台时补一次剪贴板检查（自动解析提示）；
                    // ViewModel 内按 RESUME_CHECK_MIN_INTERVAL_MS 节流，
                    // 避免快速连续导航产生的密集 RESUME 造成重复读剪贴板。
                    onClipboardSource(ClipboardSource.RESUME)
                }
                Lifecycle.Event.ON_PAUSE -> cm?.removePrimaryClipChangedListener(listener)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cm?.removePrimaryClipChangedListener(listener)
        }
    }
    // 唯一 INITIAL 入口（ViewModel 内部幂等，只执行一次；不做任何提示/解析状态机）
    LaunchedEffect(Unit) { onHomeReady() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title), fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = onToggleLanguage) {
                        Text(
                            text = stringResource(
                                if (currentLanguage == AppLanguage.ZH) R.string.home_lang_cn
                                else R.string.home_lang_en,
                            ),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    IconButton(onClick = onOpenDownloads) {
                        Icon(Icons.Default.Download, contentDescription = stringResource(R.string.home_download_list))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.home_settings))
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
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // 链接输入
            OutlinedTextField(
                value = uiState.url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.home_url_hint)) },
                placeholder = { Text("https://…") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = onPaste) {
                        Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.home_paste))
                    }
                },
            )

            // 检测到剪贴板链接提示条（默认不自动解析，用户确认）
            uiState.clipboardUrl?.let { link ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.ContentPaste,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = uiState.clipboardPlatformLabel?.let {
                                    stringResource(R.string.home_clipboard_detected_platform, it)
                                } ?: stringResource(R.string.home_clipboard_detected),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Text(
                            text = link,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = onIgnoreClipboard) { Text(stringResource(R.string.home_ignore)) }
                            TextButton(onClick = onParseClipboard) { Text(stringResource(R.string.home_parse_now)) }
                        }
                    }
                }
            }

            // 自动解析剪贴板状态反馈（成功/失败均可见，不静默）
            uiState.autoParseNote?.let { note ->
                Text(
                    text = note.asString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (uiState.autoParseNoteError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // 解析 / 取消
            if (uiState.isLoading) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(stringResource(R.string.home_cancel_parse), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Button(
                    onClick = onParse,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text(stringResource(R.string.home_parse), style = MaterialTheme.typography.titleMedium)
                }
            }

            // 错误提示
            uiState.errorMessage?.let { message ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = message.asString(),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            // 解析结果
            uiState.result?.let { result ->
                VideoInfoCard(
                    info = result,
                    sourceLabel = uiState.sourceLabel,
                    onDownload = onDownload,
                    onDownloadImageAt = onDownloadImageAt,
                    onDownloadImages = onDownloadImages,
                    onDownloadImagesSelected = onDownloadImagesSelected,
                )
            }
        }
    }
}

@Composable
private fun VideoInfoCard(
    info: VideoInfo,
    sourceLabel: String?,
    onDownload: (StreamInfo) -> Unit,
    onDownloadImageAt: (VideoInfo, Int) -> Unit,
    onDownloadImages: (VideoInfo) -> Unit,
    onDownloadImagesSelected: (VideoInfo, List<Int>) -> Unit,
) {
    var selectedStream by remember(info.id) { mutableStateOf<StreamInfo?>(null) }
    // 按清晰度分组（Phase 6.5）：每组一个最佳代表；底层完整 formats 保留在组.alternatives
    val groups = remember(info.id) { FormatDisplayGrouper.group(info.streams) }
    // 图集「选中下载」状态（内存态，不影响解析核心）
    var selectedImages by remember(info.id) { mutableStateOf(setOf<Int>()) }
    val clipboard = LocalClipboardManager.current
    val copyContext = LocalContext.current
    fun copyToClipboard(text: String, what: String) {
        clipboard.setText(AnnotatedString(text))
        android.widget.Toast.makeText(
            copyContext,
            copyContext.getString(R.string.home_copied, what),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
    // 媒体链接复制对象：优先第一个视频流（DASH 取视频分片），无视频流且单图时取图片
    val mediaCopyUrl: String? = info.streams.firstOrNull()?.let { if (it.isDash) it.videoUrl else it.url }
        ?: info.imageItems.singleOrNull()?.url

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.home_parse_success),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                sourceLabel?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // 复制入口（复制真实 URL，非内部 id）
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    copyToClipboard(info.webpageUrl, copyContext.getString(R.string.home_work_link))
                }) {
                    Text(stringResource(R.string.home_copy_work_link))
                }
                if (mediaCopyUrl != null) {
                    TextButton(onClick = {
                        copyToClipboard(mediaCopyUrl, copyContext.getString(R.string.home_media_link))
                    }) {
                        Text(stringResource(R.string.home_copy_media_link))
                    }
                }
            }

            // 封面/缩略图：优先解析器返回的 thumbnail；图集回退到第一张已过滤的真实作品图；
            // 无封面或加载失败 → 媒体占位（绝不因此影响解析结果）。
            val coverUrl = info.thumbnail ?: info.imageItems.firstOrNull()?.url
            MediaCover(
                url = coverUrl,
                typeLabel = stringResource(info.mediaType.labelRes),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (coverUrl != null) 160.dp else 110.dp),
            )

            info.title?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    // UI 保险：即使异常标题超长也不把结果卡撑高（根因修复在 WebViewParser 标题回退）
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            info.author?.let { InfoRow(stringResource(R.string.home_author), it) }
            InfoRow(stringResource(R.string.home_platform), info.platform)
            info.duration?.let { InfoRow(stringResource(R.string.home_duration), formatDuration(it)) }

            if (info.streams.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f),
                )
                Text(
                    text = stringResource(R.string.home_quality_hint),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
                if (groups.isNotEmpty()) {
                    groups.forEach { group ->
                        FormatGroupRow(
                            group = group,
                            onClick = { selectedStream = group.representative },
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.home_no_downloadable_stream),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            // 图片/图集：缩略图 + 勾选（下载选中/下载全部），保持捕获顺序
            if (info.imageItems.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.15f),
                )
                Text(
                    text = if (info.imageItems.size == 1) {
                        stringResource(R.string.home_image_content)
                    } else {
                        stringResource(R.string.home_image_collection_content, info.imageItems.size)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
                info.imageItems.forEachIndexed { idx, res ->
                    ImageRow(
                        url = res.url,
                        mimeHint = res.mimeType,
                        label = stringResource(R.string.home_image_index, idx + 1, info.imageItems.size),
                        checked = idx in selectedImages,
                        onToggle = {
                            selectedImages = if (idx in selectedImages) {
                                selectedImages - idx
                            } else {
                                selectedImages + idx
                            }
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selectedImages.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                onDownloadImagesSelected(info, selectedImages.toList())
                                selectedImages = emptySet()
                            },
                        ) {
                            Text(stringResource(R.string.home_download_selected, selectedImages.size))
                        }
                    }
                    TextButton(onClick = { onDownloadImages(info) }) {
                        Text(stringResource(R.string.home_download_all_images, info.imageItems.size))
                    }
                }
            }
        }
    }

    selectedStream?.let { stream ->
        StreamDetailDialog(
            stream = stream,
            onDismiss = { selectedStream = null },
            onDownload = {
                onDownload(stream)
                selectedStream = null
            },
        )
    }
}

@Composable
private fun ImageRow(
    url: String,
    mimeHint: String?,
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = url,
            contentDescription = label,
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        mimeHint?.takeIf { it.isNotBlank() }?.let { hint ->
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f),
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun FormatGroupRow(group: DisplayFormatGroup, onClick: () -> Unit) {
    val rep = group.representative
    val codec = FormatDisplayGrouper.codecLabelShort(rep.vcodec).ifBlank { stringResource(R.string.common_unknown) }
    val fps = FormatDisplayGrouper.fpsLabel(rep.fps)
    val sizeText = rep.fileSize?.takeIf { it > 0 }?.let {
        stringResource(R.string.home_approx_size, formatFileSize(it))
    }
    val alt = group.alternatives.size
    val altText = if (alt > 0) stringResource(R.string.home_more_versions, alt) else null
    val desc = listOfNotNull(codec, fps, sizeText, altText).joinToString(" · ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = group.label.ifBlank { stringResource(R.string.format_other) },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StreamDetailDialog(stream: StreamInfo, onDismiss: () -> Unit, onDownload: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stream.quality ?: stringResource(R.string.home_format_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow(stringResource(R.string.home_format), stream.ext?.uppercase(Locale.ROOT))
                DetailRow(stringResource(R.string.home_resolution), stream.resolution)
                stream.fps?.let { DetailRow(stringResource(R.string.home_frame_rate), formatFps(it)) }
                val codec = codecLabel(stream)
                if (codec.isNotBlank()) DetailRow(stringResource(R.string.home_codec), codec)
                stream.fileSize?.let { DetailRow(stringResource(R.string.home_size), formatFileSize(it)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload) { Text(stringResource(R.string.home_download)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_close)) }
        },
    )
}

/**
 * 媒体封面：真实 URL 可加载 → 图片；无 URL / 加载失败 → 类型占位图。
 * 纯展示组件：其内部图片请求走 Coil（非 WebView），不会污染解析嗅探。
 */
@Composable
private fun MediaCover(url: String?, typeLabel: String, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.clip(RoundedCornerShape(12.dp))) {
        if (url?.isNotBlank() == true && !failed) {
            AsyncImage(
                model = url,
                contentDescription = stringResource(R.string.home_cover),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { failed = true },
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

private fun codecLabel(stream: StreamInfo): String =
    listOfNotNull(
        stream.vcodec?.takeIf { it.isNotBlank() && it != "none" },
        stream.acodec?.takeIf { it.isNotBlank() && it != "none" },
    ).joinToString("+")

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatFps(fps: Float): String =
    if (fps % 1f == 0f) fps.toInt().toString() else String.format(Locale.ROOT, "%.2f", fps)

private fun formatFileSize(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
