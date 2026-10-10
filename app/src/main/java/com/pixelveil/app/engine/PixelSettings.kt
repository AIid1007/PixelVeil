package com.pixelveil.app.engine

import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

enum class Target(val label: String) {
    FULL_FRAME("Full frame"),
    HEAD("Head"),
    PERSON("Person"),
    CLOTHING("Clothing"),
    BACKGROUND("Background"),
}

enum class Mode(val label: String) {
    PRIVACY("Privacy"),
    STYLE("Style"),
}

enum class Voice(val label: String, val pitch: Float) {
    OFF("Off", 1.0f),
    DEEPER("Deeper", 0.72f),
    DEEP("Deep", 0.86f),
    HIGH("High", 1.3f),
}

data class PixelSettings(
    val target: Target = Target.PERSON,
    val mode: Mode = Mode.PRIVACY,
    /** 0..1. Higher = bigger blocks. */
    val amount: Float = DEFAULT_AMOUNT,
    val voice: Voice = Voice.OFF,
) {
    companion object {
        const val DEFAULT_AMOUNT = 0.7f

        /** Coarsest grid: blocks across the frame width at 100%. */
        private const val MIN_BLOCKS = 14.0

        /** Finest grid: blocks across the frame width at 0%. */
        private const val MAX_BLOCKS = 64.0

        /** Privacy mode never allows more than this many blocks across (i.e. blocks never finer). */
        const val PRIVACY_MAX_BLOCKS = 24.0

        /** Lowest slider value allowed in Privacy mode (~0.64). */
        val PRIVACY_MIN_AMOUNT: Float =
            (1.0 - ln(PRIVACY_MAX_BLOCKS / MIN_BLOCKS) / ln(MAX_BLOCKS / MIN_BLOCKS)).toFloat()

        /** blocks = 14 * (64/14)^(1 - amount); 70% gives ~22 blocks across, like the reference. */
        fun blocksAcross(amount: Float, mode: Mode): Double {
            val a = amount.coerceIn(0f, 1f).toDouble()
            val b = MIN_BLOCKS * (MAX_BLOCKS / MIN_BLOCKS).pow(1.0 - a)
            return if (mode == Mode.PRIVACY) min(b, PRIVACY_MAX_BLOCKS) else b
        }
    }
}

/** Shared between the UI thread and the GL thread so the live preview reacts instantly. */
class SettingsHolder(initial: PixelSettings) {
    @Volatile
    var value: PixelSettings = initial

    /** Wall-clock ms of the last analysed frame that contained a person (for the UI hint). */
    @Volatile
    var lastPersonSeenMs: Long = 0L

    /** Wall-clock ms of the last analysed frame. */
    @Volatile
    var lastAnalysisMs: Long = 0L

    /** True when MediaPipe failed to load and the built-in face detector is used instead. */
    @Volatile
    var usingFallbackDetector: Boolean = false
}
