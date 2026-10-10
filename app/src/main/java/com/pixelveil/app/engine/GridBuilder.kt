package com.pixelveil.app.engine

import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The block grid for one frame, in TEXTURE space (u right, v up, both 0..1).
 * Cells are square in pixels and anchored at the texture origin, so the grid stays put
 * while the person moves (the "talking, moving pixels" look from the reference).
 */
class GridSpec(val gw: Int, val gh: Int, val cellU: Double, val cellV: Double) {
    val size: Int get() = gw * gh

    fun sameShape(other: GridSpec?): Boolean = other != null && other.gw == gw && other.gh == gh

    companion object {
        fun create(texW: Int, texH: Int, displayW: Int, blocksAcross: Double): GridSpec {
            val blockPx = max(2.0, displayW / blocksAcross)
            val gw = max(1, ceil(texW / blockPx).toInt())
            val gh = max(1, ceil(texH / blockPx).toInt())
            return GridSpec(gw, gh, blockPx / texW, blockPx / texH)
        }
    }
}

object GridBuilder {

    // Selfie multiclass categories.
    private const val BACKGROUND = 0
    private const val HAIR = 1
    private const val BODY_SKIN = 2
    private const val FACE_SKIN = 3
    private const val CLOTHES = 4
    private const val OTHER = 5

    private fun targetTable(target: Target): BooleanArray {
        val t = BooleanArray(256)
        when (target) {
            Target.HEAD -> { t[HAIR] = true; t[FACE_SKIN] = true }
            Target.PERSON -> { t[HAIR] = true; t[BODY_SKIN] = true; t[FACE_SKIN] = true; t[CLOTHES] = true; t[OTHER] = true }
            Target.CLOTHING -> { t[CLOTHES] = true }
            Target.BACKGROUND -> { t[BACKGROUND] = true }
            Target.FULL_FRAME -> t.fill(true)
        }
        return t
    }

    private val tables = Target.values().associateWith { targetTable(it) }

    /**
     * Fills [out] (size gw*gh) with 255 for cells to pixelate, 0 otherwise.
     * [rotation] is the clockwise rotation applied to the texture image to make it upright
     * before analysis (0, 90, 180, 270).
     */
    fun build(a: Analysis?, s: PixelSettings, rotation: Int, spec: GridSpec, out: ByteArray) {
        java.util.Arrays.fill(out, 0, spec.size, 0)
        if (s.target == Target.FULL_FRAME) {
            java.util.Arrays.fill(out, 0, spec.size, 0xFF.toByte())
            return
        }
        if (a == null) {
            // Nothing analysed yet: Privacy covers everything rather than risk a leak.
            if (s.mode == Mode.PRIVACY) java.util.Arrays.fill(out, 0, spec.size, 0xFF.toByte())
            return
        }

        val cats = a.categories
        if (cats != null) {
            val table = tables.getValue(s.target)
            val w = a.width
            val h = a.height
            var i = 0
            for (y in 0 until h) {
                val ny = (y + 0.5) / h
                for (x in 0 until w) {
                    if (table[cats[i].toInt() and 0xFF]) {
                        mark((x + 0.5) / w, ny, rotation, spec, out)
                    }
                    i++
                }
            }
            // Face boxes back up the segmenter in Privacy mode.
            if (s.mode == Mode.PRIVACY && (s.target == Target.HEAD || s.target == Target.PERSON)) {
                for (f in a.faces) markRect(expandHead(f), rotation, spec, out, 0xFF.toByte())
            }
        } else {
            // Fallback (built-in face detector only): approximate regions from face boxes.
            when (s.target) {
                Target.HEAD -> a.faces.forEach { markRect(expandHead(it), rotation, spec, out, 0xFF.toByte()) }
                Target.PERSON -> a.faces.forEach {
                    markRect(expandHead(it), rotation, spec, out, 0xFF.toByte())
                    markRect(bodyFromFace(it, includeHead = true), rotation, spec, out, 0xFF.toByte())
                }
                Target.CLOTHING -> a.faces.forEach { markRect(bodyFromFace(it, includeHead = false), rotation, spec, out, 0xFF.toByte()) }
                Target.BACKGROUND -> {
                    java.util.Arrays.fill(out, 0, spec.size, 0xFF.toByte())
                    a.faces.forEach {
                        markRect(expandHead(it), rotation, spec, out, 0)
                        markRect(bodyFromFace(it, includeHead = true), rotation, spec, out, 0)
                    }
                }
                Target.FULL_FRAME -> Unit
            }
        }
    }

    private fun expandHead(f: RectF): RectF {
        val fw = f.width()
        val fh = f.height()
        return RectF(f.left - fw * 0.25f, f.top - fh * 0.45f, f.right + fw * 0.25f, f.bottom + fh * 0.1f)
    }

    private fun bodyFromFace(f: RectF, includeHead: Boolean): RectF {
        val fw = f.width()
        val cx = f.centerX()
        val top = if (includeHead) f.top - f.height() * 0.3f else f.bottom
        return RectF(cx - fw * 1.6f, top, cx + fw * 1.6f, 1f)
    }

