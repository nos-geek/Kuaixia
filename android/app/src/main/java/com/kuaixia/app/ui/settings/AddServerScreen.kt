package com.kuaixia.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kuaixia.app.R
import com.kuaixia.app.core.model.ParserServerConfig
import com.kuaixia.app.core.model.UiText
import com.kuaixia.app.core.util.ServerUrl
import com.kuaixia.app.ui.asString
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(
    serverId: String,
    existingServer: ParserServerConfig?,
    onSave: (ParserServerConfig) -> Unit,
    onTestConnection: suspend (String) -> Pair<Boolean, UiText>,
    onBack: () -> Unit,
) {
    val isEdit = existingServer != null
    var name by remember { mutableStateOf(existingServer?.name ?: "") }
    var baseUrl by remember { mutableStateOf(existingServer?.baseUrl ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, UiText>?>(null) }

    val scope = rememberCoroutineScope()

    // 在 Composable 作用域预解析错误文案（onClick 回调内不能调用 @Composable 的 stringResource）
    val urlRequiredError = stringResource(R.string.srv_error_url_required)
    val nameRequiredError = stringResource(R.string.srv_error_name_required)
    val urlInvalidError = stringResource(R.string.srv_error_url_invalid)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(if (isEdit) R.string.srv_edit_title else R.string.srv_add_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.srv_name_label)) },
                placeholder = { Text(stringResource(R.string.srv_name_placeholder)) },
                singleLine = true,
            )

            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.srv_url_label)) },
                placeholder = { Text(stringResource(R.string.srv_url_placeholder)) },
                singleLine = true,
            )

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        if (baseUrl.isBlank()) {
                            errorMessage = urlRequiredError
                            return@OutlinedButton
                        }
                        testing = true
                        testResult = null
                        scope.launch {
                            val result = onTestConnection(baseUrl)
                            testResult = result
                            testing = false
                        }
                    },
                    enabled = !testing,
                ) {
                    if (testing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.srv_testing))
                    } else {
                        Text(stringResource(R.string.srv_test_connection))
                    }
                }
                Spacer(Modifier.width(12.dp))
                testResult?.let { (ok, message) ->
                    Text(
                        text = message.asString(),
                        color = if (ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    errorMessage = null
                    val normalized = ServerUrl.normalize(baseUrl)
                    if (name.isBlank()) {
                        errorMessage = nameRequiredError
                        return@Button
                    }
                    if (normalized == null) {
                        errorMessage = urlInvalidError
                        return@Button
                    }
                    onSave(
                        ParserServerConfig(
                            id = existingServer?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.trim(),
                            baseUrl = normalized,
                            enabled = existingServer?.enabled ?: true,
                        ),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(stringResource(R.string.srv_save), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
