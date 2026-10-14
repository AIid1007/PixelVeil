package com.pixelveil.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * One analysed frame, in UPRIGHT image space (what the viewer sees).
 * [categories] holds one byte per pixel from the selfie multiclass model:
 * 0 background, 1 hair, 2 body skin, 3 face skin, 4 clothes, 5 other (accessories).
 * It is null when the fallback face detector is in use.
 * [faces] are normalised (0..1) boxes.
 */
class Analysis(
    val width: Int,
    val height: Int,
    val categories: ByteArray?,
    val faces: List<RectF>,
    val personPresent: Boolean,
)

/**
 * Runs the on-device models. Not thread-safe: call from one thread only.
 * All models ship inside the APK; nothing is downloaded at runtime.
 */
class MaskEngine(context: Context) : Closeable {

    /** Primary segmenter: the model run directly with TensorFlow Lite on the CPU (works on every device). */
    private var interpreter: Interpreter? = null
    private var inputBuffer: ByteBuffer? = null
    private var outputBuffer: ByteBuffer? = null
    private var scaledInput: Bitmap? = null
    private val inputPixels = IntArray(SEG_SIZE * SEG_SIZE)

    /** Backup segmenter: MediaPipe's task (needs GPU features some devices lack). */
    private var segmenter: ImageSegmenter? = null
    private var faceDetector: FaceDetector? = null

    val usingFallback: Boolean

