package com.pixelveil.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.GLES20
import android.os.SystemClock
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Media3 video effect that pixelates the chosen target. Used both by ExoPlayer (live preview)
 * and by Transformer (export), so what you preview is what you get.
 *
 * @param rawWidth width reported by the file (before rotation metadata is applied)
 * @param rawHeight height reported by the file
 * @param rotationDegrees the file's rotation metadata (clockwise)
 * @param preview true for the player: analyse at a lower rate to keep playback smooth
 */
@UnstableApi
class PixelateEffect(
    private val settings: SettingsHolder,
    private val rawWidth: Int,
    private val rawHeight: Int,
    private val rotationDegrees: Int,
    private val preview: Boolean,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        PixelateShaderProgram(context, useHdr, settings, rawWidth, rawHeight, rotationDegrees, preview)
}

@UnstableApi
internal class PixelateShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val settings: SettingsHolder,
    private val rawWidth: Int,
    private val rawHeight: Int,
    private val rotationDegrees: Int,
    private val preview: Boolean,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val engine: MaskEngine = executor.submit<MaskEngine> { MaskEngine(appContext) }.get()

    private val pixelProgram = GlProgram(VERTEX_SHADER, PIXEL_FRAGMENT_SHADER)
    private val copyProgram = GlProgram(VERTEX_SHADER, COPY_FRAGMENT_SHADER)

    private var texW = 0
    private var texH = 0
    /** Clockwise rotation needed to make the texture image upright for analysis. */
    private var analysisRotation = 0

    private var rbW = 0
    private var rbH = 0
    private var rbTex = 0
    private var rbFbo = 0
    private var rbBuffer: ByteBuffer = ByteBuffer.allocateDirect(4)
    private var rbBitmap: Bitmap? = null

    private var gridTex = 0
    private var grid = ByteArray(1)
    private var gridBuffer: ByteBuffer = ByteBuffer.allocateDirect(1)

    private var lastAnalysis: Analysis? = null
    private var lastAnalysisUs = Long.MIN_VALUE
    private var lastAnalysisWallMs = 0L
    private val tracker = MaskTracker()

    init {
        settings.usingFallbackDetector = engine.usingFallback
        val bounds = GlUtil.getNormalizedCoordinateBounds()
        pixelProgram.setBufferAttribute("aFramePosition", bounds, GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        copyProgram.setBufferAttribute("aFramePosition", bounds, GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texW = inputWidth
        texH = inputHeight

        // If the frames reach us unrotated (same orientation as the raw file) and the file
        // carries a 90/270 rotation, rotate the analysis copy so the model sees people upright.
        val texLandscape = texW >= texH
        val rawLandscape = rawWidth >= rawHeight
        analysisRotation = if ((rotationDegrees == 90 || rotationDegrees == 270) && texLandscape == rawLandscape && rawWidth != rawHeight) {
            rotationDegrees
        } else {
            0
        }

        val scale = ANALYSIS_LONG_SIDE.toFloat() / max(texW, texH)
        rbW = max(16, (texW * scale).roundToInt())
        rbH = max(16, (texH * scale).roundToInt())
        allocateReadback()
        if (gridTex == 0) gridTex = createTexture(GLES20.GL_NEAREST)
        tracker.reset()
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val s = settings.value
        val displayW = if (analysisRotation % 180 == 90) texH else texW
        val spec = GridSpec.create(texW, texH, displayW, PixelSettings.blocksAcross(s.amount, s.mode))
        val fullFrame = s.target == Target.FULL_FRAME

        if (grid.size < spec.size) {
            grid = ByteArray(spec.size)
            gridBuffer = ByteBuffer.allocateDirect(spec.size).order(ByteOrder.nativeOrder())
        }

        if (!fullFrame) {
            if (shouldAnalyze(presentationTimeUs, s)) {
                analyze(inputTexId, s)
                lastAnalysisUs = presentationTimeUs
                lastAnalysisWallMs = SystemClock.elapsedRealtime()
            }
            GridBuilder.build(lastAnalysis, s, analysisRotation, spec, grid)
            tracker.apply(grid, spec, lastAnalysis?.personPresent ?: false, presentationTimeUs, s)
        } else {
            java.util.Arrays.fill(grid, 0, spec.size, 0xFF.toByte())
        }
        uploadGrid(spec)

        pixelProgram.use()
        pixelProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
        pixelProgram.setSamplerTexIdUniform("uGridSampler", gridTex, /* texUnitIndex= */ 1)
        pixelProgram.setFloatsUniform("uCellSize", floatArrayOf(spec.cellU.toFloat(), spec.cellV.toFloat()))
        pixelProgram.setFloatsUniform("uGridSize", floatArrayOf(spec.gw.toFloat(), spec.gh.toFloat()))
        pixelProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    private fun shouldAnalyze(timeUs: Long, s: PixelSettings): Boolean {
        if (lastAnalysis == null || lastAnalysisUs == Long.MIN_VALUE || timeUs < lastAnalysisUs) return true
        return if (preview) {
            SystemClock.elapsedRealtime() - lastAnalysisWallMs >= PREVIEW_ANALYSIS_INTERVAL_MS
        } else if (s.mode == Mode.PRIVACY) {
            true
        } else {
            timeUs - lastAnalysisUs >= STYLE_EXPORT_ANALYSIS_INTERVAL_US
        }
    }

    private fun analyze(inputTexId: Int, s: PixelSettings) {
        // 1. Draw the frame into a small FBO and read it back.
        val prevFbo = IntArray(1)
        val viewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, viewport, 0)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, rbFbo)
        GLES20.glViewport(0, 0, rbW, rbH)
        copyProgram.use()
        copyProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
        copyProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        rbBuffer.rewind()
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(0, 0, rbW, rbH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rbBuffer)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])

        // 2. Make an upright bitmap: GL rows start at the bottom, then apply rotation.
        val bmp = rbBitmap ?: return
        rbBuffer.rewind()
        bmp.copyPixelsFromBuffer(rbBuffer)
        val m = Matrix().apply {
            postScale(1f, -1f)
            if (analysisRotation != 0) postRotate(analysisRotation.toFloat())
        }
        val upright = Bitmap.createBitmap(bmp, 0, 0, rbW, rbH, m, false)

        // 3. Run the models off the GL thread (MediaPipe must not disturb this EGL context).
        val wantFaces = s.mode == Mode.PRIVACY && (s.target == Target.HEAD || s.target == Target.PERSON)
        val result = try {
            executor.submit<Analysis> { engine.analyze(upright, wantFaces) }.get()
        } catch (t: Throwable) {
            null
        } finally {
            // upright is used only inside analyze().
        }
        upright.recycle()

        val now = SystemClock.elapsedRealtime()
        settings.lastAnalysisMs = now
        if (result != null) {
            lastAnalysis = result
            if (result.personPresent) settings.lastPersonSeenMs = now
        }
    }

    private fun uploadGrid(spec: GridSpec) {
        gridBuffer.clear()
        gridBuffer.put(grid, 0, spec.size)
        gridBuffer.position(0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gridTex)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, spec.gw, spec.gh, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, gridBuffer,
        )
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun allocateReadback() {
        deleteReadback()
        val prevFbo = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0)

        rbTex = createTexture(GLES20.GL_LINEAR)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rbTex)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, rbW, rbH, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        rbFbo = fbo[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, rbFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, rbTex, 0,
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])

        rbBuffer = ByteBuffer.allocateDirect(rbW * rbH * 4).order(ByteOrder.nativeOrder())
        rbBitmap = Bitmap.createBitmap(rbW, rbH, Bitmap.Config.ARGB_8888)
    }

    private fun deleteReadback() {
        if (rbFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(rbFbo), 0)
        if (rbTex != 0) GLES20.glDeleteTextures(1, intArrayOf(rbTex), 0)
        rbFbo = 0
        rbTex = 0
        rbBitmap?.recycle()
        rbBitmap = null
    }

    private fun createTexture(filter: Int): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    override fun release() {
        super.release()
        try {
            pixelProgram.delete()
            copyProgram.delete()
        } catch (_: Throwable) {}
        deleteReadback()
        if (gridTex != 0) GLES20.glDeleteTextures(1, intArrayOf(gridTex), 0)
        gridTex = 0
        executor.execute { engine.close() }
        executor.shutdown()
    }

    companion object {
        /** Long side of the copy sent to the models. The segmenter works at 256x256 internally. */
        private const val ANALYSIS_LONG_SIDE = 256
        private const val PREVIEW_ANALYSIS_INTERVAL_MS = 80L
        private const val STYLE_EXPORT_ANALYSIS_INTERVAL_US = 33_000L

        private const val VERTEX_SHADER = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

        private const val COPY_FRAGMENT_SHADER = """
precision mediump float;
uniform sampler2D uTexSampler;
varying vec2 vTexSamplingCoord;
void main() {
  gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);
}
"""

        /**
         * For cells marked in the grid: flat average of 16 samples spread over the cell.
         * Other pixels pass through untouched.
         */
        private const val PIXEL_FRAGMENT_SHADER = """
precision highp float;
uniform sampler2D uTexSampler;
uniform sampler2D uGridSampler;
uniform vec2 uCellSize;
uniform vec2 uGridSize;
varying vec2 vTexSamplingCoord;
void main() {
  vec2 cell = floor(vTexSamplingCoord / uCellSize);
  float on = texture2D(uGridSampler, (cell + 0.5) / uGridSize).r;
  if (on < 0.5) {
    gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);
    return;
  }
  vec2 origin = cell * uCellSize;
  vec4 sum = vec4(0.0);
  for (int i = 0; i < 4; i++) {
    for (int j = 0; j < 4; j++) {
      vec2 p = origin + (vec2(float(i), float(j)) + 0.5) * 0.25 * uCellSize;
      sum += texture2D(uTexSampler, clamp(p, vec2(0.0), vec2(1.0)));
    }
  }
  gl_FragColor = sum / 16.0;
}
"""
    }
}
