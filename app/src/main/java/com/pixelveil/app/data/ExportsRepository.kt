package com.pixelveil.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import java.io.File

data class ExportItem(
    val uri: Uri,
    val name: String,
    val dateAddedSec: Long,
    val durationMs: Long,
)

/** Exported videos live in the gallery under Movies/PixelVeil. No storage permission needed on Android 10+. */
class ExportsRepository(private val context: Context) {

    private val resolver get() = context.contentResolver

    fun saveToGallery(file: File): Uri? {
        val name = "PixelVeil_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            file.delete()
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }

    fun list(): List<ExportItem> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("${Environment.DIRECTORY_MOVIES}/$FOLDER%")
        val out = mutableListOf<ExportItem>()
        try {
            resolver.query(collection, projection, selection, args, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                while (c.moveToNext()) {
                    out += ExportItem(
                        ContentUris.withAppendedId(collection, c.getLong(idCol)),
                        c.getString(nameCol) ?: "video.mp4",
                        c.getLong(dateCol),
                        c.getLong(durCol),
                    )
                }
            }
        } catch (_: Throwable) {
        }
        return out
    }

    fun thumbnail(uri: Uri): Bitmap? = try {
        resolver.loadThumbnail(uri, Size(320, 320), null)
    } catch (_: Throwable) {
        null
    }

    /** Deletes one of our own exports. Returns false if Android refused. */
    fun delete(uri: Uri): Boolean = try {
        resolver.delete(uri, null, null) > 0
    } catch (_: Throwable) {
        false
    }

    /** Deletes the original video the user picked. Only works for files opened through the system picker. */
    fun deleteOriginal(uri: Uri): Boolean = try {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            DocumentsContract.deleteDocument(resolver, uri)
        } else {
            resolver.delete(uri, null, null) > 0
        }
    } catch (_: Throwable) {
        false
    }

    fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Share to a specific app, or to the system share sheet when [pkg] is null. */
    fun shareIntent(uri: Uri, pkg: String?): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (pkg != null) setPackage(pkg)
        }
        return if (pkg != null) send else Intent.createChooser(send, "Share video")
    }

    companion object {
        const val FOLDER = "PixelVeil"
        const val INSTAGRAM = "com.instagram.android"
        const val YOUTUBE = "com.google.android.youtube"
    }
}
