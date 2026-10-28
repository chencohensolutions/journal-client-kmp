package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.server.CopyRunning

// Option B's time left, as shared logic would give it (CopyRunning): the pass's rate so far, in bytes since it
// started, applied to what is left.

/** Milliseconds left at the pass's rate so far; null while counting or before a byte was written. */
internal fun CopyRunning.remainingMillisB(nowMillis: Long): Long? {
    if (counting || doneBytes <= 0 || totalBytes <= 0) return null
    val elapsed = (nowMillis - startedAtMillis).coerceAtLeast(1)
    return elapsed * (totalBytes - doneBytes).coerceAtLeast(0) / doneBytes
}
