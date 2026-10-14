package com.pixelveil.app.engine

import java.util.TreeMap

/**
 * Per-frame analysis results for one video, keyed by presentation time (microseconds from the
 * start of the video). Built once by [VideoAnalyzer]; read by the preview and the export.
 */
class MaskTrack(val durationUs: Long) {
    private val frames = TreeMap<Long, Analysis>()

    val size: Int get() = frames.size

    @Synchronized
    fun put(timeUs: Long, analysis: Analysis) {
        frames[timeUs] = analysis
    }

    @Synchronized
    fun hasPerson(): Boolean = frames.values.any { it.personPresent }

    /**
     * Typical width of the biggest face in this video as a fraction of the frame width, or null
     * if no face was seen. Uses the 90th percentile (close to the largest), so blocks are sized for
     * the moment the face is nearest the camera. Computed once and cached.
     */
    val faceWidthFraction: Double? by lazy { computeFaceWidth() }

    @Synchronized
    private fun computeFaceWidth(): Double? {
        val widths = ArrayList<Double>()
        for (a in frames.values) {
            val faceBox = a.faces.maxOfOrNull { it.width().toDouble() }
            if (faceBox != null && faceBox > 0) {
                widths += faceBox * FACE_BOX_TO_HEAD
                continue
            }
            headWidth(a)?.let { widths += it }
        }
        if (widths.size < 2) return null
        widths.sort()
        return widths[((widths.size - 1) * 0.9).toInt()].coerceIn(0.02, 1.0)
    }

    /** Width of the hair + face-skin area (when it looks like a single head), as a fraction. */
    private fun headWidth(a: Analysis): Double? {
        val c = a.categories ?: return null
        var minX = Int.MAX_VALUE
        var maxX = -1
        var n = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val k = c[y * a.width + x].toInt()
            if (k == 1 || k == 3) { if (x < minX) minX = x; if (x > maxX) maxX = x; n++ }
        }
        if (n < 20 || maxX < minX) return null
        val w = (maxX - minX + 1).toDouble() / a.width
        return if (w < 0.6) w else null // wider than that is probably several people
    }

    /** Maps player/exporter timestamps into this track's time range (handles looping). */
    fun normalize(timeUs: Long): Long {
        if (durationUs <= 0) return timeUs
        val slack = 200_000L
        return if (timeUs in -slack..(durationUs + slack)) timeUs else ((timeUs % durationUs) + durationUs) % durationUs
    }

    /** The analysis at or just before [timeUs], and the one just after it. */
    @Synchronized
    fun around(timeUs: Long): Pair<Analysis?, Analysis?> {
        val t = normalize(timeUs)
        val before = frames.floorEntry(t)
        val after = frames.higherEntry(t)
        val a = before?.value ?: after?.value
        val b = if (after != null && after.key - t <= NEIGHBOUR_WINDOW_US) after.value else null
        return a to b
    }

    companion object {
        /** Face-detector boxes cover the face only; heads (with hair) are about this much wider. */
        const val FACE_BOX_TO_HEAD = 1.3

        /** In Privacy mode the next analysed frame is merged in if it is this close. */
        const val NEIGHBOUR_WINDOW_US = 120_000L
    }
}
