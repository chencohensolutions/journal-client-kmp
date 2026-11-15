package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.chencohensolution.journal.R

// Hidden and private are different things (the UX review, Nov 16, 2026): four ways a photo can be out of sight,
// lightest to strongest. Shown at the top of Settings › Hidden and private ("How these differ"), and once as a sheet
// the first time Tuck away or Mark private is used from the picking bar (SettingsState.hidingExplained).

/** The four lines: Show in the app · Hidden on all your devices · Tucked away · Private. */
@Composable
internal fun HidingExplainer(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Line(LibraryIcons.Phone, R.string.hiding_shown_title, R.string.hiding_shown_body)
        Line(LibraryIcons.CloudOff, R.string.hiding_everywhere_title, R.string.hiding_everywhere_body)
        Line(LibraryIcons.EyeOff, R.string.hiding_tucked_title, R.string.hiding_tucked_body)
        Line(LibraryIcons.Lock, R.string.hiding_private_title, R.string.hiding_private_body)
    }
}

@Composable
private fun Line(icon: ImageVector, title: Int, body: Int) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = LibraryColors.Muted, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(stringResource(title), color = LibraryColors.OnBackground, style = LibraryType.Body, fontWeight = LibraryType.RowTitle.fontWeight)
            Text(stringResource(body), color = LibraryColors.Muted, style = LibraryType.Caption)
        }
    }
}

/** "How these differ", folded: a card whose title opens the four lines. */
@Composable
internal fun HidingExplainerCard(initiallyOpen: Boolean = false) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    SettingsCard {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.hiding_explainer_title), color = LibraryColors.OnBackground, style = LibraryType.RowTitle, modifier = Modifier.weight(1f))
            Icon(LibraryIcons.ChevronDown, null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f))
        }
        if (open) {
            HidingExplainer(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
        }
    }
}

/** The explainer as a sheet, once, before the picking bar's first Tuck away or Mark private: Got it goes on. */
@Composable
internal fun HidingExplainerSheet(onContinue: () -> Unit, onDismiss: () -> Unit) = PageSheet(onDismiss) {
    Column(Modifier.fillMaxWidth().navigationBarRoom().padding(bottom = 16.dp)) {
        SheetTitle(stringResource(R.string.hiding_explainer_title))
        Text(
            stringResource(R.string.hiding_explainer_intro),
            color = LibraryColors.Muted,
            style = LibraryType.Body,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 14.dp),
        )
        HidingExplainer(Modifier.padding(horizontal = 24.dp))
        PrimaryButton(stringResource(R.string.hiding_explainer_continue), onContinue, Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp).fillMaxWidth())
    }
}

/** Whether the explainer was shown once from the picking bar, and how to say it was. Previews: already shown. */
internal class HidingExplained(val shown: Boolean, val markShown: () -> Unit)

internal val LocalHidingExplained = compositionLocalOf { HidingExplained(shown = true, markShown = {}) }
