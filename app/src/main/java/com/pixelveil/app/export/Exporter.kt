package com.pixelveil.app.export

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.XmpData
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.pixelveil.app.engine.PixelSettings
import com.pixelveil.app.engine.PixelateEffect
import com.pixelveil.app.engine.SettingsHolder
import java.io.File

/**
 * Runs one export with Media3 Transformer. Must be created and used on the main thread.
 * Output keeps the source resolution and frame rate when the hardware encoder supports it;
 * Transformer's encoder fallback picks the nearest supported setting otherwise.
 */
@UnstableApi
class Exporter(
    private val context: Context,
    private val source: Uri,
    private val info: VideoInfo,
    settings: PixelSettings,
    private val onDone: (File) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val holder = SettingsHolder(settings)
    private val progressHolder = ProgressHolder()
    val outputFile: File = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
    private var transformer: Transformer? = null

    fun start() {
        val s = holder.value
        val audioProcessors = mutableListOf<AudioProcessor>()
        if (s.voice.pitch != 1f) {
            audioProcessors += SonicAudioProcessor().apply { setPitch(s.voice.pitch) }
        }
        val effect = PixelateEffect(holder, info.width, info.height, info.rotation, preview = false)
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(source))
            .setEffects(Effects(audioProcessors, listOf(effect)))
            .build()
        val composition = Composition.Builder(listOf(EditedMediaItemSequence(listOf(edited))))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        // Drop GPS location, XMP and Android mdta tags (device make/model) from the output.
        val muxerFactory = InAppMuxer.Factory.Builder()
            .setMetadataProvider { entries ->
                entries.removeAll { it is Mp4LocationData || it is XmpData || it is MdtaMetadataEntry }
            }
            .build()

        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setMuxerFactory(muxerFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onDone(outputFile)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    outputFile.delete()
                    onError(exportException.message ?: "Export failed (code ${exportException.errorCode})")
                }
            })
            .build()
        transformer = t
        t.start(composition, outputFile.absolutePath)
    }

    /** 0..100, or -1 when not known yet. */
    fun progress(): Int {
        val t = transformer ?: return -1
        return if (t.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) progressHolder.progress else -1
    }

    fun cancel() {
        transformer?.cancel()
        outputFile.delete()
    }
}
