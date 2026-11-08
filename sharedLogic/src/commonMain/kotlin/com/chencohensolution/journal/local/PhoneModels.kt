package com.chencohensolution.journal.local

import com.chencohensolution.journal.connection.ObservationHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The models Settings › Models on this phone lists, in its order. */
enum class PhoneModelKind {
    /** The photo embedder (MobileNet-V3 large on Android): sets of similar photos in Moments. */
    PHOTOS,

    /** Search by words' text model, which comes with its index in On device search. */
    WORDS,
}

enum class PhoneModelStatus { HERE, MISSING, DOWNLOADING, WAITING_FOR_WIFI, NOT_OFFERED, FAILED }

/**
 * One model on Settings › Models on this phone: how it stands, its size ([bytes]), what a download has so far
 * ([downloadedBytes] of [downloadingBytes]), and for Search by words the index it comes with ([withBytes]).
 */
data class PhoneModelRow(
    val kind: PhoneModelKind,
    val status: PhoneModelStatus,
    val bytes: Long,
    val downloadedBytes: Long = 0,
    val downloadingBytes: Long = 0,
    val withBytes: Long = 0,
)

/** Settings › Models on this phone. */
data class PhoneModelsState(
    val models: List<PhoneModelRow> = emptyList(),
    /** Only on Wi-Fi: On device search's setting, one for every download. */
    val wifiOnly: Boolean = true,
    /** The server couldn't be reached: nothing downloads, what's here still works. */
    val offline: Boolean = false,
    val freeBytes: Long = 0,
) {
    val here: Int get() = models.count { it.status == PhoneModelStatus.HERE }

    /** What Download all fetches: the models not here that can be downloaded. */
    val missing: List<PhoneModelRow>
        get() = models.filter { it.status == PhoneModelStatus.MISSING || it.status == PhoneModelStatus.FAILED || it.status == PhoneModelStatus.WAITING_FOR_WIFI }

    val missingBytes: Long get() = missing.sumOf { it.bytes + it.withBytes }
    val keptBytes: Long get() = models.filter { it.status == PhoneModelStatus.HERE }.sumOf { it.bytes }
}

/**
 * Settings › Models on this phone: every model the app runs here, from the one place that downloads each — [photos]
 * ([PhoneModel]) and Search by words' model in [indexes] ([LocalIndexes], whose switch On device search shows) — so
 * both screens always say the same. Download, Pause, Try again and Delete drive those downloads; Only on Wi-Fi is
 * On device search's setting, which [photos] follows too. A null source isn't listed (iOS has no photo model; a server
 * without local indexes has no Search by words).
 */
class PhoneModels(
    private val photos: PhoneModel?,
    private val indexes: LocalIndexes?,
    /** Whether the server lets phones keep its indexes (Search by words is listed). */
    private val searchOffered: StateFlow<Boolean> = MutableStateFlow(indexes != null),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val scope = scope

    val state: StateFlow<PhoneModelsState> = combine(
        photos?.state ?: MutableStateFlow(null),
        indexes?.state ?: MutableStateFlow(null),
        searchOffered,
    ) { photo, search, offered -> build(photo, search?.takeIf { offered }) }
        .stateIn(scope, SharingStarted.Eagerly, build(photos?.state?.value, indexes?.state?.value?.takeIf { searchOffered.value }))

    /** Same as `state.value`, typed for Swift. */
    val currentState: PhoneModelsState get() = state.value

    /** The page opened: asks the server what it offers now (sizes, whether it has each). */
    fun refresh() {
        photos?.check()
        indexes?.refresh()
    }

    /** Download ([force]: Download now while waiting for Wi-Fi, or Try again whatever the network). */
    fun download(kind: PhoneModelKind, force: Boolean = false) {
        when (kind) {
            PhoneModelKind.PHOTOS -> photos?.download(force)
            PhoneModelKind.WORDS -> indexes?.let { if (force) it.updateNow() else it.set(PhoneIndexKind.WORDS, true) }
        }
    }

    fun pause(kind: PhoneModelKind) {
        when (kind) {
            PhoneModelKind.PHOTOS -> photos?.pause()
            PhoneModelKind.WORDS -> indexes?.pause()
        }
    }

    /** Delete: for Search by words, turning it off in On device search (its index goes with its model). */
    fun delete(kind: PhoneModelKind) {
        when (kind) {
            PhoneModelKind.PHOTOS -> photos?.delete()
            PhoneModelKind.WORDS -> indexes?.set(PhoneIndexKind.WORDS, false)
        }
    }

    /** Download all: every model listed that isn't here. */
    fun downloadAll() {
        state.value.missing.forEach { download(it.kind) }
    }

    fun setWifiOnly(on: Boolean) {
        indexes?.setWifiOnly(on)
    }

    private fun build(photo: PhoneModelState?, search: PhoneSearchState?): PhoneModelsState {
        val rows = buildList {
            if (photo != null) add(photoRow(photo))
            if (search != null) add(wordsRow(search))
        }
        return PhoneModelsState(
            models = rows,
            wifiOnly = search?.wifiOnly ?: indexes?.currentState?.wifiOnly ?: true,
            offline = photo?.unreachable == true,
            freeBytes = search?.freeBytes ?: indexes?.currentState?.freeBytes ?: 0,
        )
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (PhoneModelsState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() = scope.cancel()
}

/** The photo model's row. */
internal fun photoRow(model: PhoneModelState): PhoneModelRow {
    val bytes = model.bytes ?: 0
    val status = when {
        model.here -> PhoneModelStatus.HERE
        model.downloading -> PhoneModelStatus.DOWNLOADING
        model.waitingForWifi -> PhoneModelStatus.WAITING_FOR_WIFI
        model.offered == false -> PhoneModelStatus.NOT_OFFERED
        model.failed -> PhoneModelStatus.FAILED
        else -> PhoneModelStatus.MISSING
    }
    return PhoneModelRow(PhoneModelKind.PHOTOS, status, bytes, downloadedBytes = model.downloaded ?: 0, downloadingBytes = bytes)
}

/** Search by words' model's row, from On device search's state. */
internal fun wordsRow(search: PhoneSearchState): PhoneModelRow {
    val index = search.indexes.first { it.kind == PhoneIndexKind.WORDS }
    val download = search.download?.takeIf { it.kind == PhoneIndexKind.WORDS }
    val status = when {
        search.wordsModelHere -> PhoneModelStatus.HERE
        download != null -> PhoneModelStatus.DOWNLOADING
        index.on && search.waitingForWifi -> PhoneModelStatus.WAITING_FOR_WIFI
        search.loaded && (!index.available || index.modelBytes == 0L) -> PhoneModelStatus.NOT_OFFERED
        index.on && search.error != null -> PhoneModelStatus.FAILED
        else -> PhoneModelStatus.MISSING
    }
    return PhoneModelRow(
        PhoneModelKind.WORDS,
        status,
        bytes = index.modelBytes,
        downloadedBytes = download?.doneBytes ?: 0,
        downloadingBytes = download?.totalBytes ?: 0,
        withBytes = if (status == PhoneModelStatus.HERE) 0 else index.bytes,
    )
}
