package com.chencohensolution.journal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohensolution.journal.R
import com.chencohensolution.journal.enrollment.EnrollmentController
import com.chencohensolution.journal.enrollment.EnrollmentError
import com.chencohensolution.journal.enrollment.EnrollmentState
import com.chencohensolution.journal.enrollment.EnrollmentStep
import com.chencohensolution.journal.enrollment.KeyProtection
import com.chencohensolution.journal.enrollment.PasteResult
import com.chencohensolution.journal.enrollment.RegistrationProgress
import com.chencohensolution.journal.ui.library.pastedText
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** Registers this device with the server: enrollment code → hardware key → client certificate. */
@Composable
fun EnrollmentWizard(
    controller: EnrollmentController,
    onOpenJournal: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    BackHandler(enabled = state.step !is EnrollmentStep.Welcome && state.step !is EnrollmentStep.Enrolled) {
        controller.back()
    }
    EnrollmentContent(
        state = state,
        actions = EnrollmentActions(
            start = controller::start,
            back = controller::back,
            updateServerAddress = controller::updateServerAddress,
            updateCode = controller::updateCode,
            updateDeviceName = controller::updateDeviceName,
            checkCode = controller::checkCode,
            // Google's scanner screen, from Play services: it needs no camera permission.
            scan = {
                val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(context, options).startScan()
                    .addOnSuccessListener { barcode -> barcode.rawValue?.let(controller::openScanned) }
                    .addOnFailureListener { Toast.makeText(context, R.string.enroll_scan_failed, Toast.LENGTH_LONG).show() }
            },
            // Read only when tapped: Android lets a focused app read the clipboard and says so in its own note.
            paste = {
                if (controller.openPasted(pastedText(context)) == PasteResult.NOTHING) {
                    Toast.makeText(context, R.string.enroll_paste_nothing, Toast.LENGTH_LONG).show()
                }
            },
            register = controller::register,
            openJournal = onOpenJournal,
        ),
    )
}

private class EnrollmentActions(
    val start: () -> Unit = {},
    val back: () -> Unit = {},
    val updateServerAddress: (String) -> Unit = {},
    val updateCode: (String) -> Unit = {},
    val updateDeviceName: (String) -> Unit = {},
    val checkCode: () -> Unit = {},
    val scan: () -> Unit = {},
    val paste: () -> Unit = {},
    val register: () -> Unit = {},
    val openJournal: () -> Unit = {},
)

@Composable
private fun EnrollmentContent(state: EnrollmentState, actions: EnrollmentActions) {
    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            when (val step = state.step) {
                EnrollmentStep.Welcome -> WelcomeStep(actions)
                is EnrollmentStep.EnterCode -> EnterCodeStep(state, step, actions)
                is EnrollmentStep.Confirm -> ConfirmStep(state, step, actions)
                is EnrollmentStep.Registering -> RegisteringStep(state, step, actions)
                is EnrollmentStep.Enrolled -> EnrolledStep(step, actions)
                is EnrollmentStep.Failed -> FailedStep(state, step, actions)
            }
        }
    }
}

@Composable
private fun ColumnScope.WelcomeStep(actions: EnrollmentActions) {
    StepBody {
        Spacer(Modifier.height(40.dp))
        Badge(Icons.Shield, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        Title(stringResource(R.string.enroll_welcome_title), Modifier.padding(top = 32.dp))
        Body(stringResource(R.string.enroll_welcome_body), Modifier.padding(top = 16.dp))
        Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            NumberedItem(1, R.string.enroll_welcome_step1_title, R.string.enroll_welcome_step1_body)
            NumberedItem(2, R.string.enroll_welcome_step2_title, R.string.enroll_welcome_step2_body)
            NumberedItem(3, R.string.enroll_welcome_step3_title, R.string.enroll_welcome_step3_body)
        }
    }
    BottomActions {
        PrimaryButton(stringResource(R.string.enroll_get_started), onClick = actions.start)
    }
}

