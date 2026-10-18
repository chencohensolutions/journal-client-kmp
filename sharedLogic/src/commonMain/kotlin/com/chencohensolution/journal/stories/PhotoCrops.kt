package com.chencohensolution.journal.stories

/*
 * Crops are only for looking: the photo itself is never changed. A photo can have three, kept in its note file
 * (media/<id>.json): how it shows in Moments (any shape), and in the viewer with the phone upright or sideways (the
 * screen's shape). See docs/screens/crop.md.
 */

/** Which crop: Moments (any shape), or the viewer with the phone upright (portrait) or sideways (landscape). */
enum class CropKind { MOMENTS, PORTRAIT, LANDSCAPE }

/**
 * A crop as fractions of the upright photo (after its rotation flag): [x], [y] its top left corner, [w], [h] its size.
 * Independent of resolution, so it fits the thumbnail, the full photo and a server copy alike. [aspect] is the screen
 * shape (width / height) a portrait or landscape crop was made for, so another screen can adapt it ([fittedTo]).
 */
data class CropRect(val x: Float, val y: Float, val w: Float, val h: Float, val aspect: Float? = null) {
    /** Its shape (width / height) on a photo of [photoRatio]. */
    fun ratioOn(photoRatio: Float): Float = photoRatio * w / h

    private val centerX get() = x + w / 2
    private val centerY get() = y + h / 2

    /** Moved by [dx], [dy] (fractions of the photo), staying on it. */
    fun moved(dx: Float, dy: Float): CropRect = copy(x = (x + dx).coerceIn(0f, 1 - w), y = (y + dy).coerceIn(0f, 1 - h))

    /**
     * Made [factor] times as large around its center, keeping its shape; never smaller than [MIN_CROP_SIZE] on a side
     * nor larger than the photo.
     */
    fun scaled(factor: Float): CropRect {
        val most = minOf(1f / w, 1f / h)
        val least = MIN_CROP_SIZE / minOf(w, h)
        val f = factor.coerceIn(minOf(least, 1f), maxOf(most, 1f))
        return centeredOn(centerX, centerY, w * f, h * f)
    }

    /**
     * One edge or corner dragged by [dx], [dy] (fractions of the photo); the opposite side stays. With [lockedRatio]
     * (width / height on the photo) a corner keeps that shape; without it the shape stays between [MIN_CROP_RATIO]
     * and [MAX_CROP_RATIO].
     */
    fun resized(handle: CropHandle, dx: Float, dy: Float, photoRatio: Float, lockedRatio: Float? = null): CropRect {
        var left = x
        var top = y
        var right = x + w
        var bottom = y + h
        if (handle.left) left = (left + dx).coerceIn(0f, right - MIN_CROP_SIZE)
        if (handle.right) right = (right + dx).coerceIn(left + MIN_CROP_SIZE, 1f)
        if (handle.top) top = (top + dy).coerceIn(0f, bottom - MIN_CROP_SIZE)
        if (handle.bottom) bottom = (bottom + dy).coerceIn(top + MIN_CROP_SIZE, 1f)
        if (lockedRatio != null) {
            // The width leads; the height follows the shape, from the side that stays.
            val fraction = lockedRatio / photoRatio
            var width = right - left
            var height = width / fraction
            val room = if (handle.top) bottom else 1 - top
            if (height > room) {
                height = room
                width = height * fraction
            }
            if (handle.left) left = right - width else right = left + width
            if (handle.top) top = bottom - height else bottom = top + height
            return CropRect(left, top, right - left, bottom - top, aspect)
        }
        val shaped = CropRect(left, top, right - left, bottom - top, aspect)
        val ratio = shaped.ratioOn(photoRatio)
        return when {
            ratio > MAX_CROP_RATIO -> if (handle.top || handle.bottom) shaped.withHeightFrom(handle, shaped.w * photoRatio / MAX_CROP_RATIO) else shaped.withWidthFrom(handle, shaped.h * MAX_CROP_RATIO / photoRatio)
            ratio < MIN_CROP_RATIO -> if (handle.left || handle.right) shaped.withWidthFrom(handle, shaped.h * MIN_CROP_RATIO / photoRatio) else shaped.withHeightFrom(handle, shaped.w * photoRatio / MIN_CROP_RATIO)
            else -> shaped
        }
    }

