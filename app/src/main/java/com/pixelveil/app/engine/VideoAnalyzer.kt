package com.pixelveil.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The "Optimising media" pass: decodes the whole video once, runs the on-device models on a
 * small upright copy of each frame, and returns a [MaskTrack]. Preview and export then only
 * look masks up, so the pixels stay locked to moving people and export runs no models.
 *
 * Runs on the calling (background) thread. Not for the main thread.
 */
class VideoAnalyzer(
    private val context: Context,
    private val uri: Uri,
    private val rotationDegrees: Int,
    private val durationMs: Long,
) {

    class AnalysisException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun run(engine: MaskEngine, onProgress: (Float) -> Unit, isCancelled: () -> Boolean): MaskTrack {
        val durationUs = max(1L, durationMs * 1000)
        // Keep memory bounded on long clips: at most ~2400 analysed frames, at most 30 per second.
        val fps = min(MAX_ANALYSIS_FPS, max(5.0, MAX_FRAMES / (durationUs / 1e6)))
        val minStepUs = (1e6 / fps).toLong()
        val track = MaskTrack(durationUs)

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw AnalysisException("No video track found in this file.")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)

            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var lastAnalysedUs = Long.MIN_VALUE
            var lastProgress = -1f
            var idleAfterInput = 0

            while (true) {
                if (isCancelled()) throw InterruptedException("cancelled")

                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = c.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx < 0 && inputDone && ++idleAfterInput > 300) break // decoder never signalled the end
                if (outIdx >= 0) {
                    idleAfterInput = 0
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val t = info.presentationTimeUs
                    if (info.size > 0 && (lastAnalysedUs == Long.MIN_VALUE || t - lastAnalysedUs >= minStepUs || eos)) {
                        val upright = try {
                            c.getOutputImage(outIdx)?.use { image -> toUprightBitmap(image) }
                        } catch (e: Throwable) {
                            Log.w(TAG, "Could not read decoded frame at $t", e)
                            null
                        }
                        if (upright != null) {
                            val analysis = engine.analyze(upright, wantFaces = true)
                            upright.recycle()
                            track.put(t, analysis)
                            lastAnalysedUs = t
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)

                    val p = (t.toFloat() / durationUs).coerceIn(0f, 1f)
                    if (p - lastProgress >= 0.01f) {
                        lastProgress = p
                        onProgress(p)
                    }
                    if (eos) break
                }
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: AnalysisException) {
            throw e
        } catch (e: Throwable) {
            throw AnalysisException("This video couldn't be analysed on this phone.", e)
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }

        if (track.size == 0) throw AnalysisException("No frames could be read from this video.")
        onProgress(1f)
        return track
    }

    /**
     * Samples the YUV frame straight into a small upright RGB bitmap (long side [ANALYSIS_LONG_SIDE]).
     * Handles row/pixel strides, crop, rotation, and 10-bit (P010) frames by reading the high byte.
     */
    private fun toUprightBitmap(image: android.media.Image): Bitmap? {
        val planes = image.planes
        if (planes.size < 3) return null
        val crop = image.cropRect
        val cw = crop.width()
        val ch = crop.height()
        if (cw <= 0 || ch <= 0) return null

        val rot = ((rotationDegrees % 360) + 360) % 360
        val uw = if (rot % 180 == 90) ch else cw
        val uh = if (rot % 180 == 90) cw else ch
        val scale = ANALYSIS_LONG_SIDE.toFloat() / max(uw, uh)
        val ow = max(16, (uw * scale).roundToInt())
        val oh = max(16, (uh * scale).roundToInt())

        val highByte = if (image.format == FORMAT_YCBCR_P010) 1 else 0
        val yBuf: ByteBuffer = planes[0].buffer
        val uBuf: ByteBuffer = planes[1].buffer
        val vBuf: ByteBuffer = planes[2].buffer
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride
        val yLim = yBuf.limit()
        val uLim = uBuf.limit()
        val vLim = vBuf.limit()

        val pixels = IntArray(ow * oh)
        var i = 0
        for (oy in 0 until oh) {
            val ny = (oy + 0.5) / oh
            for (ox in 0 until ow) {
                val nx = (ox + 0.5) / ow
                // Upright (nx, ny) -> raw decoded frame (rx, ry); raw is rotated clockwise by rot to be upright.
                val rx: Double
                val ry: Double
                when (rot) {
                    90 -> { rx = ny; ry = 1.0 - nx }
                    180 -> { rx = 1.0 - nx; ry = 1.0 - ny }
                    270 -> { rx = 1.0 - ny; ry = nx }
                    else -> { rx = nx; ry = ny }
                }
                val px = crop.left + min(cw - 1, (rx * cw).toInt())
                val py = crop.top + min(ch - 1, (ry * ch).toInt())

                val yi = py * yRow + px * yPix + highByte
                val ui = (py / 2) * uRow + (px / 2) * uPix + highByte
                val vi = (py / 2) * vRow + (px / 2) * vPix + highByte
                val yv = if (yi < yLim) yBuf.get(yi).toInt() and 0xFF else 0
                val uv = if (ui < uLim) uBuf.get(ui).toInt() and 0xFF else 128
                val vv = if (vi < vLim) vBuf.get(vi).toInt() and 0xFF else 128

                val c = (yv - 16).coerceAtLeast(0) * 1.164f
                val d = uv - 128
                val e = vv - 128
                val r = (c + 1.596f * e).toInt().coerceIn(0, 255)
                val g = (c - 0.392f * d - 0.813f * e).toInt().coerceIn(0, 255)
                val b = (c + 2.017f * d).toInt().coerceIn(0, 255)
                pixels[i++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, ow, oh, Bitmap.Config.ARGB_8888)
    }

    companion object {
        private const val TAG = "VideoAnalyzer"
        private const val TIMEOUT_US = 10_000L
        private const val ANALYSIS_LONG_SIDE = 256
        private const val MAX_ANALYSIS_FPS = 30.0
        private const val MAX_FRAMES = 2400.0
        /** ImageFormat.YCBCR_P010 (API 31+), as a literal so it compiles against any level. */
        private const val FORMAT_YCBCR_P010 = 0x36
    }
}