@Composable
private fun ColumnScope.EnterCodeStep(state: EnrollmentState, step: EnrollmentStep.EnterCode, actions: EnrollmentActions) {
    WizardHeader(stepNumber = 1, label = R.string.enroll_step_connect, onBack = actions.back)
    StepBody {
        Title(stringResource(R.string.enroll_code_title), Modifier.padding(top = 24.dp))
        Body(stringResource(R.string.enroll_code_body), Modifier.padding(top = 8.dp))
        // One above the other: side by side, "Scan QR code" wraps on a phone.
        Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions.scan, enabled = !step.checking, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Icons.QrCode, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.enroll_scan), style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(onClick = actions.paste, enabled = !step.checking, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Icon(Icons.Paste, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.enroll_paste), style = MaterialTheme.typography.titleMedium)
            }
        }
        OutlinedTextField(
            value = state.serverAddress,
            onValueChange = actions.updateServerAddress,
            label = { Text(stringResource(R.string.enroll_server_address)) },
            supportingText = { Text(stringResource(R.string.enroll_server_address_help)) },
            isError = step.error == EnrollmentError.MissingServerAddress,
            singleLine = true,
            enabled = !step.checking,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        val codeError = step.error?.takeIf { it != EnrollmentError.MissingServerAddress }
        OutlinedTextField(
            value = state.code,
            onValueChange = actions.updateCode,
            label = { Text(stringResource(R.string.enroll_code)) },
            supportingText = { Text(codeError?.let { errorMessage(it) } ?: stringResource(R.string.enroll_code_help)) },
            isError = codeError != null,
            singleLine = true,
            enabled = !step.checking,
            textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 3.sp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { actions.checkCode() }),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
    BottomActions {
        PrimaryButton(stringResource(R.string.enroll_continue), onClick = actions.checkCode, loading = step.checking)
    }
}

