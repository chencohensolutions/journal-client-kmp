package com.chencohensolution.journal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohensolution.journal.R
import com.chencohensolution.journal.connection.ConnectionStatus
import com.chencohensolution.journal.connection.JournalController

/** Shown once this device has an identity; connects with it and shows whether that worked. */
@Composable
fun HomeScreen(controller: JournalController) {
    val state by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { controller.connect() }
    HomeContent(status = state.status, onRetry = controller::connect)
}

@Composable
private fun HomeContent(status: ConnectionStatus, onRetry: () -> Unit) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
            ConnectionStatusRow(status)
            if (status is ConnectionStatus.Failed || status is ConnectionStatus.Error) {
                OutlinedButton(onClick = onRetry) {
                    Text(stringResource(R.string.home_retry))
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusRow(status: ConnectionStatus) {
    val failed = status is ConnectionStatus.Failed || status is ConnectionStatus.Error
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (status is ConnectionStatus.Connecting) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        }
        Text(
            text = status.label(),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = when {
                failed -> MaterialTheme.colorScheme.error
                status == ConnectionStatus.Connected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun ConnectionStatus.label(): String = when (this) {
    ConnectionStatus.NotConnected -> stringResource(R.string.home_status_not_connected)
    is ConnectionStatus.Connecting -> stringResource(R.string.home_status_connecting)
    ConnectionStatus.Connected -> stringResource(R.string.home_status_connected)
    // 401: the server does not accept this device's certificate (unknown or revoked).
    is ConnectionStatus.Failed -> if (httpCode == 401) {
        stringResource(R.string.home_status_rejected)
    } else {
        stringResource(R.string.home_status_failed_http, httpCode)
    }
    is ConnectionStatus.Error -> stringResource(R.string.home_status_failed, message)
    ConnectionStatus.MissingServerUrl, ConnectionStatus.MissingIdentity -> stringResource(R.string.home_status_not_registered)
}

@Preview(showBackground = true)
@Composable
private fun HomeConnectedPreview() {
    JournalTheme {
        HomeContent(status = ConnectionStatus.Connected, onRetry = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeFailedPreview() {
    JournalTheme {
        HomeContent(status = ConnectionStatus.Failed(401), onRetry = {})
    }
}
