package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The library's buttons (docs/screens/style.md): one meaning per color.
// - PrimaryButton: ink (Inverse on OnInverse), the one main action of a screen or card.
// - SecondaryButton: outlined in ink, a second action, or a card's action under its body.
// - TextAction: ink semibold text, an action inside a row or a sheet ("Change", "Copy", "+ Tag").
// Teal (Accent) is only for "on" states and progress; red (Error) only for actions that remove or forget
// ([destructive]); orange (Warning) only for warnings, never for an action.

/** The one main action: filled ink, full pill. */
@Composable
internal fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    icon: ImageVector? = null,
    /** A small spinner before the text while the action runs. */
    busy: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) LibraryColors.Error else LibraryColors.Inverse,
            contentColor = if (destructive) Color.White else LibraryColors.OnInverse,
            disabledContainerColor = LibraryColors.Muted.copy(alpha = 0.25f),
            disabledContentColor = LibraryColors.Muted,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        modifier = modifier.heightIn(min = 44.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
        if (icon != null) Icon(icon, null, Modifier.padding(end = 8.dp).size(18.dp))
        Text(text, style = LibraryType.RowTitle, fontWeight = FontWeight.SemiBold)
    }
}

/** A second action: outlined in ink, full pill. */
@Composable
internal fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, if (enabled) LibraryColors.OnBackground.copy(alpha = 0.6f) else LibraryColors.Muted.copy(alpha = 0.3f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = LibraryColors.OnBackground, disabledContentColor = LibraryColors.Muted),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        modifier = modifier.heightIn(min = 44.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.padding(end = 8.dp).size(18.dp))
        Text(text, style = LibraryType.RowTitle, fontWeight = FontWeight.SemiBold)
    }
}

/** An action as text: ink semibold ([destructive]: red), with an optional icon before it. */
@Composable
internal fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    icon: ImageVector? = null,
    color: Color = if (destructive) LibraryColors.Error else LibraryColors.OnBackground,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        val tint = if (enabled) color else LibraryColors.Muted
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.padding(end = 6.dp).size(16.dp))
        Text(text, color = tint, fontWeight = FontWeight.SemiBold)
    }
}
