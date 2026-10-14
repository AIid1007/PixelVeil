package com.pixelveil.app

import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
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
        Log.i(TAG, "$asset: analysed ${track.size} frames")
        assertTrue("analysed frames for $asset: ${track.size}", track.size >= 10)

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
            out.delete()
        }
    }

    @Test
    fun portraitVideo() = analyzeAndExport("sample.mp4")

    @Test
    fun rotatedPhoneVideo() = analyzeAndExport("sample_rotated.mp4")

    companion object {
        private const val TAG = "PixelVeilTest"
    }
}
