package com.chencohensolution.journal.ui.library

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.chencohensolution.journal.swap.CopyKeyKind

// Settings › Backup › Swap with a friend (SwapLab.kt's made-up swaps). See docs/screens/swap.md.

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=1500dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun Swaps() = Screen { SwapScreen(labSwaps, labSwapNow, passphraseKept = true, SwapActions(), back = {}) }

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=844dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SwapsNone() = Screen { SwapScreen(labSwapsEmpty, labSwapNow, passphraseKept = false, SwapActions(), back = {}) }

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=844dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SwapsUnsupported() = Screen { SwapScreen(labSwapsEmpty.copy(info = null, unsupported = true), labSwapNow, false, SwapActions(), back = {}) }

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=844dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SwapHereQuota() = Screen { SwapHereScreen(labSwapsEmpty, labSwapNow, SwapHereActions(), back = {}) }

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=900dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SwapHereQr() = Screen { SwapHereScreen(labSwapsEmpty.copy(invite = labInvite), labSwapNow, SwapHereActions(), back = {}) }

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=900dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SwapHereJoinedWithOffer() = Screen {
    val offer = labSwaps.offers.first().copy(name = "Dana")
    SwapHereScreen(labSwapsEmpty.copy(invite = labInvite.copy(joined = true), offers = listOf(offer)), labSwapNow, SwapHereActions(), back = {})
}

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=900dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SwapThereLink() = Screen { SwapThereScreen(labSwapsEmpty, labDraft(JoinStep.LINK), SwapThereActions(), back = {}) }

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=1100dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SwapThereProtect() = Screen { SwapThereScreen(labSwapsEmpty, labDraft(JoinStep.PROTECT), SwapThereActions(), back = {}) }

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=1000dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SwapThereSamePassphrase() = Screen {
    SwapThereScreen(labSwapsEmpty.copy(info = labSwapInfo.copy(copyKey = CopyKeyKind.PASSPHRASE)), labDraft(JoinStep.PROTECT), SwapThereActions(), back = {})
}

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=1000dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SwapThereWords() = Screen { SwapThereScreen(labSwapsEmpty, labDraft(JoinStep.WORDS), SwapThereActions(), back = {}) }

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=844dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SwapThereCheck() = Screen { SwapThereScreen(labSwapsEmpty, labDraft(JoinStep.CHECK), SwapThereActions(), back = {}) }

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=844dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SwapThereJoined() = Screen { SwapThereScreen(labSwapsEmpty.copy(outcome = labJoined), labDraft(JoinStep.RESULT), SwapThereActions(), back = {}) }

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=844dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SwapThereRefused() = Screen { SwapThereScreen(labSwapsEmpty.copy(outcome = labRefused), labDraft(JoinStep.RESULT), SwapThereActions(), back = {}) }

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=844dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SwapAcceptOffer() = Screen { SwapThereScreen(labSwaps, labDraft(JoinStep.PROTECT, labSwaps.offers.first()), SwapThereActions(), back = {}) }

@PreviewTest
@Preview(name = "dark", device = "spec:width=390dp,height=844dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun CopyPassphrase() = Screen { CopyPassphraseScreen(labWords.text, {}, {}, {}, {}, back = {}) }

@PreviewTest
@Preview(name = "dark-he", locale = "iw", device = "spec:width=390dp,height=1500dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SwapsHebrew() = Screen { SwapScreen(labSwaps, labSwapNow, passphraseKept = true, SwapActions(), back = {}) }

@PreviewTest
@Preview(name = "dark-he", locale = "iw", device = "spec:width=390dp,height=1000dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SwapThereWordsHebrew() = Screen { SwapThereScreen(labSwapsEmpty, labDraft(JoinStep.WORDS), SwapThereActions(), back = {}) }
