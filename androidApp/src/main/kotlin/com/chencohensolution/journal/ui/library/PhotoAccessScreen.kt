package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.ui.JournalTheme

/** Asks for the photo library once, before the home screen. See docs/screens/photo-access.md. */
@Composable
internal fun PhotoAccessScreen(onAllow: () -> Unit, onNotNow: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .systemBarsPadding()
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                PhotoStack(Modifier.fillMaxWidth().height(240.dp))
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.library_access_title), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.library_access_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(28.dp))
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Point(LibraryIcons.Calendar, R.string.library_access_days_title, R.string.library_access_days_body)
                    Point(LibraryIcons.Lock, R.string.library_access_backup_title, R.string.library_access_backup_body)
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text(stringResource(R.string.library_access_allow))
            }
            TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.library_access_not_now))
            }
            Text(
                stringResource(R.string.library_access_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

/** Three tilted photo cards with a backup badge: what the app does, before any words. */
@Composable
private fun PhotoStack(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier.offset(x = (-62).dp).rotate(-9f).size(150.dp, 186.dp)
                .shadow(8.dp, RoundedCornerShape(18.dp)).background(colors.secondaryContainer, RoundedCornerShape(18.dp)),
        )
        Box(
            Modifier.offset(x = 62.dp).rotate(8f).size(150.dp, 186.dp)
                .shadow(8.dp, RoundedCornerShape(18.dp)).background(colors.tertiaryContainer, RoundedCornerShape(18.dp)),
        )
        Box(
            Modifier.size(164.dp, 204.dp).shadow(14.dp, RoundedCornerShape(20.dp))
                .background(colors.surfaceContainerLowest, RoundedCornerShape(20.dp)).padding(10.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().height(138.dp).background(colors.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(LibraryIcons.Photo, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(40.dp))
            }
        }
        Box(
            Modifier.offset(x = 72.dp, y = 92.dp).size(44.dp)
                .background(colors.surface, CircleShape).padding(4.dp)
                .background(colors.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(LibraryIcons.CloudUp, null, tint = colors.onPrimary, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun Point(icon: ImageVector, title: Int, body: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PhotoAccessPreview() {
    JournalTheme {
        PhotoAccessScreen(onAllow = {}, onNotNow = {})
    }
}
