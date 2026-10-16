package com.chencohensolution.journal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohensolution.journal.R
import com.chencohensolution.journal.connection.ConnectionStatus
import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.connection.JournalState

@Composable
fun JournalScreen(controller: JournalController, onSelectIdentity: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    JournalContent(
        state = state,
        onServerUrlChange = controller::updateServerUrl,
        onSelectIdentity = onSelectIdentity,
        onConnect = controller::connect,
    )
}

@Composable
private fun JournalContent(
    state: JournalState,
    onServerUrlChange: (String) -> Unit,
    onSelectIdentity: () -> Unit,
    onConnect: () -> Unit,
) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text(stringResource(R.string.server_url_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.identityAlias?.let { stringResource(R.string.identity_selected, it) }
                        ?: stringResource(R.string.identity_none),
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onSelectIdentity) {
                    Text(stringResource(R.string.btn_select_identity))
                }
            }

            Button(
                onClick = onConnect,
                enabled = state.status !is ConnectionStatus.Connecting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_connect))
            }

            Text(
                text = state.status.label(),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ConnectionStatus.label(): String = when (this) {
    ConnectionStatus.NotConnected -> stringResource(R.string.status_not_connected)
    ConnectionStatus.MissingServerUrl -> stringResource(R.string.status_error_url)
    ConnectionStatus.MissingIdentity -> stringResource(R.string.status_error_identity)
    is ConnectionStatus.Connecting ->
        stringResource(R.string.status_connecting, startedAt, trustedCaNames.joinToString())
    ConnectionStatus.Connected -> stringResource(R.string.status_connected)
    is ConnectionStatus.Failed -> stringResource(R.string.status_failed_http, httpCode)
    is ConnectionStatus.Error -> stringResource(R.string.status_error_prefix, message)
}

@Preview(showBackground = true)
@Composable
private fun JournalContentPreview() {
    JournalTheme {
        JournalContent(
            state = JournalState(
                serverUrl = "https://192.168.50.2:8080/graphql",
                identityAlias = "hen",
                status = ConnectionStatus.Connected,
            ),
            onServerUrlChange = {},
            onSelectIdentity = {},
            onConnect = {},
        )
    }
}