    /** Upright normalised (nx, ny; y down) -> texture u. */
    private fun texU(nx: Double, ny: Double, rotation: Int): Double = when (rotation) {
        90 -> ny
        180 -> 1.0 - nx
        270 -> 1.0 - ny
        else -> nx
    }

    /** Upright normalised (nx, ny; y down) -> texture v (v up). */
    private fun texV(nx: Double, ny: Double, rotation: Int): Double {
        val by = when (rotation) {
            90 -> 1.0 - nx
            180 -> 1.0 - ny
            270 -> nx
            else -> ny
        }
        return 1.0 - by
    }

    private fun mark(nx: Double, ny: Double, rotation: Int, spec: GridSpec, out: ByteArray) {
        val u = texU(nx, ny, rotation)
        val v = texV(nx, ny, rotation)
        val gx = floor(u / spec.cellU).toInt()
        val gy = floor(v / spec.cellV).toInt()
        if (gx in 0 until spec.gw && gy in 0 until spec.gh) out[gy * spec.gw + gx] = 0xFF.toByte()
    }

    private fun markRect(r: RectF, rotation: Int, spec: GridSpec, out: ByteArray, value: Byte) {
        val x0 = r.left.toDouble().coerceIn(0.0, 1.0)
        val x1 = r.right.toDouble().coerceIn(0.0, 1.0)
        val y0 = r.top.toDouble().coerceIn(0.0, 1.0)
        val y1 = r.bottom.toDouble().coerceIn(0.0, 1.0)
        if (x1 <= x0 || y1 <= y0) return
        val us = doubleArrayOf(texU(x0, y0, rotation), texU(x1, y1, rotation))
        val vs = doubleArrayOf(texV(x0, y0, rotation), texV(x1, y1, rotation))
        val gx0 = max(0, floor(min(us[0], us[1]) / spec.cellU).toInt())
        val gx1 = min(spec.gw - 1, floor(max(us[0], us[1]) / spec.cellU).toInt())
        val gy0 = max(0, floor(min(vs[0], vs[1]) / spec.cellV).toInt())
        val gy1 = min(spec.gh - 1, floor(max(vs[0], vs[1]) / spec.cellV).toInt())
        for (gy in gy0..gy1) for (gx in gx0..gx1) out[gy * spec.gw + gx] = value
    }
}

/**
 * Temporal rules applied after [GridBuilder]:
 * Privacy: union with the previous frame (motion guard), grow one cell, and the
 * lost-tracking fail-safe (whole frame for up to 1 s after the person was last seen).
 * Style: hold the last grid briefly when the person blinks out.
 */
class MaskTracker {
    private var lastSeenUs = Long.MIN_VALUE
    private var prev: ByteArray? = null
    private var prevSpec: GridSpec? = null
    private var scratch = ByteArray(0)

    fun reset() {
        lastSeenUs = Long.MIN_VALUE
        prev = null
        prevSpec = null
    }

    fun apply(grid: ByteArray, spec: GridSpec, present: Boolean, timeUs: Long, s: PixelSettings) {
        if (s.target == Target.FULL_FRAME) return
        if (lastSeenUs != Long.MIN_VALUE && timeUs < lastSeenUs) reset() // seek or loop
        if (present) lastSeenUs = timeUs
        val sinceSeen = if (lastSeenUs == Long.MIN_VALUE) Long.MAX_VALUE else timeUs - lastSeenUs
        val n = spec.size

        if (s.target == Target.BACKGROUND) {
            remember(grid, spec)
            return
        }

        if (s.mode == Mode.STYLE) {
            val p = prev
            if (!present && sinceSeen <= STYLE_HOLD_US && p != null && spec.sameShape(prevSpec)) {
                System.arraycopy(p, 0, grid, 0, n)
            } else {
                remember(grid, spec)
            }
            return
        }

        // Privacy mode.
        if (!present && sinceSeen <= FAILSAFE_US) {
            java.util.Arrays.fill(grid, 0, n, 0xFF.toByte())
            return
        }
        val raw = grid.copyOf(n)
        val p = prev
        if (p != null && spec.sameShape(prevSpec)) {
            for (i in 0 until n) if (p[i].toInt() != 0) grid[i] = 0xFF.toByte()
        }
        prev = raw
        prevSpec = spec
        dilate(grid, spec)
    }

    private fun remember(grid: ByteArray, spec: GridSpec) {
        prev = grid.copyOf(spec.size)
        prevSpec = spec
    }

    private fun dilate(grid: ByteArray, spec: GridSpec) {
        val n = spec.size
        if (scratch.size < n) scratch = ByteArray(n)
        System.arraycopy(grid, 0, scratch, 0, n)
        val w = spec.gw
        val h = spec.gh
        for (y in 0 until h) for (x in 0 until w) {
            if (scratch[y * w + x].toInt() == 0) continue
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx < 0 || xx >= w) continue
                    grid[yy * w + xx] = 0xFF.toByte()
                }
            }
        }
    }

    companion object {
        const val FAILSAFE_US = 1_000_000L
        const val STYLE_HOLD_US = 300_000L
    }
}
