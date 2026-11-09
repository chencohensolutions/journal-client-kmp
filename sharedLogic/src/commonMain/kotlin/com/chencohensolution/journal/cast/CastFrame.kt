package com.chencohensolution.journal.cast

import kotlin.math.max
import kotlin.math.min

/**
 * The part of a photo the TV fills its screen with, in fractions of the upright photo: [x], [y] its top left corner,
 * [w], [h] its size. It has the TV's shape, so it reaches past the photo's edges where the photo doesn't fill the TV
 * (black bars there).
 */
data class CastFrame(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * The TV's frame on a photo while casting (docs/cast.md): the phone shows the whole photo with this frame on it, and a
 * pinch or a drag on the phone moves it; the TV shows what it holds. [scale] 1 is the whole photo on the TV; 2 is twice
 * as close. [centerX], [centerY] (fractions of the photo) is the frame's middle. Immutable: each gesture makes a new one.
 */
data class TvZoom(
    /** The photo's width over its height, upright. */
    val photoRatio: Float,
    /** The TV screen's width over its height (16:9 until the TV says). */
    val tvRatio: Float = DEFAULT_TV_RATIO,
    val scale: Float = 1f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
) {
    /** Closer than the whole photo: then one finger moves the frame instead of swiping to the next photo. */
    val isZoomed: Boolean get() = scale > 1.001f

    /** The frame at this zoom. */
    val frame: CastFrame get() {
        val (w1, h1) = wholeSize()
        val w = w1 / scale
        val h = h1 / scale
        return CastFrame(centerX - w / 2, centerY - h / 2, w, h)
    }

    /**
     * Closer by [factor] (a pinch), keeping the photo's point [focusX], [focusY] (fractions) where it is in the frame.
     * Held between the whole photo and [MAX_SCALE].
     */
    fun zoomBy(factor: Float, focusX: Float, focusY: Float): TvZoom {
        if (factor <= 0f || factor.isNaN()) return this
        val next = (scale * factor).coerceIn(1f, MAX_SCALE)
        val k = scale / next
        return copy(
            scale = next,
            centerX = focusX + (centerX - focusX) * k,
            centerY = focusY + (centerY - focusY) * k,
        ).held()
    }

    /** The frame moved by [dx], [dy] (fractions of the photo), kept on the photo. */
    fun panBy(dx: Float, dy: Float): TvZoom = copy(centerX = centerX + dx, centerY = centerY + dy).held()

    /** The same zoom on a TV of another shape (when the TV tells its size), kept on the photo. */
    fun onTv(ratio: Float): TvZoom = if (ratio > 0f && ratio != tvRatio) copy(tvRatio = ratio).held() else this

    /** Back to the whole photo. */
    fun whole(): TvZoom = TvZoom(photoRatio, tvRatio)

    /**
     * The frame's size at scale 1, in fractions of the photo: all of the photo fits, and the frame reaches past it on
     * the sides that don't fill the TV.
     */
    private fun wholeSize(): Pair<Float, Float> = if (photoRatio >= tvRatio) {
        1f to photoRatio / tvRatio
    } else {
        tvRatio / photoRatio to 1f
    }

    /**
     * Kept on the photo: along a side where the frame is smaller than the photo it stays inside it; along one where it
     * is larger, centred.
     */
    private fun held(): TvZoom {
        val (w1, h1) = wholeSize()
        val w = w1 / scale
        val h = h1 / scale
        return copy(centerX = keep(centerX, w), centerY = keep(centerY, h))
    }

    private fun keep(center: Float, size: Float): Float =
        if (size >= 1f) 0.5f else min(max(center, size / 2), 1f - size / 2)

    companion object {
        /** A TV that hasn't told its size yet: 16:9. */
        const val DEFAULT_TV_RATIO = 16f / 9f

        /** As close as the frame goes: a twelfth of the photo across. */
        const val MAX_SCALE = 12f
    }
}
