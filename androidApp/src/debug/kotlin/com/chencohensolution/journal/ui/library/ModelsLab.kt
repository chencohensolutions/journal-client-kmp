package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.local.PhoneModelKind
import com.chencohensolution.journal.local.PhoneModelRow
import com.chencohensolution.journal.local.PhoneModelStatus
import com.chencohensolution.journal.local.PhoneModelsState

/** Made-up states of Settings › Models on this phone (chosen from option A, docs/screens/mockups/models/). */
internal object ModelsLab {
    private const val MB = 1_000_000L
    private val photos = PhoneModelRow(PhoneModelKind.PHOTOS, PhoneModelStatus.HERE, 10_900_000)
    private val words = PhoneModelRow(PhoneModelKind.WORDS, PhoneModelStatus.MISSING, 290 * MB, withBytes = 440 * MB)

    val usual = PhoneModelsState(listOf(photos, words), freeBytes = 48_000 * MB, wifiOnly = true)
    val downloading = usual.copy(
        models = listOf(photos, words.copy(status = PhoneModelStatus.DOWNLOADING, downloadedBytes = 112 * MB, downloadingBytes = 290 * MB)),
    )
    val waiting = usual.copy(
        models = listOf(photos.copy(status = PhoneModelStatus.WAITING_FOR_WIFI), words.copy(status = PhoneModelStatus.WAITING_FOR_WIFI)),
    )
    val offline = usual.copy(
        models = listOf(photos.copy(status = PhoneModelStatus.NOT_OFFERED, bytes = 0), words.copy(status = PhoneModelStatus.FAILED)),
        offline = true,
    )
}
