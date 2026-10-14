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

        /** Coarsest: a face is this many blocks wide at 100%. */
        private const val MIN_FACE_BLOCKS = 3.0

        /** Finest: a face is this many blocks wide at 0% (Style only). */
        private const val MAX_FACE_BLOCKS = 30.0

        /** Privacy mode never lets a face be more than this many blocks wide. */
        const val PRIVACY_MAX_FACE_BLOCKS = 6.0

        /** Lowest slider value allowed in Privacy mode (= face 6 blocks wide, ~0.70). */
        val PRIVACY_MIN_AMOUNT: Float =
            (1.0 - ln(PRIVACY_MAX_FACE_BLOCKS / MIN_FACE_BLOCKS) / ln(MAX_FACE_BLOCKS / MIN_FACE_BLOCKS)).toFloat()

        /** How many blocks wide a face is: 3 * 10^(1 - amount). 70% gives ~6, 100% gives 3. */
        fun faceBlocks(amount: Float, mode: Mode): Double {
            val a = amount.coerceIn(0f, 1f).toDouble()
            val b = MIN_FACE_BLOCKS * (MAX_FACE_BLOCKS / MIN_FACE_BLOCKS).pow(1.0 - a)
            return if (mode == Mode.PRIVACY) min(b, PRIVACY_MAX_FACE_BLOCKS) else b
        }

        /**
         * Block size in pixels. When the video has a detectable face, blocks are sized to the face
         * ([faceWidthFraction] = face width / frame width), so close-ups get coarse blocks too.
         * Otherwise falls back to a size based on the frame width.
         */
        fun blockPixels(displayW: Int, faceWidthFraction: Double?, amount: Float, mode: Mode): Double {
            val frameBased = displayW / blocksAcross(amount, mode)
            if (faceWidthFraction == null || faceWidthFraction <= 0.0) return frameBased
            val faceBased = faceWidthFraction * displayW / faceBlocks(amount, mode)
            val minPx = displayW / (if (mode == Mode.PRIVACY) PRIVACY_MAX_BLOCKS else MAX_BLOCKS)
            return faceBased.coerceIn(minPx, displayW / 3.0)
        }

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