    init {
        interpreter = try {
            context.assets.openFd(SEGMENTER_MODEL).use { fd ->
                FileInputStream(fd.fileDescriptor).channel.use { ch ->
                    val model = ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                    Interpreter(model, Interpreter.Options().setNumThreads(4))
                }
            }.also {
                inputBuffer = ByteBuffer.allocateDirect(4 * SEG_SIZE * SEG_SIZE * 3).order(ByteOrder.nativeOrder())
                outputBuffer = ByteBuffer.allocateDirect(4 * SEG_SIZE * SEG_SIZE * SEG_CLASSES).order(ByteOrder.nativeOrder())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "TensorFlow Lite segmenter unavailable, trying MediaPipe", t)
            null
        }
        segmenter = if (interpreter != null) null else try {
            ImageSegmenter.createFromOptions(
                context,
                ImageSegmenter.ImageSegmenterOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(SEGMENTER_MODEL).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setOutputCategoryMask(true)
                    .setOutputConfidenceMasks(false)
                    .build(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Segmenter unavailable, falling back to face detection", t)
            null
        }
        faceDetector = try {
            FaceDetector.createFromOptions(
                context,
                FaceDetector.FaceDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(FACE_MODEL).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setMinDetectionConfidence(0.4f)
                    .build(),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "MediaPipe face detector unavailable", t)
            null
        }
        usingFallback = interpreter == null && segmenter == null
    }

    fun analyze(upright: Bitmap, wantFaces: Boolean): Analysis {
        val w = upright.width
        val h = upright.height
        if (interpreter != null) return analyzeWithTflite(upright, wantFaces)
        val seg = segmenter
        if (seg == null) {
            val faces = detectFacesFallback(upright)
            return Analysis(w, h, null, faces, faces.isNotEmpty())
        }

        val mpImage = BitmapImageBuilder(upright).build()
        var categories: ByteArray? = null
        var mw = w
        var mh = h
        try {
            val result = seg.segment(mpImage)
            val maskOpt = result.categoryMask()
            if (maskOpt.isPresent) {
                val mask = maskOpt.get()
                mw = mask.width
                mh = mask.height
                val buf = ByteBufferExtractor.extract(mask)
                buf.rewind()
                val bytes = ByteArray(mw * mh)
                buf.get(bytes, 0, minOf(bytes.size, buf.remaining()))
                categories = bytes
                mask.close()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Segmentation failed on a frame", t)
        }

        val faces = if (wantFaces) detectFaces(mpImage, w, h) else emptyList()
        mpImage.close()

        var personPixels = 0
        categories?.let { c -> for (b in c) if (b.toInt() != 0) personPixels++ }
        val total = (mw * mh).coerceAtLeast(1)
        val present = personPixels > total * PERSON_PRESENT_FRACTION || faces.isNotEmpty()
        return Analysis(mw, mh, categories, faces, present)
    }

    private fun analyzeWithTflite(upright: Bitmap, wantFaces: Boolean): Analysis {
        val interp = interpreter!!
        val input = inputBuffer!!
        val output = outputBuffer!!
        // The model takes the whole frame stretched to 256x256, RGB scaled to [-1, 1].
        val scaled = scaledInput ?: Bitmap.createBitmap(SEG_SIZE, SEG_SIZE, Bitmap.Config.ARGB_8888).also { scaledInput = it }
        android.graphics.Canvas(scaled).drawBitmap(
            upright,
            android.graphics.Rect(0, 0, upright.width, upright.height),
            android.graphics.Rect(0, 0, SEG_SIZE, SEG_SIZE),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
        )
        scaled.getPixels(inputPixels, 0, SEG_SIZE, 0, 0, SEG_SIZE, SEG_SIZE)
        input.rewind()
        for (c in inputPixels) {
            input.putFloat(((c shr 16 and 0xFF) - 127.5f) / 127.5f)
            input.putFloat(((c shr 8 and 0xFF) - 127.5f) / 127.5f)
            input.putFloat(((c and 0xFF) - 127.5f) / 127.5f)
        }
        input.rewind()
        output.rewind()
        var categories: ByteArray? = null
        try {
            interp.run(input, output)
            output.rewind()
            val f = output.asFloatBuffer()
            val bytes = ByteArray(SEG_SIZE * SEG_SIZE)
            for (i in bytes.indices) {
                var best = 0
                var bestV = f.get(i * SEG_CLASSES)
                for (k in 1 until SEG_CLASSES) {
                    val v = f.get(i * SEG_CLASSES + k)
                    if (v > bestV) { bestV = v; best = k }
                }
                bytes[i] = best.toByte()
            }
            categories = bytes
        } catch (t: Throwable) {
            Log.w(TAG, "Segmentation failed on a frame", t)
        }

        val faces = if (wantFaces) {
            val mp = BitmapImageBuilder(upright).build()
            try { detectFaces(mp, upright.width, upright.height) } finally { mp.close() }
        } else emptyList()

        var personPixels = 0
        categories?.let { c -> for (b in c) if (b.toInt() != 0) personPixels++ }
        val present = personPixels > SEG_SIZE * SEG_SIZE * PERSON_PRESENT_FRACTION || faces.isNotEmpty()
        return Analysis(SEG_SIZE, SEG_SIZE, categories, faces, present)
    }

    private fun detectFaces(image: com.google.mediapipe.framework.image.MPImage, w: Int, h: Int): List<RectF> {
        val fd = faceDetector ?: return emptyList()
        return try {
            fd.detect(image).detections().map { d ->
                val b = d.boundingBox()
                RectF(b.left / w, b.top / h, b.right / w, b.bottom / h)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Face detection failed on a frame", t)
            emptyList()
        }
    }

    /** Android's built-in face detector. Frontal faces only; used only if MediaPipe fails to load. */
    private fun detectFacesFallback(src: Bitmap): List<RectF> {
        val evenW = src.width - (src.width % 2)
        if (evenW <= 0 || src.height <= 0) return emptyList()
        val rgb565 = Bitmap.createBitmap(evenW, src.height, Bitmap.Config.RGB_565)
        val canvas = android.graphics.Canvas(rgb565)
        canvas.drawBitmap(src, 0f, 0f, null)
        val found = arrayOfNulls<android.media.FaceDetector.Face>(MAX_FALLBACK_FACES)
        val n = try {
            android.media.FaceDetector(evenW, src.height, MAX_FALLBACK_FACES).findFaces(rgb565, found)
        } catch (t: Throwable) {
            0
        }
        rgb565.recycle()
        val out = ArrayList<RectF>(n)
        val mid = PointF()
        for (i in 0 until n) {
            val f = found[i] ?: continue
            f.getMidPoint(mid)
            val e = f.eyesDistance()
            out += RectF(
                (mid.x - e * 1.4f) / evenW,
                (mid.y - e * 1.6f) / src.height,
                (mid.x + e * 1.4f) / evenW,
                (mid.y + e * 2.0f) / src.height,
            )
        }
        return out
    }

    override fun close() {
        try { interpreter?.close() } catch (_: Throwable) {}
        interpreter = null
        scaledInput?.recycle()
        scaledInput = null
        try { segmenter?.close() } catch (_: Throwable) {}
        try { faceDetector?.close() } catch (_: Throwable) {}
        segmenter = null
        faceDetector = null
    }

    companion object {
        private const val TAG = "MaskEngine"
        const val SEGMENTER_MODEL = "selfie_multiclass_256x256.tflite"
        const val FACE_MODEL = "blaze_face_short_range.tflite"
        private const val PERSON_PRESENT_FRACTION = 0.002
        private const val MAX_FALLBACK_FACES = 6
        private const val SEG_SIZE = 256
        private const val SEG_CLASSES = 6
    }
}
