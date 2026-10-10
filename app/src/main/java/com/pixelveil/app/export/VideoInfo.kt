package com.pixelveil.app.export

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

data class VideoInfo(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationMs: Long,
    val hasAudio: Boolean,
) {
    val displayWidth: Int get() = if (rotation % 180 == 90) height else width
    val displayHeight: Int get() = if (rotation % 180 == 90) width else height

    companion object {
        /** Returns null if the file can't be read as a video. */
        fun read(context: Context, uri: Uri): VideoInfo? {
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(context, uri)
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: return null
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: return null
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val audio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                VideoInfo(w, h, ((rot % 360) + 360) % 360, dur, audio)
            } catch (t: Throwable) {
                null
            } finally {
                try { r.release() } catch (_: Throwable) {}
            }
        }
    }
}
