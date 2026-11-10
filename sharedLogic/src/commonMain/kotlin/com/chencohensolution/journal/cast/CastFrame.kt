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
     * As close as the TV is filled with the photo, no black bars (a double tap, asked Nov 10, 2026): the frame as large
     * as fits inside the photo, about where it is now.
     */
    fun filled(): TvZoom {
        val (w1, h1) = wholeSize()
        return copy(scale = max(w1, h1).coerceIn(1f, MAX_SCALE)).held()
    }

    /** A double tap: filled when it shows the whole photo, else back to the whole photo. */
    fun toggled(): TvZoom = if (isZoomed) whole() else filled()

    /**
     * Where the photo is drawn on the phone (asked Nov 10, 2026: the phone zooms with the TV): the TV's frame fills
     * [tv], the TV's shape as large as fits on the phone ([PhoneRect.fitted]), and the photo sits around it as the frame
     * says. Fractions or pixels alike: the result is in [tv]'s units.
     */
    fun photoOn(tv: PhoneRect): PhoneRect {
        val f = frame
        val width = tv.width / f.w
        val height = tv.height / f.h
        return PhoneRect(tv.left - f.x * width, tv.top - f.y * height, width, height)
    }

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

/** A rectangle on the phone's screen: its top left corner and size. */
data class PhoneRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    companion object {
        /** A rectangle of [ratio] (width over height) as large as fits in [width] × [height], in the middle. */
        fun fitted(width: Float, height: Float, ratio: Float, left: Float = 0f, top: Float = 0f): PhoneRect {
            if (width <= 0f || height <= 0f || ratio <= 0f) return PhoneRect(left, top, width, height)
            return if (ratio >= width / height) {
                val h = width / ratio
                PhoneRect(left, top + (height - h) / 2, width, h)
            } else {
                val w = height * ratio
                PhoneRect(left + (width - w) / 2, top, w, height)
            }
        }
    }
}