@Composable
private fun ColumnScope.ConfirmStep(state: EnrollmentState, step: EnrollmentStep.Confirm, actions: EnrollmentActions) {
    WizardHeader(stepNumber = 2, label = R.string.enroll_step_confirm, onBack = actions.back)
    StepBody {
        Title(stringResource(R.string.enroll_confirm_title), Modifier.padding(top = 24.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        ) {
            Column {
                DetailRow(Icons.Server, stringResource(R.string.enroll_server), state.serverAddress.removePrefix("https://"),
                    state.serverId?.let { stringResource(R.string.enroll_server_verified_id, shortServerId(it)) }
                        ?: stringResource(R.string.enroll_server_verified))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                DetailRow(Icons.Person, stringResource(R.string.enroll_account), step.user,
                    stringResource(R.string.enroll_code_expires, state.code.uppercase(), step.minutesLeft), highlight = false)
            }
        }
        OutlinedTextField(
            value = state.deviceName,
            onValueChange = actions.updateDeviceName,
            label = { Text(stringResource(R.string.enroll_device_name)) },
            supportingText = { Text(stringResource(R.string.enroll_device_name_help)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.enroll_key_notice), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    BottomActions {
        PrimaryButton(stringResource(R.string.enroll_register), onClick = actions.register)
    }
}

@Composable
private fun ColumnScope.RegisteringStep(state: EnrollmentState, step: EnrollmentStep.Registering, actions: EnrollmentActions) {
    WizardHeader(stepNumber = 3, label = R.string.enroll_step_register, onBack = null)
    StepBody {
        Title(stringResource(R.string.enroll_registering_title, state.deviceName.trim()), Modifier.padding(top = 24.dp))
        Body(stringResource(R.string.enroll_registering_body), Modifier.padding(top = 8.dp))
        Column(Modifier.padding(top = 32.dp)) {
            val items = listOf(
                R.string.enroll_progress_key to R.string.enroll_progress_key_detail,
                R.string.enroll_progress_request to R.string.enroll_progress_request_detail,
                R.string.enroll_progress_certificate to R.string.enroll_progress_certificate_detail,
                R.string.enroll_progress_test to R.string.enroll_progress_test_detail,
            )
            items.forEachIndexed { index, (title, detail) ->
                ProgressRow(
                    title = stringResource(title),
                    detail = stringResource(detail),
                    state = index.compareTo(step.progress.ordinal),
                    last = index == RegistrationProgress.entries.lastIndex,
                )
            }
        }
    }
    BottomActions {
        TextButton(onClick = actions.back, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(stringResource(R.string.enroll_cancel))
        }
    }
}

@Composable
private fun ColumnScope.EnrolledStep(step: EnrollmentStep.Enrolled, actions: EnrollmentActions) {
    StepBody(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(64.dp))
        Box(
            Modifier.size(96.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(48.dp))
        }
        Title(stringResource(R.string.enroll_done_title), Modifier.padding(top = 28.dp), TextAlign.Center)
        Body(stringResource(R.string.enroll_done_body, step.user), Modifier.padding(top = 8.dp), TextAlign.Center)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SummaryRow(R.string.enroll_server, step.serverAddress.removePrefix("https://"))
                SummaryRow(R.string.enroll_device, step.deviceName)
                SummaryRow(R.string.enroll_key, stringResource(step.keyProtection.label()))
                SummaryRow(R.string.enroll_valid_until, step.validUntil, last = true)
            }
        }
    }
    BottomActions {
        PrimaryButton(stringResource(R.string.enroll_open_journal), onClick = actions.openJournal)
    }
}

@Composable
private fun ColumnScope.FailedStep(state: EnrollmentState, step: EnrollmentStep.Failed, actions: EnrollmentActions) {
    StepBody {
        Spacer(Modifier.height(64.dp))
        Badge(Icons.Error, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        val title = if (step.error == EnrollmentError.InvalidCode) R.string.enroll_failed_code_title else R.string.enroll_failed_title
        Title(stringResource(title), Modifier.padding(top = 28.dp))
        Body(
            if (step.error == EnrollmentError.InvalidCode) {
                stringResource(R.string.enroll_failed_code_body, state.code.uppercase())
            } else {
                errorMessage(step.error)
            },
            Modifier.padding(top = 12.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.enroll_failed_new_code), style = MaterialTheme.typography.titleSmall)
                Text(
                    "$ journal-server enroll-code",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFDCE5DE),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF121513), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        Text(
            stringResource(R.string.enroll_failed_key_deleted),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, start = 4.dp),
        )
    }
    BottomActions {
        PrimaryButton(stringResource(R.string.enroll_new_code), onClick = actions.back)
    }
}

@Composable
private fun errorMessage(error: EnrollmentError): String = when (error) {
    EnrollmentError.MissingServerAddress -> stringResource(R.string.enroll_error_missing_address)
    EnrollmentError.MissingCode -> stringResource(R.string.enroll_error_missing_code)
    EnrollmentError.InvalidCode -> stringResource(R.string.enroll_error_invalid_code)
    EnrollmentError.RateLimited -> stringResource(R.string.enroll_error_rate_limited)
    EnrollmentError.RequestRejected -> stringResource(R.string.enroll_error_request_rejected)
    is EnrollmentError.Unreachable -> stringResource(R.string.enroll_error_unreachable, error.message)
    is EnrollmentError.KeyStoreFailed -> stringResource(R.string.enroll_error_key_store, error.message)
    is EnrollmentError.ConnectionTestFailed -> stringResource(R.string.enroll_error_connection_test, error.message)
}

private fun KeyProtection.label(): Int = when (this) {
    KeyProtection.STRONGBOX -> R.string.enroll_key_strongbox
    KeyProtection.TEE -> R.string.enroll_key_tee
    KeyProtection.SECURE_ENCLAVE -> R.string.enroll_key_secure_enclave
    KeyProtection.SOFTWARE -> R.string.enroll_key_software
}

// --- Building blocks ----------------------------------------------------------

@Composable
private fun ColumnScope.StepBody(
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

@Composable
private fun BottomActions(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

@Composable
private fun WizardHeader(stepNumber: Int, label: Int, onBack: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(64.dp).padding(start = if (onBack == null) 24.dp else 4.dp),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.ArrowBack, contentDescription = stringResource(R.string.enroll_back))
            }
        }
        Text(
            stringResource(R.string.enroll_step_of, stepNumber, stringResource(label)),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(
                        if (index < stepNumber) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(2.dp),
                    )
            )
        }
    }
}

