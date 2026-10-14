package com.pixelveil.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.pixelveil.app.engine.MaskTrack
import com.pixelveil.app.engine.PixelateEffect
import com.pixelveil.app.engine.SettingsHolder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pixelveil.app.engine.MaskEngine
import com.pixelveil.app.engine.Mode
import com.pixelveil.app.engine.PixelSettings
import com.pixelveil.app.engine.Target
import com.pixelveil.app.engine.VideoAnalyzer
import com.pixelveil.app.engine.Voice
import com.pixelveil.app.export.Exporter
import com.pixelveil.app.export.VideoInfo
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs the real analysis pass and export on a device or emulator. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ExportTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext

    private fun copyAsset(name: String): Uri {
        val f = File(ctx.cacheDir, name)
        instrumentation.context.assets.open(name).use { input -> f.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(f)
    }

    private fun analyzeAndExport(asset: String) {
        val uri = copyAsset(asset)
        val info = VideoInfo.read(ctx, uri)
        assertNotNull("VideoInfo for $asset", info)
        info!!
        Log.i(TAG, "$asset: $info")

        val track = MaskEngine(ctx).use { engine ->
            VideoAnalyzer(ctx, uri, info.rotation, info.durationMs).run(engine, {}, { false })
        }
        Log.i(TAG, "$asset: analysed ${track.size} frames, person found: ${track.hasPerson()}")
        assertTrue("analysed frames for $asset: ${track.size}", track.size >= 10)
        assertTrue("a person should be detected in $asset", track.hasPerson())

        // Mimic the app: preview with the effect, release the player, then export straight away.
        previewThenRelease(uri, info, track)

        val cases = listOf(
            PixelSettings(target = Target.PERSON, mode = Mode.PRIVACY, voice = Voice.DEEP),
            PixelSettings(target = Target.FULL_FRAME, mode = Mode.STYLE, amount = 0.3f),
        )
        for (settings in cases) {
            val latch = CountDownLatch(1)
            var error: String? = null
            var output: File? = null
            instrumentation.runOnMainSync {
                Exporter(
                    context = ctx,
                    source = uri,
                    info = info,
                    track = track,
                    settings = settings,
                    onDone = { output = it; latch.countDown() },
                    onError = { error = it; latch.countDown() },
                ).start()
            }
            assertTrue("export timed out for $asset $settings", latch.await(240, TimeUnit.SECONDS))
            assertNull("export error for $asset $settings", error)
            val out = output
            assertTrue("output exists for $asset $settings", out != null && out.length() > 0)
            val outInfo = VideoInfo.read(ctx, Uri.fromFile(out))
            Log.i(TAG, "$asset $settings -> ${out!!.length()} bytes, $outInfo")
            assertNotNull("output readable", outInfo)
            if (settings.target == Target.PERSON) checkPixelsLandOnPerson(asset, uri, Uri.fromFile(out))
            out.delete()
        }
    }

    private fun previewThenRelease(uri: Uri, info: VideoInfo, track: MaskTrack) {
        var player: ExoPlayer? = null
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(ctx).build().apply {
                setVideoEffects(listOf(PixelateEffect(SettingsHolder(PixelSettings()), track, info.width, info.height, info.rotation)))
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = true
            }
        }
        Thread.sleep(2000)
        instrumentation.runOnMainSync { player?.release() }
    }

    /** Upright luminance image of the frame at [timeUs], whatever way the file stores it. */
    private fun uprightFrame(uri: Uri, timeUs: Long): Bitmap {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val frame = r.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)!!
            // Display is portrait (720x1280). If the retriever gave the stored (unrotated) frame, rotate it.
            return if (frame.width > frame.height && rot % 180 == 90) {
                Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            } else frame
        } finally {
            r.release()
        }
    }

    /** Mean absolute neighbour difference (texture) of a region given in 720x1280 display coordinates. */
    private fun texture(b: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val sx = b.width / 720.0
        val sy = b.height / 1280.0
        val l = (x0 * sx).toInt(); val t = (y0 * sy).toInt()
        val w = ((x1 - x0) * sx).toInt(); val h = ((y1 - y0) * sy).toInt()
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, l, t, w, h)
        fun lum(c: Int) = ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10
        var sum = 0.0
        var n = 0
        for (y in 0 until h - 1) for (x in 0 until w - 1) {
            val c = lum(px[y * w + x])
            sum += Math.abs(c - lum(px[y * w + x + 1])) + Math.abs(c - lum(px[(y + 1) * w + x]))
            n++
        }
        return sum / n
    }

    /**
     * The person sits in the centre of the frame over a noisy background. After a Person export
     * the person's area must lose most of its detail, and the empty top of the frame must keep it.
     * A mask that is rotated or shifted would fail one of the two.
     */
    private fun checkPixelsLandOnPerson(asset: String, src: Uri, out: Uri) {
        val t = 1_000_000L
        val a = uprightFrame(src, t)
        val b = uprightFrame(out, t)
        val faceSrc = texture(a, 300, 600, 440, 760)
        val faceOut = texture(b, 300, 600, 440, 760)
        val bgSrc = texture(a, 40, 60, 680, 380)
        val bgOut = texture(b, 40, 60, 680, 380)
        Log.i(TAG, "$asset alignment: face $faceSrc -> $faceOut, background $bgSrc -> $bgOut")
        logImage("$asset-before", a)
        logImage("$asset-after", b)
        assertTrue("person area should be pixelated in $asset ($faceSrc -> $faceOut)", faceOut < faceSrc * 0.6)
        assertTrue("background should stay sharp in $asset ($bgSrc -> $bgOut)", bgOut > bgSrc * 0.6)
    }

    /** Writes a small PNG of [b] to logcat in base64 chunks so CI can show the result. */
    private fun logImage(name: String, b: Bitmap) {
        val small = Bitmap.createScaledBitmap(b, 270, 480, true)
        val bytes = java.io.ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        b64.chunked(3000).forEachIndexed { i, chunk -> Log.i("PVIMG", "$name|$i|$chunk") }
        Log.i("PVIMG", "$name|end|")
    }

    @Test
    fun portraitVideo() = analyzeAndExport("person.mp4")

    @Test
    fun rotatedPhoneVideo() = analyzeAndExport("person_rotated.mp4")

    companion object {
        private const val TAG = "PixelVeilTest"
    }
}