    private fun withWidthFrom(handle: CropHandle, width: Float): CropRect {
        val newWidth = width.coerceAtMost(1f)
        val left = if (handle.left) (x + w - newWidth) else x
        return copy(x = left.coerceIn(0f, 1 - newWidth), w = newWidth)
    }

    private fun withHeightFrom(handle: CropHandle, height: Float): CropRect {
        val newHeight = height.coerceAtMost(1f)
        val top = if (handle.top) (y + h - newHeight) else y
        return copy(y = top.coerceIn(0f, 1 - newHeight), h = newHeight)
    }

    private fun centeredOn(cx: Float, cy: Float, width: Float, height: Float): CropRect {
        val w = width.coerceAtMost(1f)
        val h = height.coerceAtMost(1f)
        return CropRect((cx - w / 2).coerceIn(0f, 1 - w), (cy - h / 2).coerceIn(0f, 1 - h), w, h, aspect)
    }

    /**
     * Adapted to a screen of [screenRatio] (width / height) when it was made for another: same center, the same
     * height upright (width sideways), the other side following the new shape, and still on the photo.
     */
    fun fittedTo(screenRatio: Float, photoRatio: Float): CropRect {
        if (aspect == null || kotlin.math.abs(aspect - screenRatio) < 0.001f) return this
        val fraction = screenRatio / photoRatio
        var width: Float
        var height: Float
        if (screenRatio < 1f) {
            height = h
            width = height * fraction
        } else {
            width = w
            height = width / fraction
        }
        if (width > 1f) {
            width = 1f
            height = width / fraction
        }
        if (height > 1f) {
            height = 1f
            width = height * fraction
        }
        return centeredOn(centerX, centerY, width, height).copy(aspect = screenRatio)
    }

    companion object {
        /** The whole photo. */
        val WHOLE = CropRect(0f, 0f, 1f, 1f)

        /**
         * The largest crop of [ratio] (width / height) on a photo of [photoRatio], centered on [around]'s center (a
         * crop already made, so the next one starts on the same spot) or on the photo's.
         */
        fun largest(ratio: Float, photoRatio: Float, around: CropRect? = null): CropRect {
            val fraction = ratio / photoRatio
            val w = if (fraction <= 1f) fraction else 1f
            val h = if (fraction <= 1f) 1f else 1f / fraction
            return WHOLE.centeredOn(around?.centerX ?: 0.5f, around?.centerY ?: 0.5f, w, h).copy(aspect = ratio)
        }
    }
}

/** What is dragged on the crop frame: a corner or the middle of a side. */
enum class CropHandle(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean) {
    TOP_LEFT(true, true, false, false),
    TOP(false, true, false, false),
    TOP_RIGHT(false, true, true, false),
    RIGHT(false, false, true, false),
    BOTTOM_RIGHT(false, false, true, true),
    BOTTOM(false, false, false, true),
    BOTTOM_LEFT(true, false, false, true),
    LEFT(true, false, false, false),
    ;

    val isCorner: Boolean get() = (left || right) && (top || bottom)
}

/** A photo's crops; a missing one shows the whole photo there. */
data class PhotoCrops(
    val moments: CropRect? = null,
    val portrait: CropRect? = null,
    val landscape: CropRect? = null,
) {
    val isEmpty: Boolean get() = moments == null && portrait == null && landscape == null

    operator fun get(kind: CropKind): CropRect? = when (kind) {
        CropKind.MOMENTS -> moments
        CropKind.PORTRAIT -> portrait
        CropKind.LANDSCAPE -> landscape
    }

    fun with(kind: CropKind, rect: CropRect?): PhotoCrops = when (kind) {
        CropKind.MOMENTS -> copy(moments = rect)
        CropKind.PORTRAIT -> copy(portrait = rect)
        CropKind.LANDSCAPE -> copy(landscape = rect)
    }
}

/** No side of a crop is smaller than this fraction of the photo. */
const val MIN_CROP_SIZE = 0.08f

/** A Moments crop is no wider than 3:1 and no taller than 1:3, so the feed never gets a strip. */
const val MAX_CROP_RATIO = 3f
const val MIN_CROP_RATIO = 1f / 3f
