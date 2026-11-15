package com.chencohensolution.journal.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.chencohensolution.journal.enrollment.EnrollmentState
import com.chencohensolution.journal.enrollment.EnrollmentStep
import com.chencohensolution.journal.ui.library.PHONE

// Enrollment (registering this phone with the server): the code step and the confirm step. See docs/screens/enrollment.md.

private fun state(step: EnrollmentStep) = EnrollmentState("https://192.168.50.2:8080", "K7QM-4XPD", "Pixel 9 Pro", step)

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Preview(name = "light-he", locale = "iw", device = PHONE, showBackground = true)
@Composable
fun EnrollCode() = JournalTheme { EnrollmentContent(state(EnrollmentStep.EnterCode()), EnrollmentActions()) }

@PreviewTest
@Preview(name = "light", device = PHONE, showBackground = true)
@Composable
fun EnrollConfirm() = JournalTheme { EnrollmentContent(state(EnrollmentStep.Confirm("hen", 8)), EnrollmentActions()) }
