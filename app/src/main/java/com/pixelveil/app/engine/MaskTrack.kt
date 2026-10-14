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
        /** In Privacy mode the next analysed frame is merged in if it is this close. */
        const val NEIGHBOUR_WINDOW_US = 120_000L
    }
}