@Composable
private fun Title(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = textAlign, modifier = modifier)
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
        modifier = modifier,
    )
}

@Composable
private fun Badge(icon: ImageVector, container: Color, content: Color) {
    Box(Modifier.size(88.dp).background(container, RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(44.dp))
    }
}

@Composable
private fun NumberedItem(number: Int, title: Int, body: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, loading: Boolean = false) {
    Button(onClick = onClick, enabled = !loading, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        if (loading) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, label: String, value: String, detail: String, highlight: Boolean = true) {
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryRow(label: Int, value: String, last: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
    if (!last) HorizontalDivider()
}

/** [state] < 0: done, 0: in progress, > 0: pending. */
@Composable
private fun ProgressRow(title: String, detail: String, state: Int, last: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(32.dp)) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                when {
                    state < 0 -> Box(
                        Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                    }
                    state == 0 -> CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                    else -> Box(Modifier.size(20.dp).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                }
            }
            if (!last) {
                Box(Modifier.padding(vertical = 4.dp).width(2.dp).height(28.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
        Column(Modifier.padding(top = 4.dp).alpha(if (state > 0) 0.55f else 1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Line icons used by the wizard (Material icons are not a dependency of this app). */
private object Icons {
    /** [mirrored] icons point along the reading direction, so they flip in right-to-left layouts (Hebrew). */
    private fun icon(name: String, vararg paths: String, mirrored: Boolean = false) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirrored).apply {
            paths.forEach { data ->
                addPath(
                    pathData = PathParser().parsePathString(data).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val ArrowBack = icon("ArrowBack", "M19 12H5", "M11 6l-6 6 6 6", mirrored = true)
    val Check = icon("Check", "M5 12l5 5 9-10")
    val Shield = icon("Shield", "M12 3l7 3v5c0 4.5-3 8.3-7 10-4-1.7-7-5.5-7-10V6z", "M12 9a2 2 0 1 0 0.01 0", "M12 13v3")
    val Lock = icon("Lock", "M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z", "M8 11V8a4 4 0 0 1 8 0v3")
    val Server = icon("Server", "M5 4h14a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M5 13h14a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z", "M7 7.5h0.01", "M7 16.5h0.01")
    val Person = icon("Person", "M12 4a4 4 0 1 0 0.01 0", "M4 20c1.5-3.5 4.5-5 8-5s6.5 1.5 8 5")
    val QrCode = icon(
        "QrCode",
        "M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z",
        "M14 14h2v2h-2z", "M18 18h2v2h-2z", "M14 18v2", "M18 14h2",
    )
    val Paste = icon(
        "Paste",
        "M9 4h6v3H9z", "M9 5.5H7a2 2 0 0 0-2 2V19a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7.5a2 2 0 0 0-2-2h-2",
        "M9 12h6", "M9 16h4",
    )
    val Error = icon("Error", "M12 3a9 9 0 1 0 0.01 0", "M12 7.5v5.5", "M12 16.5h0.01")
}

@Preview(showBackground = true)
@Composable
private fun ConfirmPreview() {
    JournalTheme {
        EnrollmentContent(
            state = EnrollmentState("https://192.168.50.2:8080", "K7QM-4XPD", "Pixel 9 Pro", EnrollmentStep.Confirm("hen", 8)),
            actions = EnrollmentActions(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RegisteringPreview() {
    JournalTheme {
        EnrollmentContent(
            state = EnrollmentState("https://192.168.50.2:8080", "K7QM-4XPD", "Pixel 9 Pro", EnrollmentStep.Registering(RegistrationProgress.SENDING_REQUEST)),
            actions = EnrollmentActions(),
        )
    }
}

/** The first two groups of a server id, "T6DNBAM-IJR6WLU-…": enough to recognize it. */
internal fun shortServerId(id: String): String = id.split("-").take(2).joinToString("-") + "-…"
