package com.kuaixia.app.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kuaixia.app.R
import com.kuaixia.app.data.ytdlp.FileStatus
import com.kuaixia.app.data.ytdlp.YtDlpInitState
import com.kuaixia.app.data.ytdlp.YtDlpStatus
import com.kuaixia.app.ui.asString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YtDlpDebugScreen(
    viewModel: YtDlpDebugViewModel,
    onBack: () -> Unit,
) {
    val status by viewModel.status.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.debug_running), style = MaterialTheme.typography.bodyMedium)
                }
            }

            InitStatusCard(status)

            OutlinedButton(onClick = viewModel::reinitialize, enabled = !uiState.running) {
                Text(stringResource(R.string.debug_reinitialize))
            }

            FileStatusCard(viewModel.fileStatus())

            HorizontalDivider()

            SectionTitle(stringResource(R.string.debug_network_env))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    viewModel.networkEnvReport().forEach { line ->
                        Text(line.asString(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            HorizontalDivider()

            LastResultCard(status)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun InitStatusCard(status: YtDlpStatus) {
    val (label, color) = when (status.initState) {
        YtDlpInitState.NOT_STARTED -> stringResource(R.string.debug_init_not_started) to MaterialTheme.colorScheme.onSurfaceVariant
        YtDlpInitState.INITIALIZING -> stringResource(R.string.debug_init_initializing) to MaterialTheme.colorScheme.primary
        YtDlpInitState.READY -> stringResource(R.string.debug_init_ready) to MaterialTheme.colorScheme.primary
        YtDlpInitState.FAILED -> stringResource(R.string.debug_init_failed) to MaterialTheme.colorScheme.error
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.debug_status_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
            status.initMessage?.let {
                Text(
                    text = stringResource(R.string.debug_details_format, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FileStatusCard(items: List<FileStatus>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.debug_env_files), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            items.forEach { item ->
                Row {
                    Text(
                        text = if (item.ok) "✓" else "✗",
                        color = if (item.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(20.dp),
                    )
                    Column {
                        Text(item.label, style = MaterialTheme.typography.bodyMedium)
                        Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun LastResultCard(status: YtDlpStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.debug_last_result), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            status.lastCommand?.let { Text(stringResource(R.string.debug_command_format, it), style = MaterialTheme.typography.bodySmall) }
            status.lastExitCode?.let {
                Text(
                    text = stringResource(R.string.debug_exit_code_format, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (it == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            status.lastStdout?.let {
                Text(stringResource(R.string.debug_stdout), style = MaterialTheme.typography.labelLarge)
                Text(it.ifBlank { stringResource(R.string.debug_empty_value) }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            status.lastStderr?.let {
                Text(stringResource(R.string.debug_stderr), style = MaterialTheme.typography.labelLarge)
                Text(
                    text = it.ifBlank { stringResource(R.string.debug_empty_value) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
