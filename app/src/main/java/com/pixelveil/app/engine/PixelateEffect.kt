package com.pixelveil.app.engine

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 video effect that pixelates the chosen target, using masks computed beforehand by
 * [VideoAnalyzer]. Used by ExoPlayer (preview) and Transformer (export), so what you preview is
 * what you get. No models run here, so it never slows playback or export down.
 *
 * @param rawWidth width reported by the file (before rotation metadata is applied)
 * @param rawHeight height reported by the file
 * @param rotationDegrees the file's rotation metadata (clockwise)
 */
@UnstableApi
class PixelateEffect(
    private val settings: SettingsHolder,
    private val track: MaskTrack,
    private val rawWidth: Int,
    private val rawHeight: Int,
    private val rotationDegrees: Int,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        PixelateShaderProgram(useHdr, settings, track, rawWidth, rawHeight, rotationDegrees)
}

@UnstableApi
internal class PixelateShaderProgram(
    useHdr: Boolean,
    private val settings: SettingsHolder,
    private val track: MaskTrack,
    private val rawWidth: Int,
    private val rawHeight: Int,
    private val rotationDegrees: Int,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

    private val pixelProgram = GlProgram(VERTEX_SHADER, PIXEL_FRAGMENT_SHADER)

    private var texW = 0
    private var texH = 0
    /** Clockwise rotation that turns the texture image upright (the space masks are stored in). */
    private var analysisRotation = 0

    private var gridTex = 0
    private var grid = ByteArray(1)
    private var neighbourGrid = ByteArray(1)
    private var gridBuffer: ByteBuffer = ByteBuffer.allocateDirect(1)
    private val tracker = MaskTracker()

    init {
        pixelProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texW = inputWidth
        texH = inputHeight
        // If frames reach us unrotated (same orientation as the raw file) and the file carries a
        // 90/270 rotation, masks (stored upright) must be rotated back into texture space.
        val texLandscape = texW >= texH
        val rawLandscape = rawWidth >= rawHeight
        analysisRotation =
            if ((rotationDegrees == 90 || rotationDegrees == 270) && texLandscape == rawLandscape && rawWidth != rawHeight) {
                rotationDegrees
            } else {
                0
            }
        if (gridTex == 0) gridTex = createTexture()
        tracker.reset()
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val s = settings.value
        val displayW = if (analysisRotation % 180 == 90) texH else texW
        val spec = GridSpec.create(texW, texH, displayW, PixelSettings.blocksAcross(s.amount, s.mode))

        if (grid.size < spec.size) {
            grid = ByteArray(spec.size)
            neighbourGrid = ByteArray(spec.size)
            gridBuffer = ByteBuffer.allocateDirect(spec.size).order(ByteOrder.nativeOrder())
        }

        if (s.target == Target.FULL_FRAME) {
            java.util.Arrays.fill(grid, 0, spec.size, 0xFF.toByte())
        } else {
            val (current, next) = track.around(presentationTimeUs)
            GridBuilder.build(current, s, analysisRotation, spec, grid)
            var present = current?.personPresent == true
            // Privacy: also cover where the person is about to be, so fast movement never leaks.
            if (s.mode == Mode.PRIVACY && next != null && next !== current && s.target != Target.BACKGROUND) {
                GridBuilder.build(next, s, analysisRotation, spec, neighbourGrid)
                for (i in 0 until spec.size) if (neighbourGrid[i].toInt() != 0) grid[i] = 0xFF.toByte()
                present = present || next.personPresent
            }
            tracker.apply(grid, spec, present, track.normalize(presentationTimeUs), s)
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

    private fun createTexture(): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    override fun release() {
        super.release()
        try { pixelProgram.delete() } catch (_: Throwable) {}
        if (gridTex != 0) GLES20.glDeleteTextures(1, intArrayOf(gridTex), 0)
        gridTex = 0
    }

    companion object {
        private const val VERTEX_SHADER = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""

        /** Marked cells: flat average of 16 samples over the cell. Other pixels pass through. */
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
