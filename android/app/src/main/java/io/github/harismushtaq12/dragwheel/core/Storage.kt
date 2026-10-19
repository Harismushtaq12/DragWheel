package io.github.harismushtaq12.dragwheel.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Reading the user's files and saving results to Downloads/DragWheel. */
object Storage {
    private const val FOLDER = "Download/DragWheel"

    fun describe(context: Context, uri: Uri): InputFile {
        var name: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) name = c.getString(i)
            }
        }
        val finalName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val mime = context.contentResolver.getType(uri) ?: mimeFor(finalName.substringAfterLast('.', ""))
        return InputFile(uri, finalName, mime)
    }

    fun open(context: Context, file: InputFile): InputStream =
        context.contentResolver.openInputStream(file.uri) ?: throw IOException("Cannot open ${file.name}")

    /**
     * Creates a file in Downloads/DragWheel (plus an optional subfolder) and lets [write] fill it.
     * Android renames it automatically if the name is taken.
     */
    fun save(
        context: Context,
        name: String,
        mime: String = mimeFor(name.substringAfterLast('.', "")),
        subfolder: String? = null,
        write: (OutputStream) -> Unit,
    ): SavedFile {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (subfolder.isNullOrBlank()) FOLDER else "$FOLDER/$subfolder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create $name in Downloads")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: throw IOException("Could not write $name")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return SavedFile(uri, name, mime)
    }

    fun saveFile(context: Context, source: File, name: String, mime: String = mimeFor(name.substringAfterLast('.', ""))): SavedFile =
        save(context, name, mime) { out -> source.inputStream().use { it.copyTo(out) } }

    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "gif" -> "image/gif"
        "pdf" -> "application/pdf"
        "mp4" -> "video/mp4"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "zip" -> "application/zip"
        "txt" -> "text/plain"
        else -> android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
            ?: "application/octet-stream"
    }
}
